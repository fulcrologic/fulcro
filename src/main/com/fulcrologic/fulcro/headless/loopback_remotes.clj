(ns com.fulcrologic.fulcro.headless.loopback-remotes
  "Loopback remote implementations for headless Fulcro applications.

   Provides synchronous remote implementations that execute locally (loopback)
   instead of making network requests:

   - `sync-remote` - Calls a handler function directly
   - `pathom-remote` - Integrates with Pathom parsers
   - `ring-remote` - Simple Ring handler integration
   - `fulcro-ring-remote` - Full Ring middleware integration with Fulcro's transit encoding
   - `mock-remote` - Returns canned responses for testing"
  (:require
    [clojure.core.async :as async]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.transit :as transit]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log])
  (:import
    (clojure.core.async.impl.protocols ReadPort)
    (java.io ByteArrayInputStream)))

(defn- extract-eql
  "Extract EQL query from an AST."
  [ast]
  (eql/ast->query ast))

(defn sync-remote
  "Create a synchronous remote that calls handler-fn directly.

   handler-fn: (fn [eql-request] response-body)
               OR returns a core.async channel that will contain the response

   The handler receives the EQL query/mutation as data and should return
   the response body (the data to merge into app state).

   Options:
   - :simulate-latency-ms - Add artificial delay (for testing loading states)
   - :transform-request - (fn [eql] transformed-eql) before calling handler
   - :transform-response - (fn [response] transformed-response) after handler
   - :on-error - (fn [error eql] {:status-code :body}) custom error handling

   Example:
   ```clojure
   (sync-remote
     (fn [eql]
       {:user/id 1 :user/name \"John\"}))
   ```"
  [handler-fn & {:keys [simulate-latency-ms transform-request transform-response on-error]}]
  {:transmit!
   (fn transmit! [_this {:keys [:com.fulcrologic.fulcro.algorithms.tx-processing/ast
                                :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler
                                :com.fulcrologic.fulcro.algorithms.tx-processing/update-handler]
                         :as   send-node}]
     (try
       (let [eql        (extract-eql ast)
             eql        (if transform-request (transform-request eql) eql)
             _          (when simulate-latency-ms
                          (Thread/sleep (long simulate-latency-ms)))
             raw-result (handler-fn eql)
             ;; Handle async channel results - ReadPort is the protocol that channels implement
             result     (if (instance? ReadPort raw-result)
                          (async/<!! raw-result)
                          raw-result)
             result     (if transform-response (transform-response result) result)]
         (result-handler {:status-code 200
                          :body        result}))
       (catch Exception e
         (log/error e "Remote handler threw exception for" (extract-eql ast))
         (if on-error
           (result-handler (on-error e (extract-eql ast)))
           (result-handler {:status-code 500
                            :body        {}
                            :error       (.getMessage e)})))))

   :abort!
   (fn abort! [_this _abort-id]
     ;; Sync remotes complete immediately, nothing to abort
     nil)})

(defn pathom-remote
  "Create a remote backed by a Pathom parser.

   parser: A Pathom parser function with signature:
           (fn [env eql] result)
           OR (fn [eql] result) if env-fn is not provided

   Options:
   - :env-fn - (fn [] env) creates parser environment per request.
               Called fresh for each request to support request-scoped data.
   - :async? - If true, parser returns a core.async channel; blocks via <!!

   Example with Pathom 3:
   ```clojure
   (pathom-remote
     (p/parser {...})
     :env-fn (fn [] {:db (get-db-connection)}))
   ```

   Example with Pathom 2:
   ```clojure
   (pathom-remote
     my-pathom2-parser
     :env-fn (fn [] {:request {:session {...}}}))
   ```"
  [parser & {:keys [env-fn async?]}]
  (sync-remote
    (fn [eql]
      (let [env    (when env-fn (env-fn))
            result (if env
                     (parser env eql)
                     (parser eql))]
        (if async?
          (async/<!! result)
          result)))))

