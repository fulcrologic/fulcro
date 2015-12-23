(ns untangled.util.logging
  (:require [taoensso.timbre :as t])
  (:import (org.graylog2.gelfclient
             GelfConfiguration
             GelfMessageBuilder
             GelfTransports
             GelfMessageLevel)
           (java.net InetSocketAddress)))

(defn fatal
  "
  A mock-able wrapper for the timbre logging library. This helps us verify that certain critical logging messages
  are emitted within our unit tests

  Parameters
  * `msgs` An arbitrary vector of logging messages that should be printed after timbre's default fatal line

  Returns a call to taoensso.timbre/fatal with our custom messages.
  "

  [& msgs] (t/fatal msgs))

(defn error
  "
  A mock-able wrapper for the timbre logging library. This helps us verify that certain critical logging messages
  are emitted within our unit tests

  Parameters
  * `msgs` An arbitrary vector of logging messages that should be printed after timbre's default fatal line

  Returns a call to taoensso.timbre/error with our custom messages.
  "

  [& msgs] (t/error msgs))

(defn info
  "
  A mock-able wrapper for the timbre logging library. This helps us verify that certain critical logging messages
  are emitted within our unit tests

  Parameters
  * `msgs` An arbitrary vector of logging messages that should be printed after timbre's default fatal line

  Returns a call to taoensso.timbre/info with our custom messages.
  "
  [& msgs] (t/info msgs))

(defn make-gelf-transport [host port]
  (let [config (-> (GelfConfiguration. (InetSocketAddress. host port)) (.transport GelfTransports/UDP))
        transport (GelfTransports/create config)]
    transport))

(def gelf-levels {:warn  GelfMessageLevel/WARNING
                  :info  GelfMessageLevel/INFO
                  :error GelfMessageLevel/ERROR
                  :fatal GelfMessageLevel/CRITICAL})

(defn gelf-appender [host port]
  (let [tranport (make-gelf-transport host port)]
    {:enabled?       true
     :async?         false
     :min-level      nil
     :rate-limit     nil
     :output-fn      :inherit
     :gelf-transport tranport
     :gelf-levels    gelf-levels

     :fn             (fn [data]
                       (let [{:keys [appender msg_ level hostname_]} data
                             gelf-transport (:gelf-transport appender)
                             log-level (get-in appender [:gelf-levels level])
                             gelf-message (-> (GelfMessageBuilder. @msg_ @hostname_)
                                            (.level log-level) .build)]
                         (.send gelf-transport gelf-message)))}))


(comment

  (t/merge-config! {:appenders {:gelf (gelf-appender "10.9.2.25" 12201) ;; add the gelf-appender
                                ;:println {:enabled? false} ;; console logging will still happen,
                                                            ;; so we could disable the default appender this way
                                }})

  (t/warn "GEM-133: testing gelf appender for timbre")

  )
