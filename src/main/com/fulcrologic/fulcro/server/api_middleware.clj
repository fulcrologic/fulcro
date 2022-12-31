(ns com.fulcrologic.fulcro.server.api-middleware
  "Standard Ring middleware for setting up servers to handle Fulcro requests. These assume you will be using a library
  like Pathom to create a parser that can properly dispatch resolution of requests. See the Developer's Guide or
  the Fulcro template for examples of usage."
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.test :refer :all]
    [clojure.repl :refer [doc source]]
    [com.fulcrologic.fulcro.algorithms.transit :as transit]
    [cognitect.transit :as ct]
    [taoensso.timbre :as log])
  (:import (java.io ByteArrayOutputStream)))

(def not-found-handler
  (fn [req]
    {:status  404
     :headers {"Content-Type" "text/plain"}
     :body    "Invalid request."}))

(defn generate-response
  "Generate a Fulcro-compatible response containing at least a status code, and
  body. You should pre-populate at least the body of the input-response."
  [{:keys [status body] :or {status 200} :as input-response}]
  (assoc input-response :status status :body body))

(defn augment-response
  "Adds a lambda to the given data `core-response` such that `apply-response-augmentations`
  will use it to morph the raw Ring response in which the `core-response` is embedded
  (the core response becomes the `:body`).

  The `ring-response-fn` is a `(fn [resp] resp')` that will be passed a raw (possibly empty)
  Ring response which it can modify and return.

  Use this function when you need to add information into the handler response, for
  example when you need to add cookies or session data. Example:

      (defmutation my-mutate
        ...
        (augment-response
          {:uid 42} ; your regular response
          #(assoc-in % [:session :user-id] 42))) ; a function resp -> resp

  If the parser has multiple responses that use `augment-response` they will all be applied.
  The first one will receive an empty map as input. Only top level values
  of your response will be checked for augmented response (i.e. primarily mutation responses).

  See `apply-response-augmentations`, which is used by `handle-api-request`, which in turn is the
  primary implementation element of `wrap-api`."
  [core-response ring-response-fn]
  (assert (instance? clojure.lang.IObj core-response) "Scalar values can't be augmented.")
  (with-meta core-response {::augment-response ring-response-fn}))

(defn apply-response-augmentations
  "Process the raw response from the parser looking for lambdas that were added by
  top-level Fulcro queries and mutations via
  `augment-response`. Runs each in turn and accumulates their effects. The result is
  meant to be a Ring response (and is used as such by `handle-api-request`."
  [response]
  (->> (keep #(some-> (second %) meta ::augment-response) response)
    (reduce (fn [response f] (f response)) {})))

(defn handle-api-request
  "Given a parser and a query: Runs the parser on the query,
   and generates a standard Fulcro-compatible response, and augment the raw Ring response with
   any augment handlers that were indicated on top-level mutations/queries via
   `augment-response`.

   Query can be a normal EQL vector, or a single-request grouping of queries, which would be a map
   containing the single key `{:queries [EQL-query EQL-query ...]}`.

   In the former case the response `:body` will be a single map. In the latter case the response will be a vector of
   result maps.

   NOTE: internal server errors (uncaught exceptions) in a batched query will cause the entire batch to fail, even
   though some of the requests could have succeeded if sent alone. If you want to use request batching then your
   query processor should *never* allow exceptions to propagate, and your response maps should instead include
   the problems.
   "
  [query query-processor]
  (generate-response
    (let [parse-result (try
                         (cond
                           (vector? query) (query-processor query)
                           (and (map? query) (contains? query :queries)) (let [{:keys [queries]} query]
                                                                           (reduce
                                                                             (fn [result query]
                                                                               (conj result (query-processor query)))
                                                                             []
                                                                             queries))
                           :else (throw (ex-info "Invalid query from client" {:query query})))
                         (catch Exception e
                           (log/error e "Parser threw an exception on" query " See https://book.fulcrologic.com/#err-parser-errored-on-query")
                           e))]
      (if (instance? Throwable parse-result)
        {:status 500 :body "Internal server error. Parser threw an exception. See server logs for details."}
        (merge {:status 200 :body parse-result} (apply-response-augmentations parse-result))))))

(defn reader
  "Create a transit reader. This reader can handler the tempid type.
   Can pass transit reader customization opts map."
  ([in] (transit/reader in))
  ([in opts] (transit/reader in opts)))

(defn writer
  "Create a transit writer. This writer can handler the tempid type.
   Can pass transit writer customization opts map."
  ([out] (transit/writer out))
  ([out opts] (transit/writer out opts)))

(defn- get-content-type
  "Return the content-type of the request, or nil if no content-type is set. Defined here to limit the need for Ring."
  [request]
  (if-let [type (get-in request [:headers "content-type"])]
    (second (re-find #"^(.*?)(?:;|$)" type))))

(defn- transit-request? [request]
  (if-let [type (get-content-type request)]
    (let [mtch (re-find #"^application/transit\+(json|msgpack)" type)]
      [(not (empty? mtch)) (keyword (second mtch))])))

(defn- read-transit [request {:keys [opts]}]
  (let [[res _] (transit-request? request)]
    (if res
      (if-let [body (:body request)]
        (let [rdr (reader body opts)]
          (try
            [true (ct/read rdr)]
            (catch Exception ex
              [false nil])))))))

(def ^{:doc "The default response to return when a Transit request is malformed."}
  default-malformed-response
  {:status  400
   :headers {"Content-Type" "text/plain"}
   :body    "Malformed Transit in request body."})

(defn- assoc-transit-params [request transit]
  (let [request (assoc request :transit-params transit)]
    (if (map? transit)
      (update-in request [:params] merge transit)
      request)))

(defn wrap-transit-params
  "Middleware that parses the body of Transit requests into a map of parameters,
  which are added to the request map on the :transit-params and :params keys.
  Accepts the following options:
  :malformed-response - a response map to return when the JSON is malformed
  :opts               - a map of options to be passed to the transit reader
  Use the standard Ring middleware, ring.middleware.keyword-params, to
  convert the parameters into keywords."
  {:arglists '([handler] [handler options])}
  [handler & [{:keys [malformed-response]
               :or   {malformed-response default-malformed-response}
               :as   options}]]
  (fn [request]
    (if-let [[valid? transit] (read-transit request options)]
      (if valid?
        (handler (assoc-transit-params request transit))
        malformed-response)
      (handler request))))

(defn- set-content-type
  "Returns an updated Ring response with the a Content-Type header corresponding
  to the given content-type. This is defined here so non-ring users do not need ring."
  [resp content-type]
  (assoc-in resp [:headers "Content-Type"] (str content-type)))

(defn- write [x t opts]
  (let [baos (ByteArrayOutputStream.)
        w    (writer baos opts)
        _    (ct/write w x)
        ret  (.toString baos "UTF-8")]
    (.reset baos)
    ret))

(defn wrap-transit-response
  "Middleware that converts responses with a map or a vector for a body into a
  Transit response.
  Accepts the following options:
  :encoding - one of #{:json :json-verbose :msgpack}
  :opts     - a map of options to be passed to the transit writer"
  {:arglists '([handler] [handler options])}
  [handler & [{:as options}]]
  (let [{:keys [encoding opts] :or {encoding :json}} options]
    (assert (#{:json :json-verbose :msgpack} encoding) "The encoding must be one of #{:json :json-verbose :msgpack}.")
    (fn [request]
      (let [response (handler request)]
        (if (coll? (:body response))
          (let [transit-response (update-in response [:body] write encoding opts)]
            (if (contains? (:headers response) "Content-Type")
              transit-response
              (set-content-type transit-response (format "application/transit+%s; charset=utf-8" (name encoding)))))
          response)))))

(defn wrap-api
  "Wrap Fulcro API request processing. Required options are:

   - `:uri` - The URI on the server that handles the API requests.
   - `:parser` - A function `(fn [eql-query] eql-response)` that can process the query.

   IMPORTANT: You must install Fulcro's `wrap-transit-response` and
   `wrap-transit-params`, or other middleware that handles content negotiation,
   like https://github.com/metosin/muuntaja, to your list of middleware
   handlers after `wrap-api`."
  [handler {:keys [uri parser]}]
  (when-not (and (string? uri) (fn? parser))
    (throw (ex-info "Invalid parameters to `wrap-api`. :uri and :parser are required. See docstring." {})))
  (fn [request]
    ;; eliminates overhead of wrap-transit
    (if (= uri (:uri request))
      ;; Fulcro's middleware, like ring-transit, places the parsed request in
      ;; the request map on `:transit-params`, other ring middleware, such as
      ;; metosin/muuntaja, places the parsed request on `:body-params`.
      (handle-api-request (or (:transit-params request) (:body-params request)) parser)
      (handler request))))
