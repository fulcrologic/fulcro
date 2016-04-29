(ns untangled.client.impl.network
  (:require [untangled.client.logging :as log]
            [cognitect.transit :as ct]
            [goog.events :as events]
            [om.transit :as t])
  (:require-macros
    [cljs.core.async.macros :refer [go]])
  (:import [goog.net XhrIo EventType]))

(declare make-untangled-network)

(defprotocol UntangledNetwork
  (send [this edn ok-callback error-callback]
        "Send method, transmits EDN to the server and gets an EDN response. Calls result-callback with that response,
        or a map with key `:error` on errors. optional options may include `:headers`, but you may NOT override content
        type. The method CANNOT be used for parallel network requests.")
  (say [this verb params ok-callback] "Send a verb to the server for separate non-query processing. Any number of these can be started at once."))

(defprotocol IXhrIOCallbacks
  (response-ok [this xhrio ok-cb] "Called by XhrIo on OK")
  (response-error [this xhrio err-cb] "Called by XhrIo on ERROR"))

(defn parse-response [xhr-io]
  (ct/read (t/reader {:handlers {"f" (fn [v] (js/parseFloat v))}}) (.getResponseText xhr-io)))

(defrecord Network [url request-transform global-error-callback]
  IXhrIOCallbacks
  (response-ok [this xhr-io valid-data-callback]
    ;; Implies:  everything went well and we have a good response
    ;; (i.e., got a 200).
    (try
      (let [query-response (parse-response xhr-io)]
        (when (and query-response valid-data-callback) (valid-data-callback query-response)))
      (finally (.dispose xhr-io))))

  (response-error [this xhr-io error-callback]
    ;; Implies:  request was sent.
    ;; *Always* called if completed (even in the face of network errors).
    ;; Used to detect errors.
    (try
      (letfn [(log-and-dispatch-error [str error]
                ;; note that impl.application/initialize will partially apply the
                ;; app-state as the first arg to global-error-callback
                (log/error str)
                (@global-error-callback error)
                (error-callback error))]
        (if (zero? (.getStatus xhr-io))
          (log-and-dispatch-error
            (str "UNTANGLED NETWORK ERROR: No connection established.")
            {:type :network})
          (log-and-dispatch-error
            (str "SERVER ERROR CODE: " (.getStatus xhr-io))
            (parse-response xhr-io))))
      (finally (.dispose xhr-io))))

  UntangledNetwork
  (send [this edn ok err]
    (let [xhrio (XhrIo.)
          headers {"Content-Type" "application/transit+json"}
          {:keys [request headers]} (cond
                                      request-transform (request-transform {:request edn :headers headers})
                                      :else {:request edn :headers headers})
          post-data (ct/write (t/writer) request)
          headers (clj->js headers)]
      (.send xhrio url "POST" post-data headers)
      (events/listen xhrio (.-SUCCESS EventType) #(response-ok this xhrio ok))
      (events/listen xhrio (.-ERROR EventType) #(response-error this xhrio err)))))

(defn make-untangled-network [url & {:keys [request-transform global-error-callback]}]
  (map->Network {:url                   url
                 :request-transform     request-transform
                 :global-error-callback (atom global-error-callback)
                 }))

(defrecord MockNetwork []
  UntangledNetwork
  (send [this edn ok err]
    (log/info "Ignored (mock) Network request " edn)))

(defn mock-network [] (MockNetwork.))

