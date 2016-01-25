(ns untangled.client.impl.network
  (:require [cljs.core.async :as async]
            [untangled.client.logging :as log]
            [cognitect.transit :as ct]
            [goog.events :as events]
            [om.transit :as t]
            [untangled.client.impl.om-plumbing :as plumbing])
  (:require-macros
    [cljs.core.async.macros :refer [go]])
  (:import [goog.net XhrIo EventType]))

(defprotocol UntangledNetwork
  (send [this edn ok-callback error-callback] [this edn ok-callback error-callback options] "Send method, transmits EDN to the server and gets
   an EDN response. Calls result-callback with that response, or a map with key `:error` on errors. optional options
   may include `:headers`, but you may NOT override content type."))

(defprotocol IXhrIOCallbacks
  (response-ok [this] "Called by XHRIO on OK")
  (request-complete [this] "Called by XHRIO on COMPLETE"))

(defrecord Network [xhr-io url error-callback valid-data-callback]
  IXhrIOCallbacks
  (response-ok [this]
    ;; Implies:  everything went well and we have a good response
    ;; (i.e., got a 200).
    (let [{:keys [query-response error]} (ct/read (t/reader) (.getResponseText (:xhr-io this)))]
      (when (and error @error-callback) (@error-callback error))
      ; TODO: Survey server error handler
      (when (and query-response @valid-data-callback) (@valid-data-callback query-response))))

  (request-complete [this]
    ;; Implies:  request was sent.
    ;; *Always* called if completed (even in the face of network errors).
    ;; Used to detect errors.
    (when (and (not (.isSuccess (:xhr-io this))) @error-callback)
      (@error-callback {:type :network})))

  UntangledNetwork
  (send [this edn ok err {:keys [headers]}]
    (let [post-data (ct/write (t/writer) edn)
          headers (clj->js (merge headers {"Content-Type" "application/transit+json"}))]
      (reset! error-callback (fn [e] (err e)))
      (reset! valid-data-callback (fn [resp] (ok resp)))
      (.send xhr-io url "POST" post-data headers)))
  (send [this post-data ok-callback error-callback] (send this post-data ok-callback error-callback {})))

(defn make-untangled-network [url]
  (let [xhrio (XhrIo.)
        rv (map->Network {:xhr-io              xhrio
                          :url                 url
                          :valid-data-callback (atom nil)
                          :error-callback      (atom nil)})]
    (events/listen xhrio (.-SUCCESS EventType) #(response-ok rv))
    (events/listen xhrio (.-COMPLETE EventType) #(request-complete rv))
    rv))


