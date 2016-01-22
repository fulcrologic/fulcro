(ns untangled.server.impl.components.logger
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as t]
            [untangled.server.impl.logging :as l]))

(defn start-logging! [host port level]
  (if (and host port)
        (t/merge-config! {:level level :appenders {:gelf (l/gelf-appender host port)}})
        (t/set-level! level)))

(defn reset-logging! []
  (t/set-config! t/example-config))

(defrecord Logger [config]
  component/Lifecycle

  (start [this]
    (let [{:keys [host port level] :or {level :debug} :as logging-config} (-> this :config :value :logging)]
      (start-logging! host port level)
      this))

  (stop [this]
    (reset-logging!)
    this))


