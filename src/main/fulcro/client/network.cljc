(ns fulcro.client.network
  (:refer-clojure :exclude [send])
  (:require [fulcro.logging :as log]
            [clojure.spec.alpha :as s]
    #?(:clj
            [clojure.future :refer :all])
            [cognitect.transit :as ct]
    #?(:cljs [goog.events :as events])
            [fulcro.transit :as t]
            [clojure.string :as str])
  #?(:cljs (:import [goog.net XhrIo EventType ErrorCode])))

#?(:cljs
   (def xhrio-error-states {(.-NO_ERROR ErrorCode)        :none
                            (.-EXCEPTION ErrorCode)       :exception
                            (.-HTTP_ERROR ErrorCode)      :http-error
                            (.-ABORT ErrorCode)           :abort
                            (.-ACCESS_DENIED ErrorCode)   :access-denied
                            (.-FILE_NOT_FOUND ErrorCode)  :not-found
                            (.-FF_SILENT_ERROR ErrorCode) :silent
                            (.-CUSTOM_ERROR ErrorCode)    :custom
                            (.-OFFLINE ErrorCode)         :offline
                            (.-TIMEOUT ErrorCode)         :timeout}))

#?(:cljs (defn make-xhrio [] (XhrIo.)))
#?(:cljs (defn xhrio-dispose [xhrio] (.dispose xhrio)))
#?(:cljs (defn xhrio-enable-progress-events [xhrio] (.setProgressEventsEnabled xhrio true)))
#?(:cljs (defn xhrio-abort [xhrio] (.abort xhrio)))
#?(:cljs (defn xhrio-send [xhrio url verb body headers] (.send xhrio url verb body (some-> headers clj->js))))
#?(:cljs (defn xhrio-status-code [xhrio] (.getStatus xhrio)))
#?(:cljs (defn xhrio-status-text [xhrio] (.getStatusText xhrio)))
#?(:cljs (defn xhrio-raw-error [xhrio] (.getLastErrorCode xhrio)))
#?(:cljs (defn xhrio-error-code [xhrio]
           (let [status (xhrio-status-code xhrio)
                 error  (get xhrio-error-states (xhrio-raw-error xhrio) :unknown)
                 error  (if (and (= 0 status) (= error :http-error)) :network-error error)]
             error)))
#?(:cljs (defn xhrio-error-text [xhrio] (.getLastError xhrio)))
#?(:cljs (defn xhrio-response-text [xhrio] (.getResponseText xhrio)))

(defn xhrio-progress
  "Given an xhrio progress event, returns a map with keys :loaded and :total, where loaded is the
  number of bytes transferred in the given phase (upload/download) and total is the total number
  of bytes to transfer (if known). "
  [event]
  {:loaded (.-loaded event) :total (.-total event)})

#?(:cljs
   (defn progress%
     "Takes a map containing :fulcro.client.network/progress (the params map from a progress report mutation)
     and returns a number between 0 and 100. `phase` can be `:overall`, `:sending`, or `:receiving`. When
     set to `:overall` then the send phase will count for progress points between 0 and 49, and receiving phase
     will account for 50 to 100. When set to :sending or :receiving the entire range will count for that phase only
     (i.e. once sending is complete this function would return 100 throughout the receiving phase.)

     If total is unknown, then this function returns 0."
     ([progress] (progress% progress :overall))
     ([progress phase]
      (let [current-phase (some-> progress ::progress :progress-phase)
            {:keys [loaded total] :or {total 0 loaded 0}} (some-> progress ::progress :progress-event xhrio-progress)
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
        (js/Math.floor (+ base (* x slope)))))))

(defn extract-response
  "Generate a response map from the status of the given xhrio object, which could be in a complete or error state."
  [tx request xhrio]
  #?(:clj {}
     :cljs
          {:transaction      tx
           :outgoing-request request
           :body             (xhrio-response-text xhrio)
           :status-code      (xhrio-status-code xhrio)
           :status-text      (xhrio-status-text xhrio)
           :error            (xhrio-error-code xhrio)
           :error-text       (xhrio-error-text xhrio)}))

