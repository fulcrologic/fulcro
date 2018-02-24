(ns fulcro.websockets
  (:require
    [fulcro.websockets.protocols :as wp :refer [WSListener WSNet add-listener remove-listener client-added client-dropped]]
    [taoensso.sente.server-adapters.http-kit :as hk]
    [com.stuartsierra.component :as component]
    [taoensso.sente :as sente]
    [fulcro.websockets.transit-packer :as tp]
    [ring.middleware.params :refer [params-request]]
    [ring.middleware.keyword-params :refer [keyword-params-request]]
    [fulcro.server :as server]
    [fulcro.logging :as log]
    [fulcro.easy-server :as easy]))

(defn sente-event-handler
  "A sente event handler that connects the websockets support up to the parser via the
  :fulcro.client/API event, and also handles notifying listeners that clients connected and dropped."
  [{:keys [send-fn listeners parser] :as websockets} event]
  (let [env (merge {:push          send-fn
                    :websockets    websockets
                    :cid           (:client-id event)       ; legacy. might be removed
                    :user-id       (:uid event)
                    :request       (:ring-req event)        ; legacy. might be removed
                    :sente-message event}
              (dissoc websockets :server-options :ring-ajax-get-or-ws-handshake :ring-ajax-post
                :ch-recv :send-fn :stop-fn :listeners))
        {:keys [?reply-fn id uid ?data]} event]
    (case id
      :chsk/uidport-open (doseq [^WSListener l @listeners]
                           (log/debug (str "Notifying listener that client " uid " connected"))
                           (client-added l websockets uid))
      :chsk/uidport-close (doseq [^WSListener l @listeners]
                            (log/debug (str "Notifying listener that client " uid " disconnected"))
                            (client-dropped l websockets uid))
      :fulcro.client/API (let [result (server/handle-api-request parser env ?data)]
                           (if ?reply-fn
                             (?reply-fn result)
                             (log/error "Reply function missing on API call!")))
      (do :nothing-by-default))))

(defn- is-wsrequest? [{:keys [websockets-uri]} {:keys [uri]}]
  (= websockets-uri uri))

(defrecord EasyServerAdapter [handler websockets]
  component/Lifecycle
  (start [this]
    (if (or (nil? handler) (nil? websockets))
      (log/fatal "Cannot adapt websockets to easy server. :handler or :websockets component it missing!")
      (let [old-pre-hook (easy/get-pre-hook handler)
            new-hook     (fn [ring-handler]
                           (let [base-request-handler (old-pre-hook ring-handler)]
                             (fn [{:keys [request-method] :as req}]
                               (if (is-wsrequest? websockets req)
                                 (let [request (-> req params-request keyword-params-request)
                                       {:keys [ring-ajax-post ring-ajax-get-or-ws-handshake]} websockets]
                                   (case request-method
                                     :get (ring-ajax-get-or-ws-handshake request)
                                     :post (ring-ajax-post request)))
                                 (base-request-handler req)))))]
        (log/info "Adding websockets into easy server middleware.")
        (easy/set-pre-hook! handler new-hook)))
    this)
  (stop [this]
    this))

(defn make-easy-server-adapter
  "Creates a component that relies on :handler and :websockets. You must install Websockets as :websockets in your components.

  This will inject the proper Ring handlers into the easy server. See wrap-api for a function that you can
  use in a custom server."
  []
  (component/using
    (map->EasyServerAdapter {})
    [:handler :websockets]))

(defrecord Websockets [parser server-adapter server-options transit-handlers
                       ring-ajax-post ring-ajax-get-or-ws-handshake websockets-uri
                       ch-recv send-fn connected-uids stop-fn listeners]
  WSNet
  (add-listener [this listener]
    (log/info "Adding channel listener to websockets")
    (swap! listeners conj listener))
  (remove-listener [this listener]
    (log/info "Removing channel listener from websockets")
    (swap! listeners disj listener))
  (push [this cid verb edn]
    (send-fn cid [:api/server-push {:topic verb :msg edn}]))

  component/Lifecycle
  (start [this]
    (log/info "Starting Sente websockets support")
    (let [transit-handlers (or transit-handlers {})
          chsk-server      (sente/make-channel-socket-server!
                             server-adapter (merge {:packer (tp/make-packer transit-handlers)}
                                              server-options))
          {:keys [ch-recv send-fn connected-uids
                  ajax-post-fn ajax-get-or-ws-handshake-fn]} chsk-server
          result           (assoc this
                             :ring-ajax-post ajax-post-fn
                             :ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn
                             :ch-rech ch-recv
                             :send-fn send-fn
                             :listeners (atom #{})
                             :connected-uids connected-uids)
          stop             (sente/start-server-chsk-router! ch-recv (partial sente-event-handler result))]
      (log/info "Started Sente websockets event loop.")
      (assoc result :stop-fn stop)))
  (stop [this]
    (when stop-fn
      (log/info "Stopping websockets.")
      (stop-fn))
    (log/info "Stopped websockets.")
    (assoc this :stop-fn nil :ch-recv nil :send-fn nil)))

(defn make-websockets
  "Build a web sockets component with the given API parser and sente socket server options (see sente docs).
  NOTE: If you supply a packer, you'll need to make sure tempids are supported (this is done by default, but if you override it, it is up to you.
  The default user id mapping is to use the internally generated UUID of the client. Use sente's `:user-id-fn` option
  to override this.

  Anything injected as a dependency of this component is added to your parser environment (in addition to the parser
  itself).

  Thus, if you'd like some other component (like a database) to be there, simply do this:

  (component/using (make-websockets parser {})
    [:sql-database :sessions])

  and when the system starts it will inject those components into this one, and this one will be your parser env.

  Additionally, the parser environment will include:
    :websockets The channel server component itself
    :push           A function that can send push messages to any connected client of this server. (just a shortcut to send-fn in websockets)
    :parser         The parser you gave this function
    :sente-message  The raw sente event.

  The websockets component must be joined into a real network server via a ring stack. This implementation assumes http-kit.
  The `wrap-api` function can be used to do that.

  All of the options in the options map are optional.

  If you don't supply a server adapter, it defaults to http-kit.
  If you don't supply websockets-uri, it defaults to \"/chsk\".
  "
  [parser {:keys [websockets-uri http-server-adapter transit-handlers sente-options]}]
  (map->Websockets {:server-options   (merge {:user-id-fn (fn [r] (:client-id r))} sente-options)
                    :transit-handlers (or transit-handlers {})
                    :websockets-uri   (or websockets-uri "/chsk")
                    :server-adapter   (or http-server-adapter (hk/get-sch-adapter))
                    :parser           parser}))

(defn wrap-api
  "Add API support to a Ring middleware chain. The websockets argument is an initialized Websockets component. Basically
  inject websockets into the component where you define your middleware, and (-> handler ... (wrap-api websockets) ...).

  NOTE: You must have wrap-keyword-params and wrap-params in the middleware chain!"
  [handler websockets]
  (let [{:keys [ring-ajax-post ring-ajax-get-or-ws-handshake websockets-uri]} websockets]
    (fn [{:keys [request-method uri] :as req}]
      (let [is-ws? (= websockets-uri uri)]
        (if is-ws?
          (case request-method
            :get (ring-ajax-get-or-ws-handshake req)
            :post (ring-ajax-post req))
          (handler req))))))
