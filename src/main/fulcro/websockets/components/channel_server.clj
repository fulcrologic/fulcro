(ns fulcro.websockets.components.channel-server
  (:require [com.stuartsierra.component :as component]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer [sente-web-server-adapter]]
            [ring.middleware.params :as params]
            [ring.middleware.keyword-params :as keyword-params]
            [fulcro.easy-server :refer [api get-pre-hook set-pre-hook!]]
            [fulcro.websockets.transit-packer :as tp]
            [fulcro.websockets.protocols :refer [WSNet WSListener client-added client-dropped]]
            [fulcro.server :as server]
            [fulcro.client.logging :as log]))

(def post-handler (atom nil))

(def ajax-get-or-ws-handler (atom nil))

(defn valid-client-id?
  "Validate that the client id is a guid."
  [client-id]
  (try (when (string? client-id)
         (java.util.UUID/fromString client-id))
       (catch Exception e
         false)))

(def valid-client-id-atom (atom valid-client-id?))

(defn valid-origin?
  "Validates origin based on a collection of whitelisted origin strings received from the config
  at `:ws-origin-whitelist`."
  [config request]
  (if-let [origin-wl (get-in config [:value :ws-origin-whitelist] false)]
    (let [_          (assert (coll? origin-wl) "The :ws-origin-whitelist must be a collection of strings.")
          origins    (set (conj origin-wl (get-in config [:value :origin])))
          req-origin (get-in request [:headers "origin"])]
      (boolean (origins req-origin)))
    true))

(defn route-handlers
  "Route handler that is expected to be passed to `:extra-routes` when creating a Fulcro app.
  Route handlers will look at optionally look at `:ws-origin-whitelist` in your config file, and
  validate origins trying to make a ws connection. If `:ws-origin-whitelist` is nil, origins will
  not be checked. The `:origin` key will also be treated as a valid origin if checking is enabled.

  Example:
  Both the values in `:origin` and `:ws-origin-whitelist` will pass. Any other origin will return 403.
  ```
  {:origin \"localhost:8080\"
   :ws-origin-whitelist \"www.example.io:3000\"}
  ```

  Origins will not be checked.
  ```
  {:origin \"localhost:8080\"}
  ```
  "
  [{:keys [config request]} _match]
  (let [ring-ajax-get-or-ws-handshake @ajax-get-or-ws-handler
        ring-ajax-post                @post-handler]
    (assert (not (and
                   (nil? @post-handler)
                   (nil? @ajax-get-or-ws-handler)
                   (nil? request)))
      "Your handlers are nil. Did you start the channel server?")
    (if (valid-origin? config request)
      (case (:request-method request)
        :get (if (@valid-client-id-atom (get-in request [:params :client-id]))
               (try (ring-ajax-get-or-ws-handshake request)
                    (catch Exception e
                      (let [message (.getMessage e)
                            type    (str (type e))]
                        (log/error "Sente handler error: " type message)
                        {:status 500
                         :body   {:type type :message message}})))
               (do
                 (log/info request)
                 {:status 500
                  :body   "invalid client id"}))
        :post (ring-ajax-post request))
      {:status 403
       :body   "You have tried to connect from an invalid origin."})))

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

