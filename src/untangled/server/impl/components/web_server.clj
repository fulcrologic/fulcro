(ns untangled.server.impl.components.web-server
  (:require [org.httpkit.server :refer [run-server]]
            [taoensso.timbre :as timbre]
            [com.stuartsierra.component :as component])
  (:gen-class))

(defrecord WebServer [port handler server]
  component/Lifecycle
  (start [this]
    (timbre/info "Web server started successfully.")
    (try
      (assoc this :server
             (run-server (:all-routes (:handler this))
                         {:port (-> this :config :value :port)
                          :join? false}))
      (catch Exception e
        (timbre/fatal "Failed to start web server " e))))
  (stop [this]
    (when-let [server (:server this)]
      (server)
      (timbre/info "web server stopped.")
      (assoc this :server nil))))