(defn ring-remote
  "Create a remote that invokes a Ring handler.

   Simulates a full HTTP round-trip through the Ring middleware stack,
   which is useful for testing authentication, authorization, and other
   middleware behavior.

   ring-handler: A Ring handler function (fn [request] response)

   Options:
   - :uri - API endpoint URI (default \"/api\")
   - :method - HTTP method (default :post)
   - :content-type - Request content type (default \"application/transit+json\")
   - :headers - Additional headers to include
   - :session - Session data to include in request
   - :encode-fn - (fn [eql] encoded-body) - defaults to pr-str
   - :decode-fn - (fn [response-body] data) - defaults to read-string

   Example:
   ```clojure
   (ring-remote my-ring-app
     :uri \"/api/graphql\"
     :session {:user/id 1}
     :headers {\"Authorization\" \"Bearer token\"})
   ```"
  [ring-handler & {:keys [uri method content-type headers session encode-fn decode-fn]
                   :or   {uri          "/api"
                          method       :post
                          content-type "application/edn"
                          encode-fn    pr-str
                          decode-fn    read-string}}]
  (sync-remote
    (fn [eql]
      (let [request  {:uri            uri
                      :request-method method
                      :headers        (merge {"content-type" content-type
                                              "accept"       content-type}
                                        headers)
                      :body           (java.io.ByteArrayInputStream.
                                        (.getBytes (encode-fn eql) "UTF-8"))
                      :session        session}
            response (ring-handler request)]
        (if (= 200 (:status response))
          (if-let [body (:body response)]
            (cond
              (string? body) (decode-fn body)
              (instance? java.io.InputStream body)
              (decode-fn (slurp body))
              :else body)
            {})
          (throw (ex-info "Ring handler returned non-200 status"
                   {:status   (:status response)
                    :response response})))))
    :on-error (fn [e eql]
                (let [data (ex-data e)]
                  {:status-code (or (:status data) 500)
                   :body        {}
                   :error       (.getMessage e)}))))

;; =============================================================================
;; Fulcro Ring Remote - Full middleware integration
;; =============================================================================

(defn- parse-cookie-header
  "Parse a Set-Cookie header value into a map with :value and attributes.
   Example: 'session=abc123; Path=/; HttpOnly' -> {:value \"abc123\" :path \"/\" :http-only true}"
  [header-value]
  (when header-value
    (let [parts (str/split header-value #";\s*")
          [name-value & attrs] parts
          [name value] (str/split name-value #"=" 2)]
      (reduce
        (fn [cookie attr]
          (let [[k v] (str/split attr #"=" 2)
                k-lower (str/lower-case k)]
            (case k-lower
              "path" (assoc cookie :path v)
              "domain" (assoc cookie :domain v)
              "max-age" (assoc cookie :max-age (Long/parseLong v))
              "expires" (assoc cookie :expires v)
              "secure" (assoc cookie :secure true)
              "httponly" (assoc cookie :http-only true)
              "samesite" (assoc cookie :same-site (keyword (str/lower-case v)))
              cookie)))
        {:value value}
        attrs))))

(defn- parse-set-cookie-headers
  "Parse Set-Cookie headers from a Ring response into a cookies map.
   Handles both single header and multiple headers (as vector)."
  [headers]
  (let [set-cookie (or (get headers "Set-Cookie")
                     (get headers "set-cookie"))]
    (cond
      (nil? set-cookie) {}
      (string? set-cookie)
      (let [[name value] (str/split (first (str/split set-cookie #";" 2)) #"=" 2)]
        {name (parse-cookie-header set-cookie)})
      (sequential? set-cookie)
      (reduce
        (fn [cookies header]
          (let [[name value] (str/split (first (str/split header #";" 2)) #"=" 2)]
            (assoc cookies name (parse-cookie-header header))))
        {}
        set-cookie)
      :else {})))

(defn- cookies->header-string
  "Convert a cookies map to a Cookie header string.
   Cookies map format: {\"name\" {:value \"value\"}} or {\"name\" \"value\"}."
  [cookies]
  (when (seq cookies)
    (->> cookies
      (map (fn [[name cookie]]
             (str name "=" (if (map? cookie) (:value cookie) cookie))))
      (str/join "; "))))

(defn wrap-fulcro-request
  "CLJ port of the CLJS wrap-fulcro-request middleware.
   Encodes the request body as transit+json and sets appropriate headers.

   Options:
   - :transit-handlers - Additional transit write handlers (map of type to handler)"
  ([] (wrap-fulcro-request identity))
  ([handler] (wrap-fulcro-request handler nil))
  ([handler transit-handlers]
   (fn [{:keys [body] :as request}]
     (let [encoded-body (transit/transit-clj->str body (when transit-handlers
                                                         {:handlers transit-handlers}))]
       (handler (-> request
                  (assoc :body encoded-body)
                  (update :headers merge {"Content-Type" "application/transit+json"
                                          "Accept"       "application/transit+json"})))))))

(defn wrap-fulcro-response
  "CLJ port of the CLJS wrap-fulcro-response middleware.
   Decodes transit+json response body back to EDN.

   Options:
   - :transit-handlers - Additional transit read handlers (map of tag to handler)"
  ([] (wrap-fulcro-response identity))
  ([handler] (wrap-fulcro-response handler nil))
  ([handler transit-handlers]
   (fn [{:keys [body status-code] :as response}]
     (handler
       (if (and (= 200 status-code) (some? body) (not (empty? body)))
         (try
           (let [decoded (transit/transit-str->clj
                           (if (string? body) body (slurp body))
                           (when transit-handlers {:handlers transit-handlers}))]
             (assoc response :body decoded))
           (catch Exception e
             (log/error e "Failed to decode transit response")
             (assoc response :body {} :decode-error (.getMessage e))))
         (assoc response :body {}))))))

(defn- simulate-ring-request
  "Simulate an HTTP request through a Ring handler.

   Handles cookies in two ways:
   1. Direct :cookies map in request (Ring's standard format)
   2. Cookie header string (for middleware that reads headers)

   Returns parsed Set-Cookie headers merged with existing cookies."
  [{:keys [ring-handler uri method headers body cookies session]}]
  (let [body-bytes       (.getBytes ^String body "UTF-8")
        cookie-header    (cookies->header-string cookies)
        request          {:uri            uri
                          :request-method method
                          :headers        (cond-> (merge {"content-type"   "application/transit+json"
                                                          "accept"         "application/transit+json"
                                                          "content-length" (str (count body-bytes))}
                                                    headers)
                                            ;; Add Cookie header if we have cookies
                                            cookie-header (assoc "cookie" cookie-header))
                          :body           (ByteArrayInputStream. body-bytes)
                          ;; Ring's wrap-cookies reads from :cookies key
                          :cookies        (or cookies {})
                          ;; Ring's wrap-session reads from :session key
                          :session        (or session {})}
        response         (ring-handler request)
        ;; Parse Set-Cookie headers from response
        response-cookies (parse-set-cookie-headers (:headers response))]
    {:status-code (:status response)
     :headers     (:headers response)
     :body        (let [resp-body (:body response)]
                    (cond
                      (nil? resp-body) ""
                      (string? resp-body) resp-body
                      (instance? java.io.InputStream resp-body) (slurp resp-body)
                      :else (str resp-body)))
     ;; Merge cookies: response :cookies key + parsed Set-Cookie headers
     :cookies     (merge (:cookies response) response-cookies)
     :session     (:session response)}))

(defn fulcro-ring-remote
  "Create a remote that simulates a full Fulcro HTTP remote through a Ring handler.

   This provides the same middleware pipeline as the CLJS `fulcro-http-remote`,
   but calls a Ring handler directly instead of making HTTP requests.

   ring-handler: A Ring handler function (fn [request] response)

   Options:
   - :uri - API endpoint URI (default \"/api\")
   - :request-middleware - Client request middleware chain (fn [handler] wrapped-handler)
                          Defaults to `wrap-fulcro-request`. Build chains like:
                          (-> (wrap-fulcro-request)
                              (wrap-csrf-token token)
                              (wrap-custom-headers))
   - :response-middleware - Client response middleware chain (fn [handler] wrapped-handler)
                           Defaults to `wrap-fulcro-response`. Build chains like:
                           (-> (wrap-fulcro-response)
                               (wrap-error-handling)
                               (wrap-logging))
   - :cookies - Initial cookies map to include in requests
   - :session - Session data to include in requests
   - :transit-write-handlers - Additional transit handlers for encoding
   - :transit-read-handlers - Additional transit handlers for decoding

   Example with middleware:
   ```clojure
   (defn wrap-auth-header [handler token]
     (fn [request]
       (handler (update request :headers assoc \"Authorization\" (str \"Bearer \" token)))))

   (fulcro-ring-remote my-ring-app
     :uri \"/api\"
     :request-middleware (-> (wrap-fulcro-request)
                             (wrap-auth-header \"my-token\"))
     :response-middleware (-> (wrap-fulcro-response)
                              (wrap-error-logging)))
   ```

   Example simulating cookies:
   ```clojure
   (fulcro-ring-remote my-ring-app
     :cookies {\"session\" {:value \"abc123\"}}
     :session {:user/id 1})
   ```"
  [ring-handler & {:keys [uri request-middleware response-middleware
                          cookies session transit-write-handlers transit-read-handlers]
                   :or   {uri "/api"}}]
  (let [;; Default middleware if not provided
        req-middleware  (or request-middleware
                          (wrap-fulcro-request identity transit-write-handlers))
        resp-middleware (or response-middleware
                          (wrap-fulcro-response identity transit-read-handlers))
        ;; State for simulating stateful interactions (cookies, etc.)
        state-atom      (atom {:cookies (or cookies {})
                               :session (or session {})})]
    {:transmit!
     (fn transmit! [_this {:com.fulcrologic.fulcro.algorithms.tx-processing/keys [ast result-handler]
                           :as                                                   send-node}]
       (try
         (let [eql                (extract-eql ast)
               ;; Build the request through middleware
               base-request       {:body    eql
                                   :headers {}
                                   :url     uri
                                   :method  :post}
               processed-request  (req-middleware base-request)
               ;; Execute through Ring
               {:keys [cookies session] :as current-state} @state-atom
               raw-response       (simulate-ring-request
                                    {:ring-handler ring-handler
                                     :uri          (or (:url processed-request) uri)
                                     :method       (or (:method processed-request) :post)
                                     :headers      (:headers processed-request)
                                     :body         (:body processed-request)
                                     :cookies      cookies
                                     :session      session})
               ;; Update stateful data from response
               _                  (swap! state-atom merge
                                    (when (:cookies raw-response) {:cookies (merge cookies (:cookies raw-response))})
                                    (when (:session raw-response) {:session (:session raw-response)}))
               ;; Process response through middleware
               processed-response (resp-middleware raw-response)]
           (result-handler processed-response))
         (catch Exception e
           (log/error e "fulcro-ring-remote error for" (extract-eql ast))
           (result-handler {:status-code 500
                            :body        {}
                            :error       (.getMessage e)}))))

     :abort!
     (fn abort! [_ _] nil)

     ;; Expose state for test inspection
     :state state-atom}))

(defn mock-remote
  "Create a remote that returns canned responses.

   responses: A map from EQL patterns to response bodies, or a function.

   When responses is a map, keys can be:
   - A complete EQL query/mutation (exact match)
   - A mutation symbol (matches any mutation with that symbol)
   - :default - Fallback for unmatched requests

   When responses is a function:
   - (fn [eql] response-body-or-nil)
   - Return nil to use default behavior

   Options:
   - :default-response - Response for unmatched requests (default: {})
   - :simulate-latency-ms - Add artificial delay
   - :on-unmatched - (fn [eql] response) called for unmatched when no default

   Example:
   ```clojure
   (mock-remote
     {`my-mutation {:result :success}
      [{:users [:user/id :user/name]}] [{:user/id 1 :user/name \"John\"}]
      :default {}})
   ```"
  [responses & {:keys [default-response simulate-latency-ms on-unmatched]
                :or   {default-response {}}}]
  (let [match-fn (if (fn? responses)
                   responses
                   (fn [eql]
                     (or
                       ;; Exact match
                       (get responses eql)
                       ;; Match by mutation symbol
                       (when-let [mutation-sym (and (vector? eql)
                                                 (= 1 (count eql))
                                                 (list? (first eql))
                                                 (first (first eql)))]
                         (get responses mutation-sym))
                       ;; Match by first element symbol (for queries with params)
                       (when-let [first-sym (and (vector? eql)
                                              (list? (first eql))
                                              (symbol? (ffirst eql))
                                              (ffirst eql))]
                         (get responses first-sym))
                       ;; Default
                       (get responses :default))))]
    (sync-remote
      (fn [eql]
        (or (match-fn eql)
          (when on-unmatched (on-unmatched eql))
          default-response))
      :simulate-latency-ms simulate-latency-ms)))

(defn recording-remote
  "Create a remote that records all requests and delegates to another remote.

   Useful for verifying what requests were sent during a test.

   Options:
   - :delegate - Remote to delegate actual handling to (required)
   - :record-atom - Atom to store recordings (default: creates new atom)

   Returns: {:remote the-remote :recordings atom-with-recordings}

   Each recording is a map with:
   - :eql - The EQL query/mutation
   - :timestamp - When the request was made
   - :response - The response returned

   Example:
   ```clojure
   (let [{:keys [remote recordings]} (recording-remote
                                       :delegate (sync-remote handler))]
     (set-remote! app :remote remote)
     (comp/transact! app [(my-mutation)])
     (is (= 1 (count @recordings)))
     (is (= 'my-mutation (ffirst (:eql (first @recordings))))))
   ```"
  [& {:keys [delegate record-atom]
      :or   {record-atom (atom [])}}]
  (when-not delegate
    (throw (IllegalArgumentException. "recording-remote requires :delegate option")))
  {:remote
   {:transmit!
    (fn transmit! [this {:keys [:com.fulcrologic.fulcro.algorithms.tx-processing/ast
                                :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler]
                         :as   send-node}]
      (let [eql             (extract-eql ast)
            timestamp       (System/currentTimeMillis)
            ;; Wrap result handler to capture response
            wrapped-handler (fn [result]
                              (swap! record-atom conj
                                {:eql       eql
                                 :timestamp timestamp
                                 :response  result})
                              (result-handler result))]
        ((:transmit! delegate) delegate
         (assoc send-node
           :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler
           wrapped-handler))))

    :abort!
    (fn abort! [this abort-id]
      (when-let [abort-fn (:abort! delegate)]
        (abort-fn delegate abort-id)))}

   :recordings record-atom})

(defn failing-remote
  "Create a remote that always fails with the given status code.

   Useful for testing error handling.

   Options:
   - :status-code - HTTP status code to return (default: 500)
   - :error-message - Error message to include
   - :body - Response body (default: {})
   - :fail-after - Number of successful requests before failing (default: 0)
   - :delegate - Remote to use for successful requests

   Example:
   ```clojure
   ;; Always fail
   (failing-remote :status-code 500 :error-message \"Server error\")

   ;; Fail after 2 successful requests
   (failing-remote
     :fail-after 2
     :delegate (sync-remote handler))
   ```"
  [& {:keys [status-code error-message body fail-after delegate]
      :or   {status-code 500
             body        {}
             fail-after  0}}]
  (let [request-count (atom 0)]
    {:transmit!
     (fn transmit! [this {:keys [:com.fulcrologic.fulcro.algorithms.tx-processing/result-handler]
                          :as   send-node}]
       (let [count (swap! request-count inc)]
         (if (and delegate (<= count fail-after))
           ;; Delegate to successful remote
           ((:transmit! delegate) delegate send-node)
           ;; Return failure
           (result-handler {:status-code status-code
                            :body        body
                            :error       error-message}))))

     :abort!
     (fn abort! [_ _] nil)}))

(defn delayed-remote
  "Create a remote that simulates a delayed response.

   Useful for testing loading states and timeouts.

   Options:
   - :delegate - Remote to delegate to (required)

   Example:
   ```clojure
   (def remote (delayed-remote  (sync-remote handler))
   ;; do network ops with fulcro
   (loopback-remotes/deliver-results! remote)
   ```"
  [delegate]
  (let [results (volatile! [])]
    {:transmit!
     (fn transmit! [this {:com.fulcrologic.fulcro.algorithms.tx-processing/keys [result-handler]
                          :as                                                   send-node}]
       (let [result-collector (fn [v] (vswap! results conj [result-handler v]))]
         ((:transmit! delegate) delegate (assoc send-node :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler result-collector))))

     :deliver! (fn []
                 (doseq [[result-handler v] @results]
                   (result-handler v))
                 (vreset! results []))

     :abort!
     (fn abort! [this abort-id]
       (vreset! results []))}))

(defn deliver-results!
  "Delivers all of the delayed results on a delayed remote synchronously and immediately. In order of original submission."
  [delayed-remote] ((:deliver! delayed-remote)))
