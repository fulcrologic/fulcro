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
    [untangled.server.impl.components.database :as db]))

(def routes
  ["" {"/" :index
       "/api"
           {:get  {[""] :api}
            :post {[""] :api}}}])

(defn index [req]
  (assoc (resource-response (str "index.html") {:root "public"})
    :headers {"Content-Type" "text/html"}))

(defn generate-response [data & [status]]
  {:status  (or status 200)
   :headers {"Content-Type" "application/transit+json"}
   :body    data})

;; FIXME: Move to untangled datomic helpers
(defn raise-response
  "For om mutations, converts {'my/mutation {:result {...}}} to {'my/mutation {...}}"
  [resp]
  (reduce (fn [acc [k v]]
            (if (and (symbol? k) (not (nil? (:result v))))
              (assoc acc k (:result v))
              (assoc acc k v)))
    {} resp))

(defn api-handler [parser datomic-connection authorizer query config]
  (raise-response (parser {:connection datomic-connection :authorizer authorizer :config config}
                    query)))

(defn api
  "The /api Request handler. The incoming request will have a database connection, parser, and error handler
  already injected. This function should be fairly static, in that it calls the parser, and if the parser
  does not throw and exception it wraps the return value in a transit response. If the parser throws
  an exception, then it calls the injected error handler with the request and the exception. Thus,
  you can define the handling of all API requests via system injection at startup."
  [{:keys [transit-params datomic-connection parser authorizer config] :as req}]
  (try
    (generate-response {:query-response (api-handler parser datomic-connection authorizer transit-params config)})
    (catch Throwable e
      (timbre/error "API error." e)
      (generate-response {:error (ex-data e)}))))

(defn route-handler [req]
  (let [match (bidi/match-route routes (:uri req)
                :request-method (:request-method req))]
    (case (:handler match)
      ;; explicit handling of / as index.html. wrap-resources does the rest
      :index (index req)
      :api (api req)
      req)))

(defn wrap-connection [handler conn api-parser authorizer config]
  (fn [req] (handler (assoc req :parser api-parser :datomic-connection conn :authorizer authorizer :config config))))

(defn handler [api-parser conn authorizer config]
  ;; NOTE: ALL resources served via wrap-resources (from the public subdirectory). The BIDI route maps / -> index.html
  (-> (wrap-connection route-handler conn api-parser authorizer config)
    (middleware/wrap-transit-params)
    (middleware/wrap-transit-response)
    (wrap-resource "public")
    (wrap-content-type)
    (wrap-not-modified)
    (wrap-gzip)))

#_(defrecord Handler [all-routes survey-database api-parser config]
  component/Lifecycle
  (start [component]
    (timbre/info "Creating web server handler.")
    (let [conn (db/get-connection survey-database)
          authorizer (:authorizer component)
          req-handler (handler api-parser conn authorizer (:value config))]
      (assoc component :api-parser api-parser :all-routes req-handler)))
  (stop [component] component
    (timbre/info "Tearing down web server handler.")
    (assoc component :all-routes nil))
  )


