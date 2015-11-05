(ns config.component
  (:require [com.stuartsierra.component :as component]
            [untangled.config.component :refer [new-config]]
            [untangled.config.core :as cfg])
  (:use midje.sweet))

(defrecord App [config]
  component/Lifecycle
  (start [this]
    (assoc this :config config))
  (stop [this]
    this))

(defn new-app []
  (component/using
    (map->App {})
    [:config]))

(facts :focused "untangled.config.component"
       (facts :focused "new-config"
              (fact :focused "returns a stuartsierra component"
                    (satisfies? component/Lifecycle (new-config)) => true
                    )
              (fact :focused ".start loads the config"
                    (.start (new-config)) => (contains {:config ..cfg..})
                    (provided
                      (cfg/load-config anything) => ..cfg..)
                    )
              (fact :focused ".stop removes the config"
                    (-> (new-config) .start .stop :config) => nil
                    (provided
                      (cfg/load-config anything) => anything)
                    )
              )
       (facts :focused :integration "smoke test with component/system"
              (-> (component/system-map
                    :config (new-config)
                    :app (new-app))
                  .start :app :config :config) => {:foo :bar}
              (provided
                (cfg/load-config anything) => {:foo :bar})
              )
       )
