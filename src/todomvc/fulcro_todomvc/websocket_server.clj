(ns fulcro-todomvc.websocket-server
  (:require
    [com.fulcrologic.fulcro.networking.websockets :as fws]
    [com.fulcrologic.fulcro.server.api-middleware :refer [not-found-handler]]
    [fulcro-todomvc.custom-types :as custom-types]
    [fulcro-todomvc.server :refer [parser]]
    [immutant.web :as web]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.resource :refer [wrap-resource]]
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
