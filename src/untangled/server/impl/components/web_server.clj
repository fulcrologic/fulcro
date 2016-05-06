(ns untangled.server.impl.components.web-server
  (:require [org.httpkit.server :refer [run-server]]
            [taoensso.timbre :as timbre]
            [com.stuartsierra.component :as component]
            [environ.core :refer [env]])
  (:gen-class))

(defrecord WebServer [port handler server]
  component/Lifecycle
  (start [component] []
    (let [http-kit-opts [:ip :port :thread :worker-name-prefix
                         :queue-size :max-body :max-line]
          server-opts (select-keys (-> component :config :value)
                                   http-kit-opts)]
      (timbre/info (str "Web server started successfully."
                        (if (env :dev) (timbre/info "(using the development profile)")))
                   "With options:" server-opts)
      (try
        (assoc component :server (run-server (:all-routes (:handler component))
                                             server-opts))
        (catch Exception e
          (timbre/fatal "Failed to start web server " e)))))
  (stop [component] []
    (when-let [server (:server component)]
      (server)
      (timbre/info "web server stopped.")
      (assoc component :server nil))))
