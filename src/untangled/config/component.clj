(ns untangled.config.component
  (:require [com.stuartsierra.component :as component]
            [untangled.config.core :as cfg]
            ))

(defrecord Config [config defaults-path props-path sys-prop]
  component/Lifecycle
  (start [this]
    (let [config (cfg/load-config this)]
      (assoc this :config config))
    )
  (stop [this]
    (assoc this :config nil)
    ))

(defn new-config [& [props-path defaults-path sys-prop]]
  (map->Config {:defaults-path defaults-path
                :props-path props-path
                :sys-prop sys-prop}))
