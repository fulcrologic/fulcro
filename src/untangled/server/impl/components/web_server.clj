(ns untangled.server.impl.components.web-server
  (:require [org.httpkit.server :refer [run-server]]
            [taoensso.timbre :as timbre]
            [com.stuartsierra.component :as component])
  (:gen-class))

(defrecord WebServer [port handler server]
  component/Lifecycle
  (start [component] []
    (try
      (let [http-kit-opts [:ip :port :thread :worker-name-prefix
                           :queue-size :max-body :max-line]
            server-opts (select-keys (-> component :config :value)
                                     http-kit-opts)
            configured-port (:port server-opts)
            rv (assoc component
                 :port configured-port
                 :server (run-server (:all-routes (:handler component))
                                     server-opts))]
        (timbre/info (str "Web server started successfully. Configured options: " server-opts))
        rv)
      (catch Exception e
        (timbre/fatal "Failed to start web server " e))))
  (stop [component] []
    (when-let [server (:server component)]
      (server)
      (timbre/info "web server stopped.")
      (assoc component :server nil))))
