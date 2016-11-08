(ns untangled.server.impl.components.handler
  (:require
    [clojure.set :as set]
    [clojure.java.io :as io]
    [bidi.bidi :as bidi]
    [com.stuartsierra.component :as component]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.middleware.resource :refer [wrap-resource]]
    [ring.middleware.gzip :refer [wrap-gzip]]
    [ring.util.response :refer [resource-response]]
    [ring.util.response :as rsp :refer [response file-response resource-response]]
    [untangled.server.impl.middleware :as middleware]
    [taoensso.timbre :as timbre]
    [clojure.data.json :as json])
  (:import (clojure.lang ExceptionInfo)))

(defn index [req]
  (assoc (resource-response (str "index.html") {:root "public"})
    :headers {"Content-Type" "text/html"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; API Helper Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn serialize-exception
  "Convert exception data to string form for network transit."
  [ex]
  {:pre [(instance? Exception ex)]}
  (let [message (.getMessage ex)
        type (str (type ex))
        serialized-data {:type type :message message}]
    (if (instance? ExceptionInfo ex)
      (assoc serialized-data :data (ex-data ex))
      serialized-data)))

(defn unknow-error->response [error]
  (let [serialized-data (serialize-exception error)]
    {:status 500
     :body   serialized-data}))

(defn parser-read-error->response
  "Determines if ex-data from ExceptionInfo has headers matching the Untangled Server API.
   Returns ex-map if the ex-data matches the API, otherwise returns the whole exception."
  [ex]
  (let [valid-response-keys #{:status :headers :body}
        ex-map (ex-data ex)]
    (if (every? valid-response-keys (keys ex-map))
      ex-map
      (unknow-error->response ex))))

(defn parser-mutate-error->response
  [mutation-result]
  (let [raise-error-data (fn [item]
                           (if (and (map? item) (contains? item :om.next/error))
                             (let [exception-data (serialize-exception (get-in item [:om.next/error]))]
                               (assoc item :om.next/error exception-data))
                             item))
        mutation-errors (clojure.walk/prewalk raise-error-data mutation-result)]

    {:status 400 :body mutation-errors}))

(defn process-errors [error]
  (let [error-response (cond
                         (instance? ExceptionInfo error) (parser-read-error->response error)
                         (instance? Exception error) (unknow-error->response error)
                         :else (parser-mutate-error->response error))]
    (timbre/error error "Parser error:\n" (with-out-str (clojure.pprint/pprint error-response)))
    error-response))

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

(defn collect-response [data]
  (->> (keep #(some-> (second %) meta :untangled.server.core/augment-response) data)
       (reduce (fn [response f] (f response)) {})))

(defn api
  "The /api Request handler. The incoming request will have a database connection, parser, and error handler
  already injected. This function should be fairly static, in that it calls the parser, and if the parser
  does not throw and exception it wraps the return value in a transit response. If the parser throws
  an exception, then it calls the injected error handler with the request and the exception. Thus,
  you can define the handling of all API requests via system injection at startup."
  [{:keys [transit-params parser env] :as req}]
  (let [parse-result (try (raise-response (parser env transit-params)) (catch Exception e e))]
    (if (valid-response? parse-result)
      (merge {:status 200 :body parse-result} (collect-response parse-result))
      (process-errors parse-result))))

(defn generate-response
  "Generate a response containing status code, headers, and body.
  The content type will always be 'application/transit+json',
  and this function will assert if otherwise."
  [{:keys [status body headers] :or {status 200} :as input}]
  {:pre [(not (contains? headers "Content-Type"))
         (and (>= status 100) (< status 600))]}
  {:status  status
   :headers (merge headers {"Content-Type" "application/transit+json"})
   :body    body})

(def default-api-key "/api")

(defn app-namify-api [default-routes app-name]
  (if app-name
    (update default-routes 1 (fn [m]
                               (let [api-val (get m default-api-key)]
                                 (-> m
                                     (dissoc default-api-key)
                                     (assoc (str "/" app-name default-api-key) api-val)))))
    default-routes))

(def default-routes
  ["" {"/"             :index
       default-api-key {:get  :api
                        :post :api}}])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handler Code
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn route-handler [req]
  (let [routes (app-namify-api default-routes (:app-name req))
        match (bidi/match-route routes (:uri req)
                                :request-method (:request-method req))]
    (case (:handler match)
      ;; explicit handling of / as index.html. wrap-resources does the rest
      :index (index req)
      :api (generate-response (api req))
      nil)))

(defn wrap-connection
  "Ring middleware function that invokes the general handler with the parser and parsing environgment on the request."
  [handler route-handler api-parser om-parsing-env app-name]
  (fn [req]
    (if-let [res (route-handler (assoc req
                                  :parser api-parser
                                  :env (assoc om-parsing-env :request req)
                                  :app-name app-name))]
      res
      (handler req))))

(defn wrap-extra-routes [dflt-handler {:keys [routes handlers] :or {routes ["" {}] handlers {}}} om-parsing-env]
  (fn [{:keys [uri] :as req}]
    (let [match (bidi/match-route routes (:uri req) :request-method (:request-method req))]
      (if-let [bidi-handler (get handlers (:handler match))]
        (bidi-handler (assoc om-parsing-env :request req) match)
        (dflt-handler req)))))

(defn not-found-handler []
  (fn [req]
    {:status  404
     :headers {"Content-Type" "text/html"}
     :body    (io/file (io/resource "public/not-found.html"))}))

(defn handler
  "Create a web request handler that sends all requests through an Om parser. The om-parsing-env of the parses
  will include any components that were injected into the handler.

  Returns a function that handles requests."
  [api-parser om-parsing-env extra-routes app-name pre-hook fallback-hook]
  ;; NOTE: ALL resources served via wrap-resources (from the public subdirectory). The BIDI route maps / -> index.html
  (-> (not-found-handler)
      (fallback-hook)
      (wrap-connection route-handler api-parser om-parsing-env app-name)
      (middleware/wrap-transit-params)
      (middleware/wrap-transit-response)
      (wrap-resource "public")
      (wrap-extra-routes extra-routes om-parsing-env)
      (pre-hook)
      ;;TODO: wrap-decode-url
      (wrap-content-type)
      (wrap-not-modified)
      (wrap-gzip)))

(defprotocol IHandler
  (set-pre-hook! [this pre-hook] "sets the handler before any important handlers are run")
  (get-pre-hook [this] "gets the current pre-hook handler")
  (set-fallback-hook! [this fallback-hook] "sets the fallback handler in case nothing else returned")
  (get-fallback-hook [this] "gets the current fallback-hook handler"))

(defrecord Handler [stack api-parser injected-keys extra-routes app-name pre-hook fallback-hook]
  component/Lifecycle
  (start [component]
    (assert (every? (set (keys component)) injected-keys)
      (str "You asked to inject " injected-keys
        " but " (set/difference injected-keys (set (keys component)))
        " do not exist."))
    (timbre/info "Creating web server handler.")
    (let [om-parsing-env (select-keys component injected-keys)
          req-handler (handler api-parser om-parsing-env extra-routes app-name
                               @pre-hook @fallback-hook)]
      (reset! stack req-handler)
      (assoc component :env om-parsing-env
                       :all-routes (fn [req] (@stack req)))))
  (stop [component]
    (timbre/info "Tearing down web server handler.")
    (assoc component :all-routes nil :stack nil :pre-hook nil :fallback-hook nil))

  IHandler
  (set-pre-hook! [this new-pre-hook]
    (reset! pre-hook new-pre-hook)
    (reset! stack
            (handler api-parser (select-keys this injected-keys)
                     extra-routes app-name @pre-hook @fallback-hook))
    this)
  (get-pre-hook [this] @pre-hook)
  (set-fallback-hook! [this new-fallback-hook]
    (reset! fallback-hook new-fallback-hook)
    (reset! stack
            (handler api-parser (select-keys this injected-keys)
                     extra-routes app-name @pre-hook @fallback-hook))
    this)
  (get-fallback-hook [this] @fallback-hook))

(defn build-handler
  "Build a web request handler.

  Parameters:
  - `api-parser`: An Om AST Parser that can interpret incoming API queries, and return the proper response. Return is the response when no exception is thrown.
  - `injections`: A vector of keywords to identify component dependencies.  Components injected here can be made available to your parser.
  - `extra-routes`: See `make-untangled-server`
  - `app-name`: See `make-untangled-server`
  "
  [api-parser injections & {:keys [extra-routes app-name]}]
  (component/using
    (map->Handler {:api-parser    api-parser
                   :injected-keys injections
                   :stack         (atom nil)
                   :pre-hook      (atom identity)
                   :fallback-hook (atom identity)
                   :extra-routes  (or extra-routes {})
                   :app-name      app-name})
    (vec (into #{:config} injections))))