; Newer protocol that should be used for new networking remotes.
(defprotocol FulcroRemoteI
  (transmit [this request complete-fn error-fn update-fn]
    "Send the given `request`, which will contain:
     - `:fulcro.client.network/edn` : The actual API tx to send.

     It may also optionally include:
     - `:fulcro.client.network/abort-id` : An ID to remember the network request by, to enable user-level API abort

     Exactly one call of complete-fn or error-fn will happen to indicate how the request finished (aborted requests call
     error-fn).

     Two or more calls to update-fn will occur `(fn [progress] )`. Once when the request is sent, once when the overall request is complete (any
     status), and zero or more times during data transfer. It will receive a single map containing the keys
     :progress and :status. The former will be one of `:sending`, `:receiving`, `:failed`, or `:complete`. The latter will
     be the low-level XhrIo progress event.

     complete-fn will be called with a (middleware) response and a query.
     error-fn is `(fn [reason detail])`. Reason will be one of:
       :middleware-aborted - The middleware threw an exception. `detail` will be an exception thrown by the middleware.
       :middleware-failed - The middleware failed to provide a well-formed request. `detail` will be the errant output of the middleware.
       :network-failed - The request did not complete at the network layer. `detail` will include
                         :error-code and :error-text. :error-code will be one of :exception, http-error, :timeout, or :abort.")
  (abort [this abort-id]
    "Cancel the network activity for the given request id, supplied during submission.")
  (network-behavior [this]
    "Returns flags indicating how this remote should behave in the Fulcro stack. Returned flags can include:

     `:fulcro.client.network/serial?` - Should Fulcro create a FIFO queue for requests to this remote, or should all
                                        requests be allowed to go immediately? If not supplied it defaults to true."))

(defn wrap-fulcro-request
  "Client Remote Middleware to add transit encoding for normal Fulcro requests. Sets the content type and transforms an EDN
  body to a transit+json encoded body. addl-transit-handlers is a map from data type to transit handler (like
  you would pass using the `:handlers` option of transit). The
  additional handlers are used to encode new data types into transit. See transit documentation for more details."
  ([handler addl-transit-handlers]
    #?(:clj identity
       :cljs
            (let [writer (t/writer (if addl-transit-handlers {:handlers addl-transit-handlers} {}))]
              (fn [{:keys [headers body] :as request}]
                (let [body    (ct/write writer body)
                      headers (assoc headers "Content-Type" "application/transit+json")]
                  (handler (merge request {:body body :headers headers :method :post})))))))
  ([handler] (wrap-fulcro-request handler nil))
  ([] (wrap-fulcro-request identity nil)))

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
    #?(:clj identity
       :cljs
            (let [base-handlers {"f" (fn [v] (js/parseFloat v)) "u" cljs.core/uuid}
                  handlers      (if (map? addl-transit-handlers) (merge base-handlers addl-transit-handlers) base-handlers)
                  reader        (t/reader {:handlers handlers})]
              (fn [{:keys [body] :as response}]
                (handler
                  (try
                    (let [new-body (if (str/blank? body)
                                     {}
                                     (ct/read reader body))
                          response (assoc response :body new-body)]
                      response)
                    (catch js/Object e
                      (log/error "Transit decode failed!" e)
                      response))))))))

