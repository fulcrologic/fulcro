(ns untangled.server.impl.components.web-server
  (:require [org.httpkit.server :refer [run-server]]
            [taoensso.timbre :as timbre]
            [com.stuartsierra.component :as component])
  (:gen-class))

(defrecord WebServer [port handler server]
  component/Lifecycle
  (start [this]
    (let [port (-> this :config :value :port)]
      (try
        (assoc this :server
               (run-server (:all-routes handler)
                           {:port port
                            :join? false}))
        (catch Exception e
          (timbre/fatal "Failed to start web server, on port " port)
          (throw e)))
      (timbre/info "Web server started successfully, on port: " port)))
  (stop [this]
    (when server
      (server)
      (timbre/info "web server successfully stopped.")
      (assoc this :server nil))))
