(ns ^:focused untangled.util.logging-spec
  (:require [clojure.test :refer :all]
            [untangled-spec.core :refer [specification assertions when-mocking component behavior]]
            [untangled.util.logging :as l])
  (:import (org.graylog2.gelfclient.transport GelfUdpTransport GelfTcpTransport)))

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
