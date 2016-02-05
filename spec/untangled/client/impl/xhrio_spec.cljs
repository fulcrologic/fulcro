(ns untangled.client.impl.xhrio-spec
  (:require [goog.events :as events]
            [untangled.client.logging :as log])
  (:require-macros
    [cljs.test :refer [is deftest testing async]])
  (:import [goog.net XhrIo EventType]))

;; This test requires that you allow cross-origin resource sharing in your browser. This is disabled by default.
;; Install this plugin for Google Chrome before running this test, or it will crash the test suite:

;; https://chrome.google.com/webstore/detail/allow-control-allow-origi/nlfbmbojpeacfghkpbjhddihlkkiljbi?hl=en

;; NOTE: This will DISABLE critical security features of your browser. Handle with care. Disable before browsing the web.

#_(deftest XhrIo-Testing
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
