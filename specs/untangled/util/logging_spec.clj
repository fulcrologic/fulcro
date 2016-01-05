(ns untangled.util.logging-spec
  (:require [clojure.test :refer :all]
            [untangled-spec.core :refer [specification assertions when-mocking component behavior]]
            [untangled.util.logging :as l])
  (:import (org.graylog2.gelfclient.transport GelfUdpTransport GelfTcpTransport)
           (org.graylog2.gelfclient GelfMessageLevel)))

(specification "timbre-to-gelf-level"
  (behavior "maps timbre's level to corresponding gelf level"
    (assertions
      (l/timbre-to-gelf-level :fatal) => GelfMessageLevel/CRITICAL ;; gelf doesn't have FATAL
      (l/timbre-to-gelf-level :info) => GelfMessageLevel/INFO
      (l/timbre-to-gelf-level :warning) => GelfMessageLevel/WARNING
      (l/timbre-to-gelf-level :error) => GelfMessageLevel/ERROR))

  (behavior "responds with GelfMessageLevel/WARNING to an unrecognized timbre level"
    (assertions (l/timbre-to-gelf-level :OMGBBQ) => GelfMessageLevel/WARNING)))

(specification "make-gelf-transport"
  (behavior "can specify the transport protocol"
    (assertions
      (l/make-gelf-transport "host" 1 :tcp) =fn=> #(instance? GelfTcpTransport %)
      (l/make-gelf-transport "host" 1 :udp) =fn=> #(instance? GelfUdpTransport %))
    (behavior "and accepts only valid protocols"
        (assertions
          (l/make-gelf-transport "host" 1 :smalltalk) =throws=> (AssertionError #"")))))

(specification "gelf-appender"
  (let [appender (l/gelf-appender "localhost" 12345)]
    (behavior "takes an optional transport protocol"
      (assertions
        (l/gelf-appender "host" 1 :tcp) =fn=> #(instance? GelfTcpTransport (:gelf-transport %)))
      (behavior "that defaults to udp if unspecified"
        (assertions
          appender =fn=> #(instance? GelfUdpTransport (:gelf-transport %)))))
    (behavior "returns a map"
      (assertions
        appender =fn=> map?)
      (behavior "that contains a function at :fn"
        (assertions
          (:fn appender) =fn=> fn?)))))
