(ns untangled.server.impl.components.handler
  (:require
    [bidi.bidi :as bidi]
    [com.stuartsierra.component :as component]
    [ring.middleware.resource :refer [wrap-resource]]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.middleware.gzip :refer [wrap-gzip]]
    [ring.util.response :refer [response file-response resource-response]]
    [untangled.server.impl.middleware :as middleware]
    [taoensso.timbre :as timbre]
    [untangled.server.impl.util :as util])
  (:import (clojure.lang ExceptionInfo)))

(def routes
  ["" {"/" :index
       "/api"
           {:get  {[""] :api}
            :post {[""] :api}}}])

(defn index [req]
  (assoc (resource-response (str "index.html") {:root "public"})
    :headers {"Content-Type" "text/html"}))

(defn error->response [error]
  {:status 500
   :body   error})

(defn untangled-api-error-response
  "Determines if ex-data from ExceptionInfo has headers matching the Untangled Server API.
   Returns ex-map if the ex-data matches the API, otherwise returns the whole exception."
  [ex]
  ;; TODO: logging?
  (let [valid-response-keys #{:status :headers :body}
        ex-map (ex-data ex)]
    (if (every? valid-response-keys (keys ex-map))
      ex-map
      (error->response ex))))

(defn process-errors [error]
  (cond
    (instance? ExceptionInfo error) (untangled-api-error-response error)
    (instance? Exception error) (error->response error)
    :else {:status 400 :body error}))

(defn valid-response? [result]
  (and
    (not (instance? Exception result))
    (not (some (fn [[_ {:keys [om.next/error]}]] (some? error)) result))))

(defn raise-response
  "For om mutations, converts {'my/mutation {:result {...}}} to {'my/mutation {...}}"
  [resp]
  (reduce (fn [acc [k v]]
            (if (and (symbol? k) (not (nil? (:result v))))
              (assoc acc k (:result v))
              (assoc acc k v)))
    {} resp))

(defn api
  "The /api Request handler. The incoming request will have a database connection, parser, and error handler
  already injected. This function should be fairly static, in that it calls the parser, and if the parser
  does not throw and exception it wraps the return value in a transit response. If the parser throws
  an exception, then it calls the injected error handler with the request and the exception. Thus,
  you can define the handling of all API requests via system injection at startup."
  [{:keys [transit-params parser env] :as req}]
  (let [parse-result (try (raise-response (parser env transit-params))
                          (catch Exception e (timbre/error "Parser error:" (.getStackTrace e)) e))]
    (if (valid-response? parse-result)
      {:status 200 :body parse-result}
      (process-errors parse-result))))

(defn generate-response
  "Generate a response containing status code, headers, and body.
  The content type will always be 'application/transit+json',
  and this function will assert if otherwise."
  [{:keys [status body headers] :or {status 200} :as input}]
  {:pre [(not (contains? headers "Content-Type"))
         (and (>= status 100) (< status 600))]}
  (timbre/info "Generate Response: " input)
  {:status  status
   :headers (merge headers {"Content-Type" "application/transit+json"})
   :body    body})

(defn route-handler [req]
  (let [match (bidi/match-route routes (:uri req)
                :request-method (:request-method req))]
    (case (:handler match)
      ;; explicit handling of / as index.html. wrap-resources does the rest
      :index (index req)
      :api (generate-response (api req))
      req)))

(defn wrap-connection
  "Ring middleware function that invokes the general handler with the parser and parsing environgment on the request."
  [handler api-parser om-parsing-env]
  (fn [req] (handler (assoc req :parser api-parser :env (assoc om-parsing-env :request req)))))

(defn handler
  "Create a web request handler that sends all requests through an Om parser. The om-parsing-env of the parses
  will include any components that were injected into the handler.

  Returns a function that handles requests."
  [api-parser om-parsing-env]
  ;; NOTE: ALL resources served via wrap-resources (from the public subdirectory). The BIDI route maps / -> index.html
  (-> (wrap-connection route-handler api-parser om-parsing-env)
    (middleware/wrap-transit-params)
    (middleware/wrap-transit-response)
    (wrap-resource "public")
    (wrap-content-type)
    (wrap-not-modified)
    (wrap-gzip)))

(defrecord Handler [all-routes api-parser injected-keys]
  component/Lifecycle
  (start [component]
    (timbre/info "Creating web server handler.")
    (assert (every? (set (keys component)) injected-keys) (str "You asked to inject " injected-keys " but one or more of those components do not exist."))
    (let [om-parsing-env (select-keys component injected-keys)
          req-handler (handler api-parser om-parsing-env)]
      (assoc component :api-parser api-parser :all-routes req-handler :env om-parsing-env)))
  (stop [component] component
    (timbre/info "Tearing down web server handler.")
    (assoc component :all-routes nil))
  )

(defn build-handler
  "Build a web request handler.

  Parameters:
  - `api-parser`: An Om AST Parser that can interpret incoming API queries, and return the proper response. Return is the response when no exception is thrown.
  - `injections`: A vector of keywords to identify component dependencies.  Components injected here can be made available to your parser.
  "
  [api-parser injections]
  (component/using
    (map->Handler {:api-parser    api-parser
                   :injected-keys injections})
    (vec (into #{:logger :config} injections))
    ))
