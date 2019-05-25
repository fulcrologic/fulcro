(ns com.fulcrologic.fulcro.networking.server-middleware
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.test :refer :all]
    [clojure.repl :refer [doc source]]
    [clojure.tools.namespace.repl :as tools-ns :refer [disable-reload! refresh clear set-refresh-dirs]]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.middleware.resource :refer [wrap-resource]]
    [ring.util.response :refer [response file-response resource-response]]
    [com.fulcrologic.fulcro.algorithms.transit :as transit]
    [immutant.web :as web]
    [cognitect.transit :as ct]
    [com.wsscode.pathom.connect :as pc]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.misc :as util]
    [com.wsscode.pathom.core :as p]
    [clojure.core.async :as async])
  (:import (java.io ByteArrayOutputStream)
           (clojure.lang ExceptionInfo)))

(def not-found-handler
  (fn [req]
    {:status  404
     :headers {"Content-Type" "text/plain"}
     :body    "Invalid request."}))

(defn generate-response
  "Generate a Fulcro-compatible response containing at least a status code, headers, and body. You should
  pre-populate at least the body of the input-response.
  The content type of the returned response will always be pegged to 'application/transit+json'."
  [{:keys [status body headers] :or {status 200} :as input-response}]
  (-> (assoc input-response :status status :body body)
    (update :headers assoc "Content-Type" "application/transit+json")))

(defn augment-map
  "Fulcro queries and mutations can wrap their responses with `augment-response` to indicate they need access to
   the raw Ring response. This function processes those into the response.

  IMPORTANT: This function expects that the parser results have already been raised via the raise-response function."
  [response]
  (->> (keep #(some-> (second %) meta :fulcro.server/augment-response) response)
    (reduce (fn [response f] (f response)) {})))

(defn handle-api-request
  "Given a parser, a parser environment, and a query: Runs the parser on the query,
   and generates a standard Fulcro-compatible response."
  [query query-processor]
  (generate-response
    (let [parse-result (try
                         (query-processor query)
                         (catch Exception e
                           (log/error e "Parser threw an exception on" query)
                           e))]
      (if (instance? Throwable parse-result)
        {:status 500 :body "Internal server error. Parser threw an exception. See server logs for details."}
        (merge {:status 200 :body parse-result} (augment-map parse-result))))))

(defn reader
  "Create a transit reader. This reader can handler the tempid type.
   Can pass transit reader customization opts map."
  ([in] (transit/reader in))
  ([in opts] (transit/reader in opts)))

(defn writer
  "Create a transit reader. This writer can handler the tempid type.
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
        ret  (.toString baos)]
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

   `:uri` - The URI on the server that handles the API requests.
   `:parser` - A function `(fn [eql-query] eql-response)` that can process the query."
  [handler {:keys [uri parser]}]
  (when-not (and (string? uri) (fn? parser))
    (throw (ex-info "Invalid parameters to `wrap-api`. :uri and :parser are required. See docstring." {})))
  (let [real-handler (->
                       (fn [request]
                         (handle-api-request (:transit-params request) parser))
                       (wrap-transit-params)
                       (wrap-transit-response))]
    (fn [request]
      ;; eliminates overhead of wrap-transit
      (if (= uri (:uri request))
        (real-handler request)
        (handler request)))))
