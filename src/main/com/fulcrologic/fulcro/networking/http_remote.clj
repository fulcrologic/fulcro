(ns com.fulcrologic.fulcro.networking.http-remote
  "CLJ HTTP remote for Fulcro. Mirrors the CLJS `http-remote` middleware API but uses an injected
   HTTP driver function instead of Google Closure's XhrIo. This allows headless CLJ apps and tests
   to communicate with a real Fulcro server over HTTP.

   The `:http-driver` is a **synchronous** function with signature:

       (fn [{:keys [url method headers body]}]
         {:status int, :headers map, :body string, :error nil-or-exception})

   IF you have http-kit on your CLASSPATH, you can use the `com.fulcrologic.fulcro.networking.http-kit-driver`.

   The remote runs the driver on a future internally so it does not block the calling thread.
   Pass `:synchronous? true` to `fulcro-http-remote` to skip the future and run the driver
   on the calling thread (required for headless test apps that use synchronous tx processing)."
  (:refer-clojure :exclude [send])
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as futil]
    [com.fulcrologic.fulcro.algorithms.transit :as t]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.guardrails.core :refer [>def]]
    [taoensso.timbre :as log])
  (:import
    (java.io ByteArrayInputStream ByteArrayOutputStream)))

;; =============================================================================
;; Specs
;; =============================================================================

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
(>def ::response (s/keys :req-un [::outgoing-request ::body ::status-code ::status-text ::error ::error-text]
                   :opt-un [::transaction]))

