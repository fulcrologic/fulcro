(ns solutions.tiny-server
  (:require
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.gzip :refer [wrap-gzip]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.middleware.resource :refer [wrap-resource]]
    [ring.util.response :as rsp :refer [response file-response resource-response]]
    [org.httpkit.server]
    [fulcro.server :as server]))

(defn not-found-handler []
  (fn [req]
    {:status  404
     :headers {"Content-Type" "text/plain"}
     :body    "NOPE"}))

(def parser (server/fulcro-parser))

(defn wrap-api [handler uri]
  (fn [request]
    (if (= uri (:uri request))
      (server/handle-api-request parser {} (:transit-params request))
      (handler request))))

(defn my-tiny-server []
  (let [port       9005
        ring-stack (-> (not-found-handler)
                     (wrap-api "/api")
                     (server/wrap-transit-params)
                     (server/wrap-transit-response)
                     (wrap-resource "public")
                     (wrap-content-type)
                     (wrap-not-modified)
                     (wrap-gzip))]
    (org.httpkit.server/run-server ring-stack {:port port})))

(server/defmutation tiny-ping [params]
  (action [env]
    (println "PING")))

(comment
  (tools-ns/refresh)

  (my-tiny-server)
  )
