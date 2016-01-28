(ns untangled.client.impl.xhrio-spec
  (:require [goog.events :as events]
            [untangled.client.logging :as log])
  (:require-macros
    [cljs.test :refer [is async]]
    [untangled-spec.core :refer [specification behavior assertions provided component when-mocking]])
  (:import [goog.net XhrIo EventType]))

(specification "XhrIo-Testing"
  (behavior "Returns success event when data is loaded." :manual-test
    (let [net (XhrIo.)
          ok (fn [xhrio]
               (log/info (str "RAN OK: " (.getStatus xhrio)))
               (is true))
          complete (fn [xhrio]
                     (log/info (str "RAN COMPLETE: " (js->clj (.getResponseHeaders xhrio))))
                     (is true))
          error (fn [xhrio]
                  (log/info (str "RESPONSE ERROR: " (.getStatus xhrio)))
                  (is true))]

      (.setTimeoutInterval net 50)
      (events/listen net (.-SUCCESS EventType) #(ok net))
      (events/listen net (.-COMPLETE EventType) #(complete net))
      (events/listen net (.-ERROR EventType) #(error net))

      ;; This will call `ok`
      ;; Add path /foo/bar to url for call to `error`
      (.send net "http://www.cnn.com" "GET"))))
