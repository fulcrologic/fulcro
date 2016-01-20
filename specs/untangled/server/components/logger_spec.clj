(ns untangled.server.components.logger-spec
  (:require [clojure.test :refer :all]
            [taoensso.timbre :as t]
            [untangled.server.components.logger :as l]
            [untangled-spec.core :refer [specification
                                         assertions
                                         when-mocking
                                         component
                                         behavior]]
            [com.stuartsierra.component :as component]
            [untangled.server.components.config :as cfg]))

(defn- start-system [log-config]
  (.start (component/system-map
            :logger (l/build-logger)
            :config (cfg/raw-config {:logging log-config}))))

(specification "Logger"
  (component "when stopping a system that has been started"
    (let [_ (.stop (start-system {:host "localhost" :port 12201 :level :error}))]
      (behavior "resets timbre's runtime config to the default"
        (assertions
          t/*config* => t/example-config))))
  (component "when starting a system that is configured with level, but no port or host"
    (let [started-system (start-system {:level :info})]
      (behavior "configures the appropriate logging level in timbre"
        (assertions
          (:level t/*config*) => :info))))
  (component "when starting a system that is configured with host, port, but no level"
    (let [started-system (start-system {:host "localhost" :port 12201})]
      (behavior "installs a gelf appender in timbre"
        (assertions
          (-> t/*config* :appenders :gelf) =fn=> map?))
      (behavior "sets timbre logging level to the default"
        (assertions
          (:level t/*config*) => :debug))))
  (component "when starting a system that is configured with host, port, and level"
    (let [started-system (start-system {:host "localhost" :port 12201 :level :error})]
      (behavior "configures the appropriate logging level in timbre"
        (assertions
          (:level t/*config*) => :error))
      (behavior "installs a gelf appender in timbre"
        (assertions
          (-> t/*config* :appenders :gelf) =fn=> map?)))))
