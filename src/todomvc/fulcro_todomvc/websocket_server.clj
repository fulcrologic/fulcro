(ns fulcro-todomvc.websocket-server
  (:require
    [com.fulcrologic.fulcro.server.api-middleware :refer [not-found-handler]]
    [com.fulcrologic.fulcro.networking.websockets :as fws]
    [fulcro-todomvc.custom-types :as custom-types]
    [immutant.web :as web]
    [fulcro-todomvc.server :refer [parser]]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.middleware.resource :refer [wrap-resource]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.util.response :refer [response file-response resource-response]]
    [taoensso.sente.server-adapters.immutant :refer [get-sch-adapter]]))

(def server (atom nil))

(defn http-server []
  (custom-types/install!)
  (let [websockets (fws/start! (fws/make-websockets
                                 (fn [query] (parser {} query))
                                 {:http-server-adapter (get-sch-adapter)
                                  ;; See Sente for CSRF instructions
                                  :sente-options       {:csrf-token-fn nil}}))
        middleware (-> not-found-handler
                     (fws/wrap-api websockets)
                     wrap-keyword-params
                     wrap-params
                     (wrap-resource "public")
                     wrap-content-type
                     wrap-not-modified)
        result     (web/run middleware {:host "0.0.0.0"
                                        :port 8080})]
    (reset! server
      (fn []
        (fws/stop! websockets)
        (web/stop result)))))

(comment

  ;; start
  (http-server)

  ;; stop
  (@server))
