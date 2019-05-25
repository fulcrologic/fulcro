(ns com.fulcrologic.fulcro.networking.http-remote
  (:refer-clojure :exclude [send])
  (:require
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [taoensso.timbre :as log]
    [clojure.spec.alpha :as s]
    [cognitect.transit :as ct]
    [goog.events :as events]
    [com.fulcrologic.fulcro.algorithms.transit :as t]
    [clojure.string :as str]
    [edn-query-language.core :as eql])
  (:import [goog.net XhrIo EventType ErrorCode]))

(def xhrio-error-states {(.-NO_ERROR ErrorCode)        :none
                         (.-EXCEPTION ErrorCode)       :exception
                         (.-HTTP_ERROR ErrorCode)      :http-error
                         (.-ABORT ErrorCode)           :abort
                         (.-ACCESS_DENIED ErrorCode)   :access-denied
                         (.-FILE_NOT_FOUND ErrorCode)  :not-found
                         (.-FF_SILENT_ERROR ErrorCode) :silent
                         (.-CUSTOM_ERROR ErrorCode)    :custom
                         (.-OFFLINE ErrorCode)         :offline
                         (.-TIMEOUT ErrorCode)         :timeout})

(defn make-xhrio [] (XhrIo.))
(defn xhrio-dispose [xhrio] (.dispose xhrio))
(defn xhrio-enable-progress-events [xhrio] (.setProgressEventsEnabled xhrio true))
(defn xhrio-abort [xhrio] (.abort xhrio))
(defn xhrio-send [xhrio url verb body headers] (.send xhrio url verb body (some-> headers clj->js)))
(defn xhrio-status-code [xhrio] (.getStatus xhrio))
(defn xhrio-status-text [xhrio] (.getStatusText xhrio))
(defn xhrio-raw-error [xhrio] (.getLastErrorCode xhrio))
(defn xhrio-error-code [xhrio]
  (let [status (xhrio-status-code xhrio)
        error  (get xhrio-error-states (xhrio-raw-error xhrio) :unknown)
        error  (if (and (= 0 status) (= error :http-error)) :network-error error)]
    error))
(defn xhrio-error-text [xhrio] (.getLastError xhrio))
(defn xhrio-response-text [xhrio] (.getResponseText xhrio))
(defn xhrio-response-headers [xhrio] (js->clj (.getResponseHeaders xhrio)))

(defn xhrio-progress
  "Given an xhrio progress event, returns a map with keys :loaded and :total, where loaded is the
  number of bytes transferred in the given phase (upload/download) and total is the total number
  of bytes to transfer (if known). "
  [event]
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

(defn extract-response
  "Generate a response map from the status of the given xhrio object, which could be in a complete or error state."
  [tx request xhrio]
  (try
    {:transaction      tx
     :outgoing-request request
     :headers          (xhrio-response-headers xhrio)
     :body             (xhrio-response-text xhrio)
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
       (let [body    (ct/write writer body)
             headers (assoc headers "Content-Type" "application/transit+json")]
         (handler (merge request {:body body :headers headers :method :post}))))))
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
   (let [base-handlers {"f" (fn [v] (js/parseFloat v)) "u" cljs.core/uuid}
         handlers      (if (map? addl-transit-handlers) (merge base-handlers addl-transit-handlers) base-handlers)
         reader        (t/reader {:handlers handlers})]
     (fn fulcro-response-handler [{:keys [body] :as response}]
       (handler
         (try
           (let [new-body (if (str/blank? body)
                            {}
                            (ct/read reader body))
                 response (assoc response :body new-body)]
             response)
           (catch js/Object e
             (log/error "Transit decode failed!" e)
             response)))))))

(s/def ::method #{:post :get :delete :put :head :connect :options :trace :patch})
(s/def ::url string?)
(s/def ::abort-id any?)
(s/def ::headers (s/map-of string? string?))
(s/def ::body any?)
(s/def ::request (s/keys :req-un [::method ::body ::url ::headers]))
(s/def ::error #{:none :exception :http-error :network-error :abort
                 :middleware-failure :access-denied :not-found :silent :custom :offline
                 :timeout})