(s/def ::method #{:post :get :delete :put :head :connect :options :trace :patch})
(s/def ::url string?)
(s/def ::abort-id any?)
(s/def ::headers (s/map-of string? string?))
(s/def ::body any?)
(s/def ::request (s/keys :req_un [::method ::body ::url ::headers]))
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
(s/def ::response (s/keys :req_un [::transaction ::outgoing-request ::body ::status-code ::status-text ::error ::error-text]
                    :opt_un [::progress-phase ::progress-event]))
(s/def ::xhrio-event any?)
(s/def ::xhrio any?)

(s/def ::response-middleware (s/fspec
                               :args (s/cat :r ::response)
                               :ret ::response))
(s/def ::request-middleware (s/fspec
                              :args (s/cat :r ::request)
                              :ret ::request))
(s/def ::active-requests (s/map-of any? set?))

(s/fdef extract-response
  :args (s/cat :tx ::transaction :req ::request :xhrio ::xhrio)
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
  #?(:cljs
     (fn []
       (let [r (extract-response edn real-request xhrio)]
         (try
           (response-middleware r)
           (catch :default e
             (log/error "Client response middleware threw an exception. " e ". Defaulting to raw response.")
             (merge r {:error                (if (contains? #{nil :none} (:error r)) :middleware-failure (:error r))
                       :middleware-exception e})))))))

(s/fdef response-extractor*
  :args (s/cat :mw ::response-middleware :tx any? :req ::request :xhrio ::xhrio)
  :ret (s/fspec :ret ::response))

(defn cleanup-routine*
  [abort-id active-requests xhrio]
  #?(:cljs (fn []
             (when abort-id
               (swap! active-requests clear-request* abort-id xhrio))
             (xhrio-dispose xhrio))))

(s/fdef cleanup-routine*
  :args (s/cat :id any? :active-requests ::active-requests :xhrio ::xhrio)
  :ret fn?)

(defn ok-routine*
  "Returns a (fn [evt] ) that pulls the response, runs it through middleware, and reports
   the appropriate results to the raw-ok-handler, and progress-routine. If the middleware fails,
   it will instaed report to the error-routine (which in turn will report to the raw error handler)"
  [progress-routine get-response-fn raw-ok-handler error-routine]
  #?(:cljs
     (fn [evt]
       (let [{:keys [error middleware-exception] :as r} (get-response-fn)]
         (if (= error :middleware-failure)
           (do
             (log/error "Client middleware threw an exception" middleware-exception)
             (progress-routine :failed evt)
             (error-routine r))
           (do
             (progress-routine :complete evt)
             (raw-ok-handler r)))))))

(s/fdef ok-routine* :args (s/cat :progress fn? :get-response fn? :complete-fn fn? :error-fn :fn?))

(defn progress-routine*
  "Return a (fn [phase progress-event]) that calls the raw update function with progress and response data merged
  together as a response."
  [get-response-fn raw-update-fn]
  (fn [phase evt] (when raw-update-fn
                    (raw-update-fn (merge {:progress-phase phase
                                           :progress-event evt} (get-response-fn))))))

(s/fdef progress-routine* :args (s/cat :response-fn fn? :update fn?))

(defn error-routine*
  "Returns a (fn [xhrio-evt]) that pulls the progress and reports it to the progress routine and the raw
  error handler."
  [get-response progress-routine raw-error-handler]
  (fn [evt]
    (let [r (get-response)]
      (progress-routine :failed evt)
      (raw-error-handler r))))

(s/fdef error-routine* :args (s/cat :get fn? :progress fn? :error fn?))

