# Websockets

A set of helpers to use websockets with your applications.

Sente is used to define the websocket layer.

## Usage

#### Server

There are two protocols on the server side:

```clojure
(defprotocol WSNet
  (add-listener [this ^WSListener listener]
    "Add a `WSListen` listener")
  (remove-listener [this ^WSListener listener]
    "Remove a `WSListen` listener")
  (push [this cid verb edn]
    "Push from server"))

(defprotocol WSListener
  (client-added [this ws-net cid]
    "Listener for dealing with client added events.")
  (client-dropped [this ws-net cid]
    "listener for dealing with client dropped events."))
```

`WSNet` is implemented by the `ChannelServer`.

`WSListener` is for you to implement. It allows for listening to client added and dropped (closed) actions. An example might look like this.

```clojure
(require '[fulcro.websockets.components.channel-server :as cs])
(require '[app.api :as api]) ;; see below.

(defrecord ChannelListener [channel-server]
  cs/WSListener
  (client-dropped [this ws-net cid]
    (remove-user-to-db cid)
    (api/notify-others ws-net cid :user/left {:user cid}))
  (client-added [this ws-net cid]
    (add-user cid)
    (api/notify-others ws-net cid :user/entered {:user cid}))

  component/Lifecycle
  (start [component]
    (cs/add-listener channel-server component)
    component))
  (stop [component]
    (cs/remove-listener channel-server component)
    component))

(defn make-channel-listener []
  (component/using
    (map->ChannelWrapper {})
    [:channel-server]))
```

Hooking Fulcro websockets into the system is simple. We will add the `ChannelServer` component and the `ChannelListener` component from above, and then define the route:

```clojure
(require '[fulcro.websockets.components.channel-server :as cs])

(defn make-system []
  (core/make-fulcro-server
    :config-path "config/app.edn"
    :parser (om/parser {:read logging-query :mutate logging-mutate})
    :parser-injections #{}
    :components {:channel-server  (cs/make-channel-server)
                 :channel-wrapper (make-channel-listener)}
    :extra-routes {:routes   ["" {["/chsk"] :web-socket}]
                   :handlers {:web-socket cs/route-handlers}}))
```

The listing for `:extra-routes` is the endpoint for your websocket. `route-handlers` is defined in `fulcro.websockets.components.channel-server`. This will leave the default "/api" route in tact in case you need to use if for other clients/reasons.

In your api you may add something like this:

```clojure
(require '[fulcro.websockets.protocols :refer [push]])

(defn notify-others [ws-net cid verb edn]
  (let [clients (:any @(:connected-uids ws-net))]
    (doall (map (fn [id]
                  (push ws-net id verb edn)) ;; Use the push protocol function on the ws-net to send to clients.
                (disj clients cid)))))

(defmulti apimutate om/dispatch)

(defmethod apimutate 'datum/add [{:keys [ws-net cid] :as env} _ params] ;; ws-net is the protocol defined in fulcro-websockets and it is added to the environment for use by mutations and components.
  {:action (fn []
             (swap! db update :data conj params)
             (notify-others ws-net cid :app/data-update params) ;; Push to topic with data (params) excluding cid
             {})})
```

#### Client

In the client we need to override the default networking:

```clojure
(def cs-net (wn/make-channel-client "/chsk" :global-error-callback (constantly nil)))

(defonce app (atom (fc/new-fulcro-client
                     :networking cs-net
                     :initial-state initial-state
                     ; IMPORTANT: install the push handlers once started! push-received won't work unless you do this.
                     :started-callback (fn [app] (wn/install-push-handlers cs-net app)))))
```

We override the default network with our own implementation of a network. We hook the client up to use "/chsk" as the route for communicating with the server, and we give a callback for global errors.

We will also need to extend the `fulcro.websockets.networking/push-received` multimethods, for handling pushed messages.

```clojure
(require '[fulcro.websockets.networking :as fwn])

(defmethod fwn/push-received :app/data-update [{:keys [reconciler] :as app} {:keys [topic msg] :as message}] ;; message => {:topic verb :msg edn}
  (om/transact! reconciler `[(do.with/data {:data msg}) :data]))
```

Note that you have access to the reconciler (for that matter the entire app) here. You can call `(fulcro.client.primitives/merge! reconciler ...)` or `(fulcro.client.primitives/transact! reconciler ...)` alternatively. That part is up to you.


Check out the demos in [Fulcro](https://github.com/fulcrologic/fulcro) for an example usage. Feel free to ping the `fulcro` on clojurians slack for help.

## License

Copyright © 2016 NAVIS
Copyright © 2017 Fulcrologic

Distributed under the MIT License.