(def listeners (ref #{}))

(defn add-listener [listeners listener]
  {:pre [(satisfies? WSListener listener)]}
  (dosync
    (alter listeners conj listener)))
(defn remove-listener [listeners listener]
  {:pre [(satisfies? WSListener listener)]}
  (dosync
    (alter listeners disj listener)))

(defn notify-listeners [f listeners ws-net cid]
  {:pre [(satisfies? WSNet ws-net)]}
  (doall (map #(f % ws-net cid) @listeners)))

(defrecord ChannelServer [handler
                          ring-ajax-post
                          ring-ajax-get-or-ws-handshake
                          ch-recv
                          chsk-send!
                          connected-cids
                          router
                          handshake-data-fn
                          server-adapter
                          client-id-fn
                          transit-handlers]
  WSNet
  (add-listener [this listener]
    (add-listener listeners listener))
  (remove-listener [this listener]
    (remove-listener listeners listener))
  (push [this cid verb edn]
    (chsk-send! cid [:api/server-push {:topic verb :msg edn}]))

  component/Lifecycle
  (start [component]
    (log/info "Starting Channel Server.")
    (let [pre-hook  (get-pre-hook handler)
          {:keys [api-parser
                  env]} handler
          {:keys [ajax-get-or-ws-handshake-fn
                  ajax-post-fn
                  ch-recv
                  connected-uids
                  send-fn]} (sente/make-channel-socket!
                              server-adapter
                              {:user-id-fn        client-id-fn
                               :handshake-data-fn handshake-data-fn
                               :packer            (tp/make-packer transit-handlers)})
          component (assoc component
                      :ring-ajax-post ajax-post-fn
                      :ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn
                      :ch-recv ch-recv
                      :chsk-send! send-fn
                      :connected-cids connected-uids        ; remap uid's to cid's
                      :router (sente/start-server-chsk-router! ch-recv message-received))
          env       (assoc env :ws-net component)]

      (reset! post-handler ajax-post-fn)
      (reset! ajax-get-or-ws-handler ajax-get-or-ws-handshake-fn)

      (set-pre-hook! handler
        (comp pre-hook wrap-web-socket))

      (defmethod message-received :default [message]
        (log/error (str "Received message " message ", but no receiver wanted it!")))

      (defmethod message-received :api/parse [{:keys [client-id ?data ring-req uid] :as message}]
        (let [result (api {:transit-params (:content ?data)
                           :parser         api-parser
                           :env            (assoc env :cid uid :request ring-req)})]
          (send-fn uid [:api/parse result])))

      (defmethod message-received :chsk/uidport-open [{:keys [client-id ?data ring-req uid state] :as message}]
        (log/debug "Port opened by client: " uid)
        (log/debug "Port state: " state)
        (notify-listeners client-added listeners component uid))

      (defmethod message-received :chsk/uidport-close [{:keys [client-id ?data ring-req uid] :as message}]
        (log/debug "Connection closed" client-id)
        (notify-listeners client-dropped listeners component uid))

      (defmethod message-received :chsk/ws-ping [{:keys [client-id ?data ring-req uid] :as message}]
        (log/debug "Ping from client" client-id))

      component))

  (stop [component]
    (let [stop-f router]
      (dosync (ref-set listeners #{}))
      (assoc component :router (stop-f)))))

(defn make-channel-server
  "Creates `ChannelServer`.

  Params:
  - `handshake-data-fn` (Optional) - Used by sente for adding data at the handshake.
  - `server-adapter` (Optional) - adapter for handling servers implemented by sente. Default is http-kit.
  - `client-id-fn` (Optional) - returns a client id from the request.
  - `dependencies` (Optional) - adds dependecies to the fulcro handler.
  - `valid-client-id-fn` (Optional) - Function for validating websocket clients. Expects a client-id.
  - `transit-handlers` (Optional) - Expects a map with `:read` and/or `:write` key containing a map of transit handlers,
  "
  [& {:keys [handshake-data-fn server-adapter client-id-fn dependencies valid-client-id-fn transit-handlers]}]
  (when valid-client-id-fn
    (reset! valid-client-id-atom valid-client-id-fn))
  (component/using
    (map->ChannelServer {:handshake-data-fn (or handshake-data-fn (fn [ring-req]
                                                                    (get (:headers ring-req) "Authorization")))
                         :server-adapter    (or server-adapter sente-web-server-adapter)
                         :client-id-fn      (or client-id-fn (fn [request]
                                                               (:client-id request)))
                         :transit-handlers  transit-handlers})
    (into [] (cond-> [:handler]
               dependencies (concat dependencies)))))

(defrecord SimpleChannelServer [ring-ajax-post
                                ring-ajax-get-or-ws-handshake
                                ch-recv
                                chsk-send!
                                connected-cids
                                router
                                handshake-data-fn
                                server-adapter
                                client-id-fn
                                transit-handlers]
  WSNet
  (add-listener [this listener]
    (add-listener listeners listener))
  (remove-listener [this listener]
    (remove-listener listeners listener))
  (push [this cid verb edn]
    (chsk-send! cid [:api/server-push {:topic verb :msg edn}]))

  component/Lifecycle
  (start [component]
    (log/info "Starting Channel Server.")
    (let [{:keys [ajax-get-or-ws-handshake-fn
                  ajax-post-fn
                  ch-recv
                  connected-uids
                  send-fn]} (sente/make-channel-socket!
                              server-adapter
                              {:user-id-fn        client-id-fn
                               :handshake-data-fn handshake-data-fn
                               :packer            (tp/make-packer transit-handlers)})
          component (assoc component
                      :ring-ajax-post ajax-post-fn
                      :ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn
                      :ch-recv ch-recv
                      :chsk-send! send-fn
                      :connected-cids connected-uids        ; remap uid's to cid's
                      :router (sente/start-server-chsk-router! ch-recv message-received))
          parser    (server/fulcro-parser)
          env       (assoc {} :ws-net component)]

      (reset! post-handler ajax-post-fn)
      (reset! ajax-get-or-ws-handler ajax-get-or-ws-handshake-fn)

      (defmethod message-received :default [message]
        (log/error (str "Received message " message ", but no receiver wanted it!")))

      (defmethod message-received :api/parse [{:keys [client-id ?data ring-req uid] :as message}]
        (let [result (server/handle-api-request parser (assoc env :cid uid :request ring-req) (:content ?data))]
          (send-fn uid [:api/parse result])))

      (defmethod message-received :chsk/uidport-open [{:keys [client-id ?data ring-req uid state] :as message}]
        (log/debug "Port opened by client: " uid)
        (log/debug "Port state: " state)
        (notify-listeners client-added listeners component uid))

      (defmethod message-received :chsk/uidport-close [{:keys [client-id ?data ring-req uid] :as message}]
        (log/debug "Connection closed" client-id)
        (notify-listeners client-dropped listeners component uid))

      (defmethod message-received :chsk/ws-ping [{:keys [client-id ?data ring-req uid] :as message}]
        (log/debug "Ping from client" client-id))

      component))

  (stop [component]
    (let [stop-f router]
      (dosync (ref-set listeners #{}))
      (assoc component :router (stop-f)))))

(defn simple-channel-server
  "
  Creates a channel server that uses the default server parser (you can use defmutation, defquery-root, etc.) for
  incoming requests.  Any dependencies you need in the parsing environment will be injected into the :ws-net entry
  in the parsing env. In other words: this function makes the channel server depend on your stated dependencies,
  and the channel server itself appears under :ws-net in the parsing env. Thus, any injected dependencies will be
  there as well.

  Params:
  - `handshake-data-fn` (Optional) - Used by sente for adding data at the handshake.
  - `server-adapter` (Optional) - adapter for handling servers implemented by sente. Default is http-kit.
  - `client-id-fn` (Optional) - returns a client id from the request.
  - `dependencies` (Optional) - adds dependecies to the :ws-net entry in the parsing environment.
  - `valid-client-id-fn` (Optional) - Function for validating websocket clients. Expects a client-id.
  - `transit-handlers` (Optional) - Expects a map with `:read` and/or `:write` key containing a map of transit handlers,
  "
  [& {:keys [handshake-data-fn server-adapter client-id-fn dependencies valid-client-id-fn transit-handlers]}]
  (when valid-client-id-fn
    (reset! valid-client-id-atom valid-client-id-fn))
  (component/using
    (map->SimpleChannelServer {:handshake-data-fn (or handshake-data-fn (fn [ring-req]
                                                                          (get (:headers ring-req) "Authorization")))
                               :server-adapter    (or server-adapter sente-web-server-adapter)
                               :client-id-fn      (or client-id-fn (fn [request]
                                                                     (:client-id request)))
                               :transit-handlers  transit-handlers})
    (into [] (cond-> []
               dependencies (concat dependencies)))))