(defrecord FulcroHTTPRemote [url request-middleware response-middleware active-requests serial?]
  FulcroRemoteI
  (transmit [this {:keys [::edn ::abort-id] :as raw-request} raw-ok-fn raw-error-fn raw-progress-fn]
    #?(:cljs
       (if-let [real-request (try (request-middleware {:headers {} :body edn :url url :method :post})
                                  (catch :default e
                                    (log/error "Send aborted due to middleware failure " e)
                                    nil))]
         (let [xhrio                (make-xhrio)
               {:keys [body headers url method]} real-request
               http-verb            (-> (or method :post) name str/upper-case)
               extract-response     (response-extractor* response-middleware edn real-request xhrio)
               gc-network-resources (cleanup-routine* abort-id active-requests xhrio)
               progress-routine     (progress-routine* extract-response raw-progress-fn)
               ok-routine           (ok-routine* progress-routine extract-response raw-ok-fn raw-error-fn)
               error-routine        (error-routine* extract-response progress-routine raw-error-fn)
               with-cleanup         (fn [f] (fn [evt] (try (f evt) (finally (gc-network-resources)))))]
           (when abort-id
             (swap! active-requests update abort-id (fnil conj #{}) xhrio))
           (when raw-progress-fn
             (xhrio-enable-progress-events xhrio)
             (events/listen xhrio (.-DOWNLOAD_PROGRESS EventType) #(progress-routine :receiving %))
             (events/listen xhrio (.-UPLOAD_PROGRESS EventType) #(progress-routine :sending %)))
           (events/listen xhrio (.-SUCCESS EventType) (with-cleanup ok-routine))
           (events/listen xhrio (.-ABORT EventType) (with-cleanup #(raw-ok-fn {})))
           (events/listen xhrio (.-ERROR EventType) (with-cleanup error-routine))
           (xhrio-send xhrio url http-verb body headers))
         (raw-error-fn {:error :abort :error-text "Transmission was aborted because the request middleware threw an exception"}))))
  (abort [this id]
    #?(:cljs (when-let [xhrios (get @active-requests id)]
               (doseq [xhrio xhrios]
                 (xhrio-abort xhrio)))))
  (network-behavior [this] {::serial? serial?}))

(s/fdef transmit
  :args (s/cat
          :remote any?
          :raw-request (s/keys :req [::edn] :opt [::abort-id])
          :complete-fn ::ok-handler
          :error-fn ::error-handler
          :update-fn ::progress-handler))

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

  A result with a 200 status code will result in a merge using the resulting response's `:transaction` as the query,
  and the `:body` as the EDN to merge. If the status code is anything else then the details of the response will be
  used when triggering the built-in error handling (e.g. fallbacks, global error handler, etc.)."
  [{:keys [url request-middleware response-middleware serial?] :or {url                 "/api"
                                                                    response-middleware (wrap-fulcro-response)
                                                                    serial?             true
                                                                    request-middleware  (wrap-fulcro-request)} :as options}]
  (map->FulcroHTTPRemote (merge options {:request-middleware  request-middleware
                                         :response-middleware response-middleware
                                         :active-requests     (atom {})})))


(comment
  (log/set-level! :all)
  (let [r (fulcro-http-remote {:url "http://localhost:8085/api"})
        c (fn [r] (js/console.log :complete r))
        e (fn [e v] (js/console.log :error e v))
        u (fn [u] (js/console.log :update u))]
    (transmit r {::edn [:hello]} c e u)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Everything below this is DEPRECATED. Use code above this in new programs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare make-fulcro-network)

(defprotocol NetworkBehavior
  (serialize-requests? [this] "DEPRECATED. Returns true if the network is configured to desire one request at a time."))

(defprotocol ProgressiveTransfer
  (updating-send [this edn done-callback error-callback update-callback] "DEPRECATED. Send EDN. The update-callback will merge the state
  given to it. The done-callback will merge the state given to it, and indicates completion. See
  `fulcro.client.ui.file-upload/FileUploadNetwork` for an example."))

(defprotocol FulcroNetwork
  (send [this edn done-callback error-callback]
    "DEPRECATED. Send EDN. Calls either the done or error callback when the send is done. You must call one of those only once.
     Implement ProgressiveTransfer if you want to do progress updates during network transmission.")
  (start [this]
    "Starts the network."))

(defprotocol IXhrIOCallbacks
  (response-ok [this xhrio ok-cb] "Called by XhrIo on OK")
  (response-error [this xhrio err-cb] "Called by XhrIo on ERROR"))

#?(:cljs
   (defn parse-response
     "DEPRECATED. An XhrIo-specific implementation method for interpreting the server response."
     ([xhr-io] (parse-response xhr-io nil))
     ([xhr-io read-handlers]
      (try (let [text          (.getResponseText xhr-io)
                 base-handlers {"f" (fn [v] (js/parseFloat v)) "u" cljs.core/uuid}
                 handlers      (if (map? read-handlers) (merge base-handlers read-handlers) base-handlers)]
             (if (str/blank? text)
               (.getStatus xhr-io)
               (ct/read (t/reader {:handlers handlers})
                 (.getResponseText xhr-io))))
           (catch js/Object e {:error 404 :message "Server down"})))))

(defrecord Network [url request-transform global-error-callback complete-app transit-handlers]
  NetworkBehavior
  (serialize-requests? [this] true)
  IXhrIOCallbacks
  (response-ok [this xhr-io valid-data-callback]
    ;; Implies:  everything went well and we have a good response
    ;; (i.e., got a 200).
    #?(:cljs
       (try
         (let [read-handlers  (:read transit-handlers)
               query-response (parse-response xhr-io read-handlers)]
           (when valid-data-callback (valid-data-callback (or query-response {}))))
         (finally (.dispose xhr-io)))))
  (response-error [this xhr-io error-callback]
    ;; Implies:  request was sent.
    ;; *Always* called if completed (even in the face of network errors).
    ;; Used to detect errors.
    #?(:cljs
       (try
         (let [status                 (.getStatus xhr-io)
               log-and-dispatch-error (fn [str error]
                                        ;; note that impl.application/initialize will partially apply the
                                        ;; app-state as the first arg to global-error-callback
                                        (log/error str)
                                        (error-callback error)
                                        (when @global-error-callback
                                          (@global-error-callback status error)))]
           (if (zero? status)
             (log-and-dispatch-error
               (str "NETWORK ERROR: No connection established.")
               {:type :network})
             (log-and-dispatch-error
               (str "SERVER ERROR CODE: " status)
               (parse-response xhr-io transit-handlers))))
         (finally (.dispose xhr-io)))))
  FulcroNetwork
  (send [this edn ok error]
    #?(:cljs
       (let [xhrio     (make-xhrio)
             handlers  (or (:write transit-handlers) {})
             headers   {"Content-Type" "application/transit+json"}
             {:keys [body headers]} (cond-> {:body edn :headers headers}
                                      request-transform request-transform)
             post-data (ct/write (t/writer {:handlers handlers}) body)
             headers   (clj->js headers)]
         (events/listen xhrio (.-SUCCESS EventType) #(response-ok this xhrio ok))
         (events/listen xhrio (.-ERROR EventType) #(response-error this xhrio error))
         (.send xhrio url "POST" post-data headers))))
  (start [this] this))

(defn make-fulcro-network
  "DERECATED: Use `make-fulcro-remote` instead.

  Build a Fulcro Network object using the default implementation.

  Features:

  - `:url` is the target URL suffix (URI) on the server for network requests. defaults to /api.
  - `:request-transform` is a (fn [{:keys [body headers] :as req}] req') to transform arbitrary requests (e.g. to add things like auth headers)
  - `:global-error-callback` is a global error callback (fn [app-state-map status-code error] ) that is notified when a 400+ status code or hard network error occurs
  - `transit-handlers` is a map of transit handlers to install on the reader, such as

   `{ :read { \"thing\" (fn [wire-value] (convert wire-value))) }
      :write { Thing (ThingHandler.) } }`

   where:

   (defrecord Thing [foo])

   (deftype ThingHandler []
     Object
     (tag [_ _] \"thing\")
     (rep [_ thing] (make-raw thing))
     (stringRep [_ _] nil)))
  "
  [url & {:keys [request-transform global-error-callback transit-handlers]}]
  (map->Network {:url                   url
                 :transit-handlers      transit-handlers
                 :request-transform     request-transform
                 :global-error-callback (atom global-error-callback)}))

(defrecord MockNetwork
  [complete-app]
  FulcroNetwork
  (send [this edn ok err] (log/info "Ignored (mock) Network request " edn))
  (start [this] this))

(defn mock-network [] (map->MockNetwork {}))

(comment
  (log/set-level! :all)
  (let [r (fulcro-http-remote {:url "http://localhost:8085/api"})
        c (fn [r] (js/console.log :complete r))
        e (fn [e v] (js/console.log :error e v))
        u (fn [u] (js/console.log :update u))]
    (transmit r {::edn [:hello]} c e u))
  (let [r (make-fulcro-network "http://localhost:8085/api")
        c (fn [r] (js/console.log :complete r))
        e (fn [e v] (js/console.log :error e v))]
    (send r [:hello] c e)))
