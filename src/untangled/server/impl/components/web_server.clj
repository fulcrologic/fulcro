(ns untangled.server.impl.components.web-server
  (:require [org.httpkit.server :refer [run-server]]
            [taoensso.timbre :as timbre]
            [com.stuartsierra.component :as component])
  (:gen-class))

(def http-kit-opts
  [:ip :port :thread :worker-name-prefix
   :queue-size :max-body :max-line])

(defrecord WebServer [port handler server]
  component/Lifecycle
  (start [this]
    (try
      (let [server-opts (select-keys (-> this :config :value) http-kit-opts)
            port (:port server-opts)
            started-server (run-server (:all-routes handler) server-opts)]
        (timbre/info "Web server started successfully. With options:" server-opts)
        (assoc this :port port :server started-server))
      (catch Exception e
        (timbre/fatal "Failed to start web server " e)
        (throw e))))
  (stop [this]
    (when server
      (server)
      (timbre/info "web server stopped.")
      (assoc this :server nil))))
