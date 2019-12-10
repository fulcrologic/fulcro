(ns com.fulcrologic.fulcro.networking.http-remote
  (:refer-clojure :exclude [send])
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [cognitect.transit :as ct]
    [com.fulcrologic.fulcro.algorithms.transit :as t]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [edn-query-language.core :as eql]
    [com.fulcrologic.guardrails.core :refer [>defn => >def]]
    [goog.events :as events]
    [taoensso.timbre :as log])
  (:import [goog.net XhrIo EventType ErrorCode]))

(>def ::method #{:post :get :delete :put :head :connect :options :trace :patch})
(>def ::url string?)
(>def ::headers (s/map-of string? string?))
(>def ::body any?)
(>def ::request (s/keys :req-un [::method ::body ::url ::headers]))
(>def ::error #{:none :exception :http-error :network-error :abort
                :middleware-failure :access-denied :not-found :silent :custom :offline
                :timeout})
(>def ::error-text string?)
(>def ::status-code pos-int?)
(>def ::status-text string?)
(>def ::outgoing-request ::request)
(>def ::transaction vector?)
(>def ::progress-phase #{:sending :receiving :complete :failed})
(>def ::progress-event any?)
(>def ::response (s/keys :req-un [::transaction ::outgoing-request ::body ::status-code ::status-text ::error ::error-text]
                   :opt-un [::progress-phase ::progress-event]))
(>def ::xhrio-event any?)
(>def ::xhrio any?)

(>def ::response-middleware fn?)
(>def ::request-middleware fn?)
(>def ::active-requests (s/and
                          #(map? (deref %))
                          #(every? set? (vals (deref %)))))

(>def ::transmit! fn?)
(>def ::abort! fn?)
(>def ::fulcro-remote (s/keys :req-un [::transmit!] :opt-un [::abort!]))

(def xhrio-error-states {(.-NO_ERROR ^js ErrorCode)        :none
                         (.-EXCEPTION ^js ErrorCode)       :exception
                         (.-HTTP_ERROR ^js ErrorCode)      :http-error
                         (.-ABORT ^js ErrorCode)           :abort
                         (.-ACCESS_DENIED ^js ErrorCode)   :access-denied
                         (.-FILE_NOT_FOUND ^js ErrorCode)  :not-found
                         (.-FF_SILENT_ERROR ^js ErrorCode) :silent
                         (.-CUSTOM_ERROR ^js ErrorCode)    :custom
                         (.-OFFLINE ^js ErrorCode)         :offline
                         (.-TIMEOUT ^js ErrorCode)         :timeout})

(defn make-xhrio [] (XhrIo.))
(defn xhrio-dispose [^js xhrio] (.dispose xhrio))
(defn xhrio-enable-progress-events [^js xhrio] (.setProgressEventsEnabled xhrio true))
(defn xhrio-abort [^js xhrio] (.abort xhrio))
(defn xhrio-send [^js xhrio url verb body headers] (.send xhrio url verb body (some-> headers clj->js)))
(defn xhrio-status-code [^js xhrio] (.getStatus xhrio))
(defn xhrio-status-text [^js xhrio] (.getStatusText xhrio))
(defn xhrio-raw-error [^js xhrio] (.getLastErrorCode xhrio))
(defn xhrio-error-code [^js xhrio]
  (let [status (xhrio-status-code xhrio)
        error  (get xhrio-error-states (xhrio-raw-error xhrio) :unknown)
        error  (if (and (= 0 status) (= error :http-error)) :network-error error)]
    error))
(defn xhrio-error-text [^js xhrio] (.getLastError xhrio))
(defn xhrio-response [^js xhrio] (.getResponse xhrio))
(defn xhrio-response-headers [^js xhrio] (js->clj (.getResponseHeaders xhrio)))

(defn xhrio-progress
  "Given an xhrio progress event, returns a map with keys :loaded and :total, where loaded is the
  number of bytes transferred in the given phase (upload/download) and total is the total number
  of bytes to transfer (if known). "
  [^js event]
  {:loaded (.-loaded event) :total (.-total event)})

(defn progress%
  "Takes the progress report from the progress network event
  and returns a number between 0 and 100. `phase` can be `:overall`, `:sending`, or `:receiving`. When
  set to `:overall` then the send phase will count for progress points between 0 and 49, and receiving phase
  will account for 50 to 100. When set to :sending or :receiving the entire range will count for that phase only
  (i.e. once sending is complete this function would return 100 throughout the receiving phase.)

  If total is unknown, then this function returns 0."
  ([progress] (progress% progress :overall))
  ([progress phase]
   (let [current-phase (some-> progress :progress-phase)
         {:keys [loaded total] :or {total 0 loaded 0}} (some-> progress :progress-event xhrio-progress)
         [base max-pct] (cond
                          (= current-phase :complete) [100 100]
                          (= current-phase :failed) [0 0]
                          (and (= current-phase :sending) (= :overall phase)) [0 49]
                          (and (= current-phase :receiving) (= :overall phase)) [50 100]
                          (and (= current-phase :sending) (= :sending phase)) [0 100]
                          (and (= current-phase :receiving) (= :sending phase)) [100 100]
                          (and (= current-phase :sending) (= :receiving phase)) [0 0]
                          (and (= current-phase :receiving) (= :receiving phase)) [0 100])
         slope         (- max-pct base)
         x             (if (= 0 total) 1 (/ loaded total))]
     (if (zero? slope)
       100
       (js/Math.floor (+ base (* x slope)))))))

(def response-types {:default      ""
                     :array-buffer "arraybuffer"
                     :blob         "blob"
                     :document     "document"
                     :text         "text"})
(def legal-response-types (set (keys response-types)))
(>def ::response-type legal-response-types)

(defn desired-response-type [{:keys [body]}]
  (let [nodes (some-> body eql/query->ast :children)
        cnt   (count nodes)
        alt   (some #(-> % :params ::response-type) nodes)]
    (when (and alt (not= 1 cnt))
      (log/error "Attempt to request alternate response from HTTP remote from multiple items in a single transaction. This could mean more than one transaction got combined into a single request."))
    (if (and alt (= 1 cnt) (contains? legal-response-types alt))
      (let [node         (update-in (first nodes) [:params] dissoc ::response-type)
            updated-body [(eql/ast->query node)]]
        [updated-body alt])
      [body :default])))

(defn wrap-fulcro-request
  "Client Remote Middleware to add transit encoding for normal Fulcro requests. Sets the content type and transforms an EDN
  body to a transit+json encoded body. addl-transit-handlers is a map from data type to transit handler (like
  you would pass using the `:handlers` option of transit). The additional handlers are used to encode new data types
  into transit. transit-transformation is a function of one argument returning a transformed transit value (like you
  would pass using the `:transform` option of transit). See transit documentation for more details."
  ([handler addl-transit-handlers transit-transformation]
   (let [writer (t/writer (cond-> {}
                            addl-transit-handlers (assoc :handlers addl-transit-handlers)
                            transit-transformation (assoc :transform transit-transformation)))]
     (fn [{:keys [headers body] :as request}]
       (let [[body response-type] (desired-response-type request)
             body    (ct/write writer body)
             headers (assoc headers "Content-Type" "application/transit+json")]
         (handler (merge request {:body body :headers headers :method :post :response-type response-type}))))))
  ([handler addl-transit-handlers] (wrap-fulcro-request handler addl-transit-handlers nil))
  ([handler] (wrap-fulcro-request handler nil nil))
  ([] (wrap-fulcro-request identity nil nil)))

(defn wrap-csrf-token
  "Client remote request middleware. This middleware can be added to add an X-CSRF-Token header to the request."
  ([csrf-token] (wrap-csrf-token identity csrf-token))
  ([handler csrf-token]
   (fn [request]
     (handler (update request :headers assoc "X-CSRF-Token" csrf-token)))))

(defn wrap-fulcro-response
  "Client remote middleware to transform a network response to a standard Fulcro form.

  This returns a function that will decode a transit response iff the resulting status code is 200 and the
  body is not empty. For errant status codes and empty body: the response body will become an empty map.

  No arguments: Returns a function that can process responses, that is not further chained.
  handler: If supplied, the result of this transformation will be passed through the `handler`.
  addl-transit-handlers is equivalent to the :handlers option in transit: a map from data type to handler.
  "
  ([] (wrap-fulcro-response identity))
  ([handler] (wrap-fulcro-response handler nil))
  ([handler addl-transit-handlers]
   (let [base-handlers {}
         handlers      (if (map? addl-transit-handlers) (merge base-handlers addl-transit-handlers) base-handlers)
         reader        (t/reader {:handlers handlers})]
     (fn fulcro-response-handler [{:keys [body error outgoing-request] :as response}]
       (handler
         (let [{:keys [response-type]} outgoing-request]
           (if (= :default response-type)
             (try
               (if (= :network-error error)
                 response
                 (let [new-body (if (str/blank? body)
                                  {}
                                  (ct/read reader body))
                       response (assoc response :body new-body)]
                   response))
               (catch :default e
                 (log/warn "Transit decode failed!")
                 (assoc response :status-code 417 :status-text "body was either not transit or you have not installed the correct transit read/write handlers.")))
             response)))))))

(defn extract-response
  "Generate a response map from the status of the given xhrio object, which could be in a complete or error state."
  [tx request xhrio]
  [any? ::request ::xhrio => ::response]
  (try
    {:transaction      tx
     :outgoing-request request
     :headers          (xhrio-response-headers xhrio)
     :body             (xhrio-response xhrio)
     :status-code      (xhrio-status-code xhrio)
     :status-text      (xhrio-status-text xhrio)
     :error            (xhrio-error-code xhrio)
     :error-text       (xhrio-error-text xhrio)}
    (catch :default e
      (log/error "Unable to extract response from XhrIO Object" e)
      {:transaction      tx
       :outgoing-request request
       :body             ""
       :headers          {}
       :status-code      0
       :status-text      "Internal Exception"
       :error            :exception
       :error-text       "Internal Exception from XHRIO"})))

(defn was-network-error?
  "Returns true if the given response looks like a low-level network error."
  [{:keys [status-code error]}]
  [::response => boolean?]
  (boolean (and (= 0 status-code) (= :http-error error))))

(defn clear-request*
  [active-requests id xhrio]
  [::active-requests any? ::xhrio => (s/map-of any? set?)]
  (if (every? #(= xhrio %) (get active-requests id))
    (dissoc active-requests id)
    (update active-requests id disj xhrio)))

(defn response-extractor*
  [response-middleware edn real-request xhrio]
  [::response-middleware any? ::request ::xhrio => ::response]
  (fn []
    (let [r (extract-response edn real-request xhrio)]
      (try
        (response-middleware r)
        (catch :default e
          (log/error "Client response middleware threw an exception. " e ". Defaulting to raw response.")
          (merge r {:error                (if (contains? #{nil :none} (:error r)) :middleware-failure (:error r))
                    :middleware-exception e}))))))

(defn cleanup-routine*
  [abort-id active-requests xhrio]
  [::txn/abort-id ::active-requests ::xhrio => fn?]
  (fn []
    (when abort-id
      (swap! active-requests clear-request* abort-id xhrio))
    (xhrio-dispose xhrio)))

(defn ok-routine*
  "Returns a (fn [evt] ) that pulls the response, runs it through middleware, and reports
   the appropriate results to the raw-ok-handler, and progress-routine. If the middleware fails,
   it will instaed report to the error-routine (which in turn will report to the raw error handler)"
  [progress-routine get-response-fn raw-ok-handler error-routine]
  [fn? fn? fn? fn? => any?]
  (fn [evt]
    (let [{:keys [error middleware-exception] :as r} (get-response-fn)]
      (if (= error :middleware-failure)
        (do
          (log/error "Client middleware threw an exception" middleware-exception)
          (progress-routine :failed evt)
          (error-routine r))
        (do
          (progress-routine :complete evt)
          (raw-ok-handler r))))))

(defn progress-routine*
  "Return a (fn [phase progress-event]) that calls the raw update function with progress and response data merged
  together as a response."
  [get-response-fn raw-update-fn]
  [fn? fn? => fn?]
  (fn progress-fn
    [phase evt]
    (when raw-update-fn
      (raw-update-fn (merge {:progress-phase phase
                             :progress-event evt} (get-response-fn))))))

(defn error-routine*
  "Returns a (fn [xhrio-evt]) that pulls the progress and reports it to the progress routine and the raw
  error handler."
  [get-response ok-routine progress-routine raw-error-handler]
  [fn? fn? fn? fn? => fn?]
  (fn [evt]
    (let [r (get-response)]                                 ; middleware can rewrite to be ok...
      (progress-routine :failed evt)
      (if (= 200 (:status-code r))
        (ok-routine evt)
        (raw-error-handler r)))))

(defn fulcro-http-remote
  "Create a remote that (by default) communicates with the given url (which defaults to `/api`).

  The request middleware is a `(fn [request] modified-request)`. The `request` will have `:url`, `:body`, `:method`, and `:headers`. The
  request middleware defaults to `wrap-fulcro-request` (which encodes the request in transit+json). The result of this
  middleware chain on the outgoing request becomes the real outgoing request. It is allowed to modify the `url`.
  If the the request middleware returns a corrupt request or throws an exception then the remote code
  will immediately abort the request. The return value of the middleware will be used to generate a request to `:url`,
  with `:method` (e.g. :post), and the given headers. The body will be sent as-is without further translation.

  `response-middleware` is a function that returns a function `(fn [response] mod-response)` and
  defaults to `wrap-fulcro-response` which decodes the raw response and transforms it back to a response that Fulcro can merge.
  The response will be a map containing the `:transaction`, which is the
  original Fulcro EDN request; `:outgoing-request` which is the exact request sent on the network; `:body`, which
  is the raw data of the response. Additionally, there will be one or more of the following to indicate low-level
  details of the result: `:status-code`, `:status-text`, `:error-code` (one of :none, :exception, :http-error, :abort, or :timeout),
  and `:error-text`.  Middleware is allowed to morph any of this to suit its needs.

  A result with a 200 status code will result in a merge using the resulting response's `:transaction` as the query,
  and the `:body` as the EDN to merge. If the status code is anything else then the details of the response will be
  used when triggering the built-in error handling (e.g. fallbacks, global error handler, etc.)."
  [{:keys [url request-middleware response-middleware] :or {url                 "/api"
                                                            response-middleware (wrap-fulcro-response)
                                                            request-middleware  (wrap-fulcro-request)} :as options}]
  [(s/keys :opt-un [::url ::request-middleware ::response-middleware]) => ::fulcro-remote]
  (merge options
    {:active-requests (atom {})
     :transmit!       (fn transmit! [{:keys [active-requests]} {::txn/keys [ast ast-without-transform result-handler update-handler] :as send-node}]
                        (let [edn              (eql/ast->query ast)
                              merge-edn        (eql/ast->query ast-without-transform)
                              ok-handler       (fn [result]
                                                 (try
                                                   (result-handler result)
                                                   (catch :default e
                                                     (log/error e "Result handler for remote" url "failed with an exception."))))
                              progress-handler (fn [update-msg]
                                                 (let [msg {:status-code      200
                                                            :raw-progress     (select-keys update-msg [:progress-phase :progress-event])
                                                            :overall-progress (progress% update-msg :overall)
                                                            :receive-progress (progress% update-msg :receiving)
                                                            :send-progress    (progress% update-msg :sending)}]
                                                   (when update-handler
                                                     (try
                                                       (update-handler msg)
                                                       (catch :default e
                                                         (log/error e "Update handler for remote" url "failed with an exception."))))))
                              error-handler    (fn [error-result]
                                                 (try
                                                   (result-handler (merge error-result {:status-code 500}))
                                                   (catch :default e
                                                     (log/error e "Error handler for remote" url "failed with an exception."))))]
                          (if-let [real-request (try
                                                  (request-middleware {:headers {} :body edn :url url :method :post})
                                                  (catch :default e
                                                    (log/error e "Send aborted due to middleware failure ")
                                                    nil))]
                            (let [abort-id             (or
                                                         (-> send-node ::txn/options ::txn/abort-id)
                                                         (-> send-node ::txn/options :abort-id))
                                  xhrio                (make-xhrio)
                                  {:keys [body headers url method response-type]} real-request
                                  http-verb            (-> (or method :post) name str/upper-case)
                                  extract-response     #(extract-response body real-request xhrio)
                                  extract-response-mw  (response-extractor* response-middleware merge-edn real-request xhrio)
                                  gc-network-resources (cleanup-routine* abort-id active-requests xhrio)
                                  progress-routine     (progress-routine* extract-response progress-handler)
                                  ok-routine           (ok-routine* progress-routine extract-response-mw ok-handler error-handler)
                                  error-routine        (error-routine* extract-response-mw ok-routine progress-routine error-handler)
                                  with-cleanup         (fn [f] (fn [evt] (try (f evt) (finally (gc-network-resources)))))]
                              (when abort-id
                                (log/info "Registering abort-id" abort-id)
                                (swap! active-requests update abort-id (fnil conj #{}) xhrio))
                              (when (and (legal-response-types response-type) (not= :default response-type))
                                (.setResponseType ^js xhrio (get response-types response-type)))
                              (when progress-handler
                                (xhrio-enable-progress-events xhrio)
                                (events/listen xhrio (.-DOWNLOAD_PROGRESS ^js EventType) #(progress-routine :receiving %))
                                (events/listen xhrio (.-UPLOAD_PROGRESS ^js EventType) #(progress-routine :sending %)))
                              (events/listen xhrio (.-SUCCESS ^js EventType) (with-cleanup ok-routine))
                              (events/listen xhrio (.-ABORT ^js EventType) (with-cleanup #(ok-handler {:status-text   "Cancelled"
                                                                                                       ::txn/aborted? true})))
                              (events/listen xhrio (.-ERROR ^js EventType) (with-cleanup error-routine))
                              (xhrio-send xhrio url http-verb body headers))
                            (error-handler {:error :abort :error-text "Transmission was aborted because the request middleware returned nil or threw an exception"}))))
     :abort!          (fn abort! [this id]
                        (if-let [xhrios (get @(:active-requests this) id)]
                          (doseq [xhrio xhrios]
                            (xhrio-abort xhrio))
                          (log/info "Unable to abort. No active request with abort id:" id)))}))

(defn overall-progress
  "Returns a number between 0 and 100 for the overall progress.  Use in a `progress-action` section of your mutation
  when using the http-remote to monitor network progress."
  [mutation-env]
  (some-> mutation-env :progress :overall-progress))

(defn receive-progress
  "Returns a number between 0 and 100 for the receive progress.  Use in a `progress-action` section of your mutation
  when using the http-remote to monitor network progress."
  [mutation-env]
  (some-> mutation-env :progress :receive-progress))

(defn send-progress
  "Returns a number between 0 and 100 for the send progress.  Use in a `progress-action` section of your mutation
  when using the http-remote to monitor network progress."
  [mutation-env]
  (some-> mutation-env :progress :send-progress))