(s/def ::error-text string?)
(s/def ::status-code pos-int?)
(s/def ::status-text string?)
(s/def ::outgoing-request ::request)
(s/def ::transaction vector?)
(s/def ::progress-phase #{:sending :receiving :complete :failed})
(s/def ::progress-event any?)
(s/def ::response (s/keys :req-un [::transaction ::outgoing-request ::body ::status-code ::status-text ::error ::error-text]
                    :opt-un [::progress-phase ::progress-event]))
(s/def ::xhrio-event any?)
(s/def ::xhrio any?)

(s/def ::response-middleware fn?)
(s/def ::request-middleware (s/fspec
                              :args (s/cat :r ::request)
                              :ret ::request))
(s/def ::active-requests (s/and
                           #(map? (deref %))
                           #(every? set? (vals (deref %)))))

(s/fdef extract-response
  :args (s/cat :tx any? :req ::request :xhrio ::xhrio)
  :ret ::response)

(defn was-network-error?
  "Returns true if the given response looks like a low-level network error."
  [{:keys [status-code error]}] (and (= 0 status-code) (= :http-error error)))

(s/fdef was-network-error?
  :args (s/cat :r ::response)
  :ret boolean?)

(defn clear-request* [active-requests id xhrio]
  (if (every? #(= xhrio %) (get active-requests id))
    (dissoc active-requests id)
    (update active-requests id disj xhrio)))

(s/fdef clear-request*
  :args (s/cat :active-requests ::active-requests :id any? :xhrio ::xhrio)
  :ret (s/map-of any? set?))

(defn response-extractor*
  [response-middleware edn real-request xhrio]
  (fn []
    (let [r (extract-response edn real-request xhrio)]
      (try
        (response-middleware r)
        (catch :default e
          (log/error "Client response middleware threw an exception. " e ". Defaulting to raw response.")
          (merge r {:error                (if (contains? #{nil :none} (:error r)) :middleware-failure (:error r))
                    :middleware-exception e}))))))

(s/fdef response-extractor*
  :args (s/cat :mw ::response-middleware :tx any? :req ::request :xhrio ::xhrio)
  :ret (s/fspec :ret ::response))

(defn active?
  "Returns true if any of networks (obtained by querying `[::net/status '_]`) are active.  If passed a remote
  as a second argument if returns whether or not that particular remote is active."
  ([network-markers]
   (->> network-markers vals (some #{:active}) boolean))
  ([network-markers remote]
   (= :active (get network-markers remote))))

(defn cleanup-routine*
  [abort-id active-requests xhrio]
  (fn []
    (when abort-id
      (swap! active-requests clear-request* abort-id xhrio))
    (xhrio-dispose xhrio)))

(s/fdef cleanup-routine*
  :args (s/cat :id any? :active-requests ::active-requests :xhrio ::xhrio)
  :ret fn?)

(defn ok-routine*
  "Returns a (fn [evt] ) that pulls the response, runs it through middleware, and reports
   the appropriate results to the raw-ok-handler, and progress-routine. If the middleware fails,
   it will instaed report to the error-routine (which in turn will report to the raw error handler)"
  [progress-routine get-response-fn raw-ok-handler error-routine]
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

(s/fdef ok-routine* :args (s/cat :progress fn? :get-response fn? :complete-fn fn? :error-fn fn?))

(defn progress-routine*
  "Return a (fn [phase progress-event]) that calls the raw update function with progress and response data merged
  together as a response."
  [get-response-fn raw-update-fn]
  (fn progress-fn
    [phase evt]
    (when raw-update-fn
      (raw-update-fn (merge {:progress-phase phase
                             :progress-event evt} (get-response-fn))))))

(s/fdef progress-routine* :args (s/cat :response-fn fn? :update (s/or :none nil? :func fn?)))

(defn error-routine*
  "Returns a (fn [xhrio-evt]) that pulls the progress and reports it to the progress routine and the raw
  error handler."
  [get-response ok-routine progress-routine raw-error-handler]
  (fn [evt]
    (let [r (get-response)]                                 ; middleware can rewrite to be ok...
      (progress-routine :failed evt)
      (if (= 200 (:status-code r))
        (ok-routine evt)
        (raw-error-handler r)))))

(s/fdef error-routine* :args (s/cat :get fn? :ok fn? :progress fn? :error fn?))

(defn fulcro-http-remote
  "Create a remote that (by default) communicates with the given url.

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

  `serial?` - A boolean (default true). Should requests to this remote be queued sequentially (false means they will hit the network
  as submitted, true means the prior one has to complete (by default) before the next starts).  Loads can be made parallel
  with a load option, so you should typically not override this option.

  A result with a 200 status code will result in a merge using the resulting response's `:transaction` as the query,
  and the `:body` as the EDN to merge. If the status code is anything else then the details of the response will be
  used when triggering the built-in error handling (e.g. fallbacks, global error handler, etc.)."
  [{:keys [url request-middleware response-middleware serial?] :or {url                 "/api"
                                                                    response-middleware (wrap-fulcro-response)
                                                                    request-middleware  (wrap-fulcro-request)} :as options}]
  (merge options
    {:active-requests (atom {})
     :serial?         serial?
     :transmit!       (fn transmit! [{:keys [active-requests]} {:keys [::txn/ast ::txn/result-handler ::txn/update-handler] :as send-node}]
                        (let [edn              (log/spy :info (eql/ast->query ast))
                              ok-handler       (fn [result]
                                                 (try
                                                   (result-handler (select-keys result #{:transaction :status-code :body :status-text}))
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
                                                   (result-handler (merge {:status-code 500} (select-keys error-result #{:transaction :status-code :body :status-text})))
                                                   (catch :default e
                                                     (log/error e "Error handler for remote" url "failed with an exception."))))]
                          (if-let [real-request (try (request-middleware {:headers {} :body edn :url url :method :post})
                                                     (catch :default e
                                                       (log/error "Send aborted due to middleware failure " e)
                                                       nil))]
                            (let [abort-id             (::txn/id send-node)
                                  xhrio                (make-xhrio)
                                  {:keys [body headers url method]} real-request
                                  http-verb            (-> (or method :post) name str/upper-case)
                                  extract-response     #(extract-response body real-request xhrio)
                                  extract-response-mw  (response-extractor* response-middleware edn real-request xhrio)
                                  gc-network-resources (cleanup-routine* abort-id active-requests xhrio)
                                  progress-routine     (progress-routine* extract-response progress-handler)
                                  ok-routine           (ok-routine* progress-routine extract-response-mw ok-handler error-handler)
                                  error-routine        (error-routine* extract-response-mw ok-routine progress-routine error-handler)
                                  with-cleanup         (fn [f] (fn [evt] (try (f evt) (finally (gc-network-resources)))))]
                              (when abort-id
                                (swap! active-requests update abort-id (fnil conj #{}) xhrio))
                              (when progress-handler
                                (xhrio-enable-progress-events xhrio)
                                (events/listen xhrio (.-DOWNLOAD_PROGRESS EventType) #(progress-routine :receiving %))
                                (events/listen xhrio (.-UPLOAD_PROGRESS EventType) #(progress-routine :sending %)))
                              (events/listen xhrio (.-SUCCESS EventType) (with-cleanup ok-routine))
                              (events/listen xhrio (.-ABORT EventType) (with-cleanup #(ok-handler {})))
                              (events/listen xhrio (.-ERROR EventType) (with-cleanup error-routine))
                              (xhrio-send xhrio url http-verb body headers))
                            (error-handler {:error :abort :error-text "Transmission was aborted because the request middleware threw an exception"}))))
     :abort!          (fn abort! [this id]
                        (when-let [xhrios (get @(:active-requests this) id)]
                          (doseq [xhrio xhrios]
                            (xhrio-abort xhrio))))}))
