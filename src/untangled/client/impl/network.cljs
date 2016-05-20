(ns untangled.client.impl.network
  (:require [untangled.client.logging :as log]
            [cognitect.transit :as ct]
            [goog.events :as events]
            [om.transit :as t])
  (:require-macros
    [cljs.core.async.macros :refer [go]])
  (:import [goog.net XhrIo EventType]))

(declare make-untangled-network)

(defn make-xhrio []
  (XhrIo.))

(defprotocol UntangledNetwork
  (send [this edn ok-callback error-callback]
        "Send method, transmits EDN to the server and gets an EDN response. Calls result-callback with that response,
        or a map with key `:error` on errors. optional options may include `:headers`, but you may NOT override content
        type. The method CANNOT be used for parallel network requests.")
  (start [this complete-app]
    "Starts the network, passing in the app for any components that may need it."))

(defprotocol IXhrIOCallbacks
  (response-ok [this xhrio ok-cb] "Called by XhrIo on OK")
  (response-error [this xhrio err-cb] "Called by XhrIo on ERROR"))

(defn parse-response [xhr-io]
  (ct/read (t/reader {:handlers {"f" (fn [v] (js/parseFloat v))}}) (.getResponseText xhr-io)))

(defrecord Network [url request-transform global-error-callback complete-app]
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
      (let [status (.getStatus xhr-io)
            log-and-dispatch-error (fn [str error]
                                     ;; note that impl.application/initialize will partially apply the
                                     ;; app-state as the first arg to global-error-callback
                                     (log/error str)
                                     (when @global-error-callback
                                         (@global-error-callback status error))
                                     (error-callback error))]
        (if (zero? status)
          (log-and-dispatch-error
            (str "UNTANGLED NETWORK ERROR: No connection established.")
            {:type :network})
          (log-and-dispatch-error
            (str "SERVER ERROR CODE: " status)
            (parse-response xhr-io))))
      (finally (.dispose xhr-io))))

  UntangledNetwork
  (send [this edn ok err]
    (let [xhrio (make-xhrio)
          headers {"Content-Type" "application/transit+json"}
          {:keys [request headers]} (cond
                                      request-transform (request-transform {:request edn :headers headers})
                                      :else {:request edn :headers headers})
          post-data (ct/write (t/writer) request)
          headers (clj->js headers)]
      (.send xhrio url "POST" post-data headers)
      (events/listen xhrio (.-SUCCESS EventType) #(response-ok this xhrio ok))
      (events/listen xhrio (.-ERROR EventType) #(response-error this xhrio err))))

  (start [this app]
    (assoc this :complete-app app)))

(defn make-untangled-network [url & {:keys [request-transform global-error-callback]}]
  (map->Network {:url                   url
                 :request-transform     request-transform
                 :global-error-callback (atom global-error-callback)
                 }))

(defrecord MockNetwork [complete-app]
  UntangledNetwork
  (send [this edn ok err]
    (log/info "Ignored (mock) Network request " edn))
  (start [this app]
    (assoc this :complete-app app)))

(defn mock-network [] (map->MockNetwork {}))
