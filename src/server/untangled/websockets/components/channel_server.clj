(ns untangled.websockets.components.channel-server
  (:require [clojure.core.async :as async :refer [<! <!! chan go thread]]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer [sente-web-server-adapter]]
            [ring.middleware.params :as params]
            [ring.middleware.keyword-params :as keyword-params]
            [untangled.transit-packer :as tp]))

(defn wrap-web-socket [handler]
  (-> handler
    (keyword-params/wrap-keyword-params)
    (params/wrap-params)))

;; SAMPLE MESSAGE FROM CLIENT self-identified as "930" that we have assigned user-id 1 to (via (<! recv-channel))
;; {:?reply-fn      (fn [edn] ...plumbing to client ...),
;;  :ch-recv        #<ManyToManyChannel clojure.core.async.impl.channels.ManyToManyChannel@3926ef92>,
;;  :client-id      "930",
;;  :connected-uids #<Atom@1bb933d: {:ws #{1}, :ajax #{}, :any #{1}}>,
;;  :uid            1,
;;  :event          [:a/b {:a 22}],
;;  :id             :a/b,
;;  :ring-req
;;                  {:remote-addr          "0:0:0:0:0:0:0:1",
;;                   :params               {:client-id "930"},
;;                   :datahub/credentials  {:real-user nil, :effective-user nil, :realm nil},
;;                   :route-params         {},
;;                   :headers              {"Authorization" "Bearer some-token" ,"origin" "http://localhost:4001", "host" "localhost:3000", ...}
;;                   :websocket?           true,
;;                   :query-params         {"client-id" "930"},
;;                   :datahub/dependencies {:databases ...}
;;                   :server-name          "localhost",
;;                   :query-string         "client-id=930",
;;                   :scheme               :http,
;;                   :request-method       :get},
;;  :?data          {:a 22},
;;  :send-fn #<sente$make_channel_socket_BANG_$send_fn__29153 taoensso.sente$make_channel_socket_BANG_$send_fn__29153@1d0c0cb4>}

;; Message from client: [ target-keyword { :sub-target kw :content edn-msg } ]
;; message handed to message-received: { :reply-fn (fn [edn] ...)   ; optional...if it is there, should be called with response
;;                                       :content edn-value
;;                                     }

(defmulti message-received
  "The primary multi-method to define methods for in order to receive client messages."
  :id)

(def post-handler (atom nil))

(def ajax-get-or-ws-handler (atom nil))

(defrecord ChannelServer [handler
                          connected-services            ; atom containing a set of target keywords that have registered multimethods
                          clients                       ; atom containing all connected clients
                          ring-ajax-post                ; ring hook-ups
                          ring-ajax-get-or-ws-handshake
                          ch-recv                       ; incoming messages
                          chsk-send!                    ; server push by uid
                          connected-uids
                          router
                          handshake-data-fn
                          user-id-fn
                          chsk-handler]

  component/Lifecycle
  (start [component]
    (timbre/info "Starting Channel Server.")
    (let [pre-hook          (.get-pre-hook handler)
          {:keys [ajax-get-or-ws-handshake-fn
                  ajax-post-fn
                  ch-recv
                  connected-uids
                  send-fn]} (sente/make-channel-socket!
                              sente-web-server-adapter
                              {:user-id-fn        user-id-fn
                               :handshake-data-fn handshake-data-fn
                               :packer            tp/packer})
          component         (assoc component
                              :connected-services (atom #{})
                              :clients (atom #{})
                              :ring-ajax-post ajax-post-fn
                              :ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn
                              :ch-recv ch-recv                 ; ChannelSocket's receive channel
                              :chsk-send! send-fn              ; ChannelSocket's send API fn
                              :connected-uids connected-uids
                              :router (sente/start-server-chsk-router! ch-recv chsk-handler))]

      (reset! post-handler ajax-post-fn)
      (reset! ajax-get-or-ws-handler ajax-get-or-ws-handshake-fn)

      (.set-pre-hook! handler
        (comp pre-hook wrap-web-socket))
      component))

  (stop [component]
    (if-let [stop-f router]
      (assoc component :router (stop-f))
      component)))

(defn make-channel-server [& {:keys [user-id-fn handshake-data-fn]}]
  (component/using
    (map->ChannelServer {:handshake-data-fn (or handshake-data-fn (fn [ring-req]
                                                                    (get (:headers ring-req) "Authorization")))
                         :user-id-fn        (or user-id-fn (let [id-atom (atom 0)]
                                                             (fn [request]
                                                               (swap! id-atom inc))))
                         :chsk-handler      message-received})
    [:handler]))

(defn route-handlers
  "Route handler that is expected to be passed to `:extra-routes` when creating an untangled app."
  [req _env _match]
  (let [ring-ajax-get-or-ws-handshake @ajax-get-or-ws-handler
        ring-ajax-post @post-handler]
    (assert (not (and
                   (nil? @post-handler)
                   (nil? @ajax-get-or-ws-handler)))
      "Your handlers are nil. Did you start the channel server?")
    (case (:request-method req)
      :get  (try (ring-ajax-get-or-ws-handshake req)
                 (catch Exception e (.printStackTrace e System/out)))
      :post (ring-ajax-post req))))
