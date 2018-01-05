(ns fulcro.client.network
  (:refer-clojure :exclude [send])
  (:require [fulcro.client.logging :as log]
            [cognitect.transit :as ct]
    #?(:cljs [goog.events :as events])
            [fulcro.transit :as t]
            [clojure.string :as str])
  #?(:cljs (:import [goog.net XhrIo EventType])))

(declare make-fulcro-network)

#?(:cljs
   (defn make-xhrio "This is here (not inlined) to make mocking easier." [] (XhrIo.)))

(defprotocol NetworkBehavior
  (serialize-requests? [this] "Returns true if the network is configured to desire one request at a time."))

(defprotocol ProgressiveTransfer
  (updating-send [this edn done-callback error-callback update-callback] "Send EDN. The update-callback will merge the state
  given to it. The done-callback will merge the state given to it, and indicates completion. See
  `fulcro.client.ui.file-upload/FileUploadNetwork` for an example."))

(defprotocol FulcroNetwork
  (send [this edn done-callback error-callback]
    "Send EDN. Calls either the done or error callback when the send is done. You must call one of those only once.
     Implement ProgressiveTransfer if you want to do progress updates during network transmission.")
  (start [this]
    "Starts the network."))

(defprotocol IXhrIOCallbacks
  (response-ok [this xhrio ok-cb] "Called by XhrIo on OK")
  (response-error [this xhrio err-cb] "Called by XhrIo on ERROR"))

#?(:cljs
   (defn parse-response
     "An XhrIo-specific implementation method for interpreting the server response."
     ([xhr-io] (parse-response xhr-io nil))
     ([xhr-io read-handlers]
      (try (let [text          (.getResponseText xhr-io)
                 base-handlers {"f" (fn [v] (js/parseFloat v)) "u" cljs.core/uuid}
                 handlers      (if (map? read-handlers) (merge base-handlers read-handlers) base-handlers)]
             (if (str/blank? text)
               (.getStatus xhr-io)
               (ct/read (t/reader {:handlers handlers})
                 (.getResponseText xhr-io))))
           (catch js/Object e {:error 404 :message "Server down"})))))

(defrecord Network [url request-transform global-error-callback complete-app transit-handlers]
  NetworkBehavior
  (serialize-requests? [this] true)
  IXhrIOCallbacks
  (response-ok [this xhr-io valid-data-callback]
    ;; Implies:  everything went well and we have a good response
    ;; (i.e., got a 200).
    #?(:cljs
       (try
         (let [read-handlers  (:read transit-handlers)
               query-response (parse-response xhr-io read-handlers)]
           (when valid-data-callback (valid-data-callback (or query-response {}))))
         (finally (.dispose xhr-io)))))
  (response-error [this xhr-io error-callback]
    ;; Implies:  request was sent.
    ;; *Always* called if completed (even in the face of network errors).
    ;; Used to detect errors.
    #?(:cljs
       (try
         (let [status                 (.getStatus xhr-io)
               log-and-dispatch-error (fn [str error]
                                        ;; note that impl.application/initialize will partially apply the
                                        ;; app-state as the first arg to global-error-callback
                                        (log/error str)
                                        (error-callback error)
                                        (when @global-error-callback
                                          (@global-error-callback status error)))]
           (if (zero? status)
             (log-and-dispatch-error
               (str "NETWORK ERROR: No connection established.")
               {:type :network})
             (log-and-dispatch-error
               (str "SERVER ERROR CODE: " status)
               (parse-response xhr-io transit-handlers))))
         (finally (.dispose xhr-io)))))
  FulcroNetwork
  (send [this edn ok error]
    #?(:cljs
       (let [xhrio     (make-xhrio)
             handlers  (or (:write transit-handlers) {})
             headers   {"Content-Type" "application/transit+json"}
             {:keys [body headers]} (cond-> {:body edn :headers headers}
                                      request-transform request-transform)
             post-data (ct/write (t/writer {:handlers handlers}) body)
             headers   (clj->js headers)]
         (events/listen xhrio (.-SUCCESS EventType) #(response-ok this xhrio ok))
         (events/listen xhrio (.-ERROR EventType) #(response-error this xhrio error))
         (.send xhrio url "POST" post-data headers))))
  (start [this] this))

(defn make-fulcro-network
  "Build a Fulcro Network object using the default implementation.

  Features:

  - `:url` is the target URL suffix (URI) on the server for network requests. defaults to /api.
  - `:request-transform` is a (fn [{:keys [body headers] :as req}] req') to transform arbitrary requests (e.g. to add things like auth headers)
  - `:global-error-callback` is a global error callback (fn [app-state-map status-code error] ) that is notified when a 400+ status code or hard network error occurs
  - `transit-handlers` is a map of transit handlers to install on the reader, such as

   `{ :read { \"thing\" (fn [wire-value] (convert wire-value))) }
      :write { Thing (ThingHandler.) } }`

   where:

   (defrecord Thing [foo])

   (deftype ThingHandler []
     Object
     (tag [_ _] \"thing\")
     (rep [_ thing] (make-raw thing))
     (stringRep [_ _] nil)))
  "
  [url & {:keys [request-transform global-error-callback transit-handlers]}]
  (map->Network {:url                   url
                 :transit-handlers      transit-handlers
                 :request-transform     request-transform
                 :global-error-callback (atom global-error-callback)}))

(defrecord MockNetwork
  [complete-app]
  FulcroNetwork
  (send [this edn ok err] (log/info "Ignored (mock) Network request " edn))
  (start [this] this))

(defn mock-network [] (map->MockNetwork {}))