(>def ::response-middleware fn?)
(>def ::request-middleware fn?)
(>def ::active-requests (s/and
                          #(map? (deref %))
                          #(every? set? (vals (deref %)))))

(>def ::transmit! fn?)
(>def ::abort! fn?)
(>def ::fulcro-remote (s/keys :req-un [::transmit!] :opt-un [::abort!]))

;; =============================================================================
;; Transit helpers (internal)
;; =============================================================================

(defn- transit-encode
  "Encode `value` as a transit+json string. Merges any `addl-handlers` into the writer."
  [value addl-handlers transit-transformation]
  (let [out  (ByteArrayOutputStream.)
        opts (cond-> {}
               addl-handlers (assoc :handlers addl-handlers)
               transit-transformation (assoc :transform transit-transformation))
        w    (t/writer out opts)]
    (cognitect.transit/write w value)
    (.toString out "UTF-8")))

(defn- transit-decode
  "Decode a transit+json `string` to EDN. Merges any `addl-handlers` into the reader."
  [string addl-handlers]
  (let [in   (ByteArrayInputStream. (.getBytes ^String string "UTF-8"))
        opts (cond-> {}
               addl-handlers (assoc :handlers addl-handlers))
        r    (t/reader in opts)]
    (cognitect.transit/read r)))

;; =============================================================================
;; HTTP status helpers
;; =============================================================================

(defn- status-text-for
  "Returns a human-readable status text for `status-code`."
  [status-code]
  (case (int (or status-code 0))
    200 "OK"
    400 "Bad Request"
    401 "Unauthorized"
    403 "Forbidden"
    404 "Not Found"
    408 "Request Timeout"
    417 "Expectation Failed"
    500 "Internal Server Error"
    502 "Bad Gateway"
    503 "Service Unavailable"
    (str "HTTP " status-code)))

(defn- classify-error
  "Classify the driver response into a Fulcro error keyword."
  [{:keys [status error]}]
  (cond
    (some? error) :exception
    (nil? status) :network-error
    (<= 200 status 299) :none
    (= 408 status) :timeout
    :else :http-error))

(defn- error-text-for
  "Returns a human-readable error text from a driver response."
  [{:keys [status error]}]
  (cond
    (some? error) (str error)
    (nil? status) "Network error"
    (<= 200 status 299) ""
    :else (status-text-for status)))

;; =============================================================================
;; Middleware (public API — matching CLJS arities)
;; =============================================================================

(defn wrap-fulcro-request
  "Client remote middleware to add transit encoding for Fulcro requests. Sets the content type and
   transforms an EDN body to a transit+json encoded body.

   `addl-transit-handlers` is a map from data type to transit handler (the `:handlers` option of transit).
   `transit-transformation` is a function of one argument returning a transformed transit value
   (the `:transform` option of transit)."
  ([handler addl-transit-handlers transit-transformation]
   (fn [{:keys [headers body] :as request}]
     (let [body    (transit-encode body addl-transit-handlers transit-transformation)
           headers (assoc headers
                     "Content-Type" "application/transit+json"
                     "Accept" "application/transit+json")]
       (handler (merge request {:body body :headers headers :method :post})))))
  ([handler addl-transit-handlers] (wrap-fulcro-request handler addl-transit-handlers nil))
  ([handler] (wrap-fulcro-request handler nil nil))
  ([] (wrap-fulcro-request identity nil nil)))

(defn wrap-csrf-token
  "Client remote request middleware. Adds an X-CSRF-Token header to the request."
  ([csrf-token] (wrap-csrf-token identity csrf-token))
  ([handler csrf-token]
   (fn [request]
     (handler (update request :headers assoc "X-CSRF-Token" csrf-token)))))

(defn wrap-fulcro-response
  "Client remote middleware to transform a network response to standard Fulcro form.

   Decodes a transit response when the status code is 200 and the body is not empty.
   For errant status codes or empty body the response body becomes an empty map.

   `addl-transit-handlers` is equivalent to the `:handlers` option in transit: a map from tag to handler."
  ([] (wrap-fulcro-response identity))
  ([handler] (wrap-fulcro-response handler nil))
  ([handler addl-transit-handlers]
   (fn fulcro-response-handler [{:keys [body error] :as response}]
     (handler
       (try
         (if (= :network-error error)
           response
           (let [new-body (if (str/blank? body)
                            {}
                            (transit-decode body addl-transit-handlers))
                 response (assoc response :body new-body)]
             response))
         (catch Exception e
           (log/warn e "Transit decode failed!")
           (assoc response
             :status-code 417
             :status-text "Body was either not transit or you have not installed the correct transit read/write handlers.")))))))

;; =============================================================================
;; Response extraction
;; =============================================================================

(defn extract-response
  "Convert a driver response map into a Fulcro response shape."
  [edn request driver-response]
  (try
    {:outgoing-request     request
     :original-transaction edn
     :headers              (or (:headers driver-response) {})
     :body                 (or (:body driver-response) "")
     :status-code          (or (:status driver-response) 0)
     :status-text          (status-text-for (:status driver-response))
     :error                (classify-error driver-response)
     :error-text           (error-text-for driver-response)}
    (catch Exception e
      (log/error e "Unable to extract response from driver response")
      {:outgoing-request     request
       :original-transaction edn
       :body                 ""
       :headers              {}
       :status-code          0
       :status-text          "Internal Exception"
       :error                :exception
       :error-text           "Internal exception from HTTP driver"})))

;; =============================================================================
;; Internal routines (same pattern as CLJS, simplified without progress)
;; =============================================================================

(defn- clear-request*
  "Remove a future from the active-requests map for `id`."
  [active-requests id fut]
  (if (every? #(= fut %) (get active-requests id))
    (dissoc active-requests id)
    (update active-requests id disj fut)))

(defn- response-extractor*
  "Returns a thunk that extracts the response and runs it through `response-middleware`."
  [response-middleware edn real-request driver-response]
  (fn []
    (let [r (extract-response edn real-request driver-response)]
      (try
        (response-middleware r)
        (catch Exception e
          (log/error e "Client response middleware threw an exception. Defaulting to raw response.")
          (merge r {:error                (if (contains? #{nil :none} (:error r)) :middleware-failure (:error r))
                    :middleware-exception e}))))))

(defn- ok-routine*
  "Returns a function that pulls the response through middleware and reports to ok-handler.
   If middleware fails, reports to error-routine instead."
  [get-response-fn raw-ok-handler error-routine]
  (fn []
    (let [{:keys [error middleware-exception] :as r} (get-response-fn)]
      (if (= error :middleware-failure)
        (do
          (log/error "Client middleware threw an exception" middleware-exception)
          (error-routine r))
        (raw-ok-handler r)))))

(defn- error-routine*
  "Returns a function that pulls the response through middleware and reports to error-handler.
   If middleware rewrites to 200, defers to ok-routine instead."
  [get-response ok-routine raw-error-handler]
  (fn [& [evt-or-response]]
    (let [r (if (map? evt-or-response) evt-or-response (get-response))]
      (if (= 200 (:status-code r))
        (ok-routine)
        (raw-error-handler r)))))

;; =============================================================================
;; Remote factory
;; =============================================================================

(defn fulcro-http-remote
  "Create a CLJ remote that communicates over HTTP using an injected driver function.

   The `options` map supports:

   * `:url` - The URL to contact (default `\"/api\"`)
   * `:http-driver` - **(required)** A synchronous function with signature
     `(fn [{:keys [url method headers body]}] {:status int :headers map :body string :error nil})`
   * `:request-middleware` - Middleware chain applied to outgoing requests (default `(wrap-fulcro-request)`)
   * `:response-middleware` - Middleware chain applied to incoming responses (default `(wrap-fulcro-response)`)
   * `:preprocess-error` - A `(fn [result] result)` applied before reporting errors (default adds `:status-code 500`)
   * `:synchronous?` - When `true`, runs the HTTP driver on the calling thread instead of a future.
     Required for headless test apps that use synchronous tx processing (default `false`)."
  [{:keys [url http-driver request-middleware response-middleware preprocess-error synchronous?]
    :or   {url                 "/api"
           response-middleware (wrap-fulcro-response)
           request-middleware  (wrap-fulcro-request)
           preprocess-error    (fn [r] (merge r {:status-code 500}))
           synchronous?        false}
    :as   options}]
  (when-not http-driver
    (throw (IllegalArgumentException. "fulcro-http-remote requires an :http-driver function. See docstring.")))
  (let [active-requests (atom {})]
    (merge options
      {:active-requests    active-requests
       :supports-raw-body? true
       :transmit!          (fn transmit! [{:keys [active-requests]} {::txn/keys [ast raw-body result-handler update-handler options] :as send-node}]
                             (let [base-body     (or raw-body (futil/ast->query ast))
                                   ok-handler    (fn [result]
                                                   (try
                                                     (result-handler result)
                                                     (catch Exception e
                                                       (log/error e "Result handler for remote" url "failed with an exception."))))
                                   error-handler (fn [error-result]
                                                   (try
                                                     (let [error (preprocess-error error-result)]
                                                       (log/error (ex-info "Remote Error" error))
                                                       (result-handler error))
                                                     (catch Exception e
                                                       (log/error e "Error handler for remote" url "failed with an exception."))))]
                               (if-let [real-request (try
                                                       (request-middleware {:headers {} :body base-body :url url :method :post})
                                                       (catch Exception e
                                                         (log/error e "Send aborted due to middleware failure.")
                                                         nil))]
                                 (let [abort-id   (or (-> options ::txn/abort-id) (-> options :abort-id))
                                       do-request (fn []
                                                    (try
                                                      (let [driver-response (http-driver {:url     (:url real-request)
                                                                                          :method  (or (:method real-request) :post)
                                                                                          :headers (:headers real-request)
                                                                                          :body    (:body real-request)})
                                                            get-response    (response-extractor* response-middleware base-body real-request driver-response)
                                                            ok-routine      (ok-routine* get-response ok-handler
                                                                              (error-routine* get-response nil error-handler))
                                                            error-routine   (error-routine* get-response ok-routine error-handler)
                                                            error?          (not= :none (classify-error driver-response))]
                                                        (if error?
                                                          (error-routine)
                                                          (ok-routine)))
                                                      (catch Exception e
                                                        (log/error e "HTTP driver threw an exception")
                                                        (error-handler {:error                :exception
                                                                        :error-text           (str e)
                                                                        :status-code          0
                                                                        :status-text          "Exception"
                                                                        :body                 ""
                                                                        :headers              {}
                                                                        :outgoing-request     real-request
                                                                        :original-transaction base-body}))
                                                      (finally
                                                        (when abort-id
                                                          (swap! active-requests clear-request* abort-id (Thread/currentThread))))))]
                                   (if synchronous?
                                     (do-request)
                                     (let [fut (future (do-request))]
                                       (when abort-id
                                         (swap! active-requests update abort-id (fnil conj #{}) fut)))))
                                 (error-handler {:error      :abort
                                                 :error-text "Transmission was aborted because the request middleware returned nil or threw an exception"}))))
       :abort!             (fn abort! [this id]
                             (if-let [futs (get @(:active-requests this) id)]
                               (doseq [fut futs]
                                 (future-cancel fut))
                               (log/info "Unable to abort. No active request with abort id:" id)))})))

;; =============================================================================
;; Progress helpers (simple accessors, same as CLJS)
;; =============================================================================

(defn overall-progress
  "Returns a number between 0 and 100 for the overall progress. Use in a `progress-action`
   section of your mutation when using the http-remote to monitor network progress."
  [mutation-env]
  (some-> mutation-env :progress :overall-progress))

(defn receive-progress
  "Returns a number between 0 and 100 for the receive progress. Use in a `progress-action`
   section of your mutation when using the http-remote to monitor network progress."
  [mutation-env]
  (some-> mutation-env :progress :receive-progress))

(defn send-progress
  "Returns a number between 0 and 100 for the send progress. Use in a `progress-action`
   section of your mutation when using the http-remote to monitor network progress."
  [mutation-env]
  (some-> mutation-env :progress :send-progress))
