(ns untangled.client.impl.xhrio-spec
  (:require [goog.events :as events]
            [untangled.client.logging :as log])
  (:require-macros
    [cljs.test :refer [is deftest testing async]])
  (:import [goog.net XhrIo EventType]))

(deftest XhrIo-Testing
  (testing "Runs ok when receiving a valid response"
    (async done
      (let [net (XhrIo.)
            ok (fn [xhrio]
                 (log/info (str "RAN OK: " (.getStatus xhrio)))
                 (is true)
                 (done))
            error (fn [xhrio]
                    (log/info (str "RESPONSE ERROR: " (.getStatus xhrio)))
                    (is false)
                    (done))]

        (.setTimeoutInterval net 50)
        (events/listen net (.-SUCCESS EventType) #(ok net))
        (events/listen net (.-ERROR EventType) #(error net))

        ;; This will call `ok`
        ;; Add path /foo/bar to url for call to `error`
        (.send net "http://www.cnn.com" "GET"))))

  (testing "Runs error when receiving an invalid response"
    (async done
      (let [net (XhrIo.)
            ok (fn [xhrio]
                 (log/info (str "RAN OK: " (.getStatus xhrio)))
                 (is false)
                 (done))
            error (fn [xhrio]
                    (log/info (str "RESPONSE ERROR: " (.getStatus xhrio)))
                    (is true)
                    (done))]

        (.setTimeoutInterval net 50)
        (events/listen net (.-SUCCESS EventType) #(ok net))
        (events/listen net (.-ERROR EventType) #(error net))

        (.send net "http://www.cnn.com/foo" "GET")))))
