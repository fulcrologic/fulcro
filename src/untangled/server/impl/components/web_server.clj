(ns untangled.server.impl.components.web-server
  (:require [org.httpkit.server :refer [run-server]]
            [taoensso.timbre :as timbre]
            [com.stuartsierra.component :as component]
            [environ.core :refer [env]])
  (:gen-class))

(defrecord WebServer [port handler server]
  component/Lifecycle
  (start [component] []
    (timbre/info "Web server started successfully."
      (when (env :dev) "(using the development profile)"))
    (try
      (assoc component :server (run-server (:all-routes (:handler component))
                                 {:port (-> component :config :value :port) :join? false}))
      (catch Exception e
        (timbre/fatal "Failed to start web server " e))
      )
    )
  (stop [component] []
    (when-let [server (:server component)]
      (server)
      (timbre/info "web server stopped.")
      (assoc component :server nil))
    )
  )

(defn make-web-server []
  (component/using
    (map->WebServer {})
    [:handler :config]
    )
  )
