(ns untangled.components.logger
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as t]
            [untangled.util.logging :as l]))

(defrecord Logger [config]
  component/Lifecycle

  (start [this]
    (let [{:keys [host port level] :or {level :debug} :as logging-config} (-> this :config :value :logging)]
      (if (and host port)
        (t/merge-config! {:level level :appenders {:gelf (l/gelf-appender host port)}})
        (t/set-level! level))
      this))

  (stop [this]
    (t/set-config! t/example-config)
    this))

(defn build-logger []
  (component/using
    (map->Logger {})
    [:config]))
