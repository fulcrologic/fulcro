(ns untangled.client.impl.network
  (:require [untangled.client.logging :as log]
            [cognitect.transit :as ct]
            [goog.events :as events]
            [om.transit :as t])
  (:require-macros
    [cljs.core.async.macros :refer [go]])
  (:import [goog.net XhrIo EventType]))

(defprotocol UntangledNetwork
  (send [this edn ok-callback error-callback]
    "Send method, transmits EDN to the server and gets an EDN response. Calls result-callback with that response,
    or a map with key `:error` on errors. optional options may include `:headers`, but you may NOT override content
    type."))

(defprotocol IXhrIOCallbacks
  (response-ok [this] "Called by XhrIo on OK")
  (response-error [this] "Called by XhrIo on ERROR"))

(defn parse-response [xhr-io]
  (ct/read (t/reader {:handlers {"f" (fn [v] (js/parseFloat v))}}) (.getResponseText xhr-io)))

(defrecord Network [xhr-io url error-callback valid-data-callback request-transform global-error-callback]
  IXhrIOCallbacks
  (response-ok [this]
    ;; Implies:  everything went well and we have a good response
    ;; (i.e., got a 200).
    (let [query-response (parse-response xhr-io)]
      (when (and query-response @valid-data-callback) (@valid-data-callback query-response))))

  (response-error [this]
    ;; Implies:  request was sent.
    ;; *Always* called if completed (even in the face of network errors).
    ;; Used to detect errors.
    (letfn [(log-and-dispatch-error [str error]
              ;; note that impl.application/initialize will partially apply the
              ;; app-state as the first arg to global-error-callback
              (log/error str)
              (@global-error-callback error)
              (@error-callback error))]

      (if (zero? (.getStatus xhr-io))
        (log-and-dispatch-error
          (str "UNTANGLED NETWORK ERROR: No connection established.")
          {:type :network})
        (log-and-dispatch-error
          (str "SERVER ERROR CODE: " (.getStatus xhr-io))
          (parse-response xhr-io)))))

  UntangledNetwork
  (send [this edn ok err]
    (let [headers {"Content-Type" "application/transit+json"}
          {:keys [request headers]} (cond
                                      request-transform (request-transform {:request edn :headers headers})
                                      :else {:request edn :headers headers})
          post-data (ct/write (t/writer) request)
          headers (clj->js headers)]
      (reset! error-callback (fn [e] (err e)))
      (reset! valid-data-callback (fn [resp] (ok resp)))
      (.send xhr-io url "POST" post-data headers))))

(defn make-untangled-network [url & {:keys [request-transform global-error-callback]}]
  (let [xhrio (XhrIo.)
        rv (map->Network {:xhr-io                xhrio
                          :url                   url
                          :request-transform     request-transform
                          :global-error-callback (atom global-error-callback)
                          :valid-data-callback   (atom nil)
                          :error-callback        (atom nil)})]
    (events/listen xhrio (.-SUCCESS EventType) #(response-ok rv))
    (events/listen xhrio (.-ERROR EventType) #(response-error rv))
    rv))

(defrecord MockNetwork []
  UntangledNetwork
  (send [this edn ok err]
    (log/info "Ignored (mock) Network request " edn)))

(defn mock-network [] (MockNetwork.))

