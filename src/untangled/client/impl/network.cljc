(ns untangled.client.impl.network
  (:refer-clojure :exclude [send])
  (:require [untangled.client.logging :as log]
            [cognitect.transit :as ct]
    #?(:cljs [goog.events :as events])
            [om.transit :as t]
            [clojure.string :as str])
  #?(:cljs (:import [goog.net XhrIo EventType])))

(declare make-untangled-network)

#?(:cljs
   (defn make-xhrio "This is here (not inlined) to make mocking easier." [] (XhrIo.)))

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

#?(:cljs
   (defn parse-response
     "An XhrIo-specific implementation method for interpreting the server response."
     ([xhr-io] (parse-response xhr-io nil))
     ([xhr-io read-handlers]
      (try (let [text (.getResponseText xhr-io)
                 base-handlers {"f" (fn [v] (js/parseFloat v)) "u" cljs.core/uuid}
                 handlers (if (map? read-handlers) (merge base-handlers read-handlers) base-handlers)]
             (if (str/blank? text)
               (.getStatus xhr-io)
               (ct/read (t/reader {:handlers handlers})
                        (.getResponseText xhr-io))))
           (catch js/Object e {:error 404 :message "Server down"})))))

(defrecord Network [url request-transform global-error-callback complete-app transit-handlers]
  IXhrIOCallbacks
  (response-ok [this xhr-io valid-data-callback]
    ;; Implies:  everything went well and we have a good response
    ;; (i.e., got a 200).
    #?(:cljs
       (try
         (let [read-handlers (:read transit-handlers)
               query-response (parse-response xhr-io read-handlers)]
           (when (and query-response valid-data-callback) (valid-data-callback query-response)))
         (finally (.dispose xhr-io)))))
  (response-error [this xhr-io error-callback]
    ;; Implies:  request was sent.
    ;; *Always* called if completed (even in the face of network errors).
    ;; Used to detect errors.
    #?(:cljs
       (try
         (let [status (.getStatus xhr-io)
               log-and-dispatch-error (fn [str error]
                                        ;; note that impl.application/initialize will partially apply the
                                        ;; app-state as the first arg to global-error-callback
                                        (log/error str)
                                        (error-callback error)
                                        (when @global-error-callback
                                          (@global-error-callback status error)))]
           (if (zero? status)
             (log-and-dispatch-error
               (str "UNTANGLED NETWORK ERROR: No connection established.")
               {:type :network})
             (log-and-dispatch-error
               (str "SERVER ERROR CODE: " status)
               (parse-response xhr-io transit-handlers))))
         (finally (.dispose xhr-io)))))

  UntangledNetwork
  (send [this edn ok err]
    #?(:cljs
       (let [xhrio (make-xhrio)
             handlers (or (:write transit-handlers) {})
             headers {"Content-Type" "application/transit+json"}
             {:keys [body headers]} (cond-> {:body edn :headers headers}
                                            request-transform request-transform)
             post-data (ct/write (t/writer {:handlers handlers}) body)
             headers (clj->js headers)]
         (.send xhrio url "POST" post-data headers)
         (events/listen xhrio (.-SUCCESS EventType) #(response-ok this xhrio ok))
         (events/listen xhrio (.-ERROR EventType) #(response-error this xhrio err)))))
  (start [this app]
    (assoc this :complete-app app)))


(defn make-untangled-network
  "TODO: This is PUBLIC API! Should not be in impl ns.

  Build an Untangled Network object using the default implementation.

  Features:

  - Can configure the target URL on the server for Om network requests
  - Can supply a (fn [{:keys [body headers] :as req}] req') to transform arbitrary requests (e.g. to add things like auth headers)
  - Supports a global error callback (fn [status-code error] ) that is notified when a 400+ status code or hard network error occurs
  - `transit-handlers`: A map of transit handlers to install on the reader, such as

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
  UntangledNetwork
  (send [this edn ok err]
    (log/info "Ignored (mock) Network request " edn))
  (start [this app]
    (assoc this :complete-app app)))

(defn mock-network [] (map->MockNetwork {}))
