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

(defn timbre-to-gelf-level [level]
  (let [gelf-levels {:info  GelfMessageLevel/INFO
                     :warn  GelfMessageLevel/WARNING
                     :error GelfMessageLevel/ERROR
                     :fatal GelfMessageLevel/CRITICAL}]
    (get gelf-levels level GelfMessageLevel/WARNING)))

(defn make-gelf-transport
  "Make a new GelfTransport object, capable of sending a GelfMessage to a remote server.

  Parameters:
  *`host` - An IP address or hostname string of the remote logging server.
  *`port` - The TCP or UDP port on which the server listens.
  *`protocol` - Use :tcp or :udp transport to send messages.

  Returns a new GelfTransport object."
  [host port protocol]
  {:pre [(or (= :udp protocol) (= :tcp protocol))]}
  (let [protocols {:udp GelfTransports/UDP :tcp GelfTransports/TCP}
        transport (protocol protocols)
        config (-> (GelfConfiguration. (InetSocketAddress. host port)) (.transport transport))]
    (GelfTransports/create config)))

(defn gelf-appender
  "A timbre appender capable of sending gelf messages to a remote host.

  Parameters:
  *`gelf-server` - An IP address or hostname string of the remote logging server.
  *`port` - the TCP or UDP port on which the server listens.
  *`protocol` - OPTIONAL, Use :tcp or :udp (default) transport to send messages.

  Returns a map, where :fn is the function timbre will call with a log message."
  ([gelf-server port] (gelf-appender gelf-server port :udp))
  ([gelf-server port protocol]
   (let [tranport (make-gelf-transport gelf-server port protocol)]
     {:enabled?       true
      :async?         false
      :min-level      nil
      :rate-limit     nil
      :output-fn      :inherit
      :gelf-transport tranport

      :fn             (fn [data]
                        (let [{:keys [appender msg_ level hostname_]} data
                              gelf-transport (:gelf-transport appender)
                              log-level (timbre-to-gelf-level level)
                              gelf-message (-> (GelfMessageBuilder. @msg_ @hostname_)
                                             (.level log-level) .build)]
                          (.send gelf-transport gelf-message)))})))
