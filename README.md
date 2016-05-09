# Untangled Websockets

A set of helpers to use websockets with your untangled applications.

Sente is used to define the websocket layer.

## Usage

#### Server

Using untanlged websockets is simple. You first need to create your system:

```clojure
(defn make-system []
  (core/make-untangled-server
    :config-path "config/app.edn"
    :parser (om/parser {:read logging-query :mutate logging-mutate})
    :parser-injections #{}
    :components {:channel-server  (make-channel-server)
                 :channel-wrapper (make-channel-wrapper)} ;; THIS PART IS IMPLEMENTED BY YOU!
    :extra-routes {:routes   ["" {["/chsk"] :web-socket}]
                   :handlers {:web-socket cs/route-handlers}}))
```

Two new components stand out:

1. `:channel-server` - the implementation of the socket server. In our case we are serving websockets with [Sente](https://github.com/ptaoussanis/sente)
2. `:channel-wrapper` - this wraps the channel server and hooks into incoming events, and redirects them to their corresponding actions. NOTE: You implement this as a component that depends on `:channel-server`

There is also a listing for `:extra-routes`. The extra route described is the endpoint for your websocket. This will leave the default "/api" route in tact in case you need to use if for other clients/reasons.

In your api you may add something like this:

```clojure
(require '[untangled.websockets.protocols :refer [push]])

(defn notify-others [ws-net cid verb edn]
  (let [clients (:any @(:connected-uids ws-net))]
    (doall (map (fn [id]
                  (push ws-net id verb edn)) ;; Use the push protocol function on the ws-net to send to clients.
                (disj clients cid)))))

(defmulti apimutate om/dispatch)

(defmethod apimutate 'datum/add [{:keys [ws-net cid] :as env} _ params] ;; ws-net is the protocol defined in untangled-websockets and it is added to the environment for use by mutations and components.
  {:action (fn []
             (swap! db update :data conj params)
             (notify-others ws-net cid :app/data-update params) ;; Push to topic with data (params) excluding cid
             {})})
```

#### Client

In the client we need to override the default networking:

```clojure
(defonce app (atom (uc/new-untangled-client
                     :networking (wn/make-channel-client "/chsk" :global-error-callback (constantly nil))
                     :initial-state initial-state
                     :started-callback (fn [{:keys [reconciler]}]
                                         ;; You may want to put some code here to run on startup.
                                         ))))
```

We override the default network with our own implementation of a network. We hook the client up to use "/chsk" as the route for communicating with the server, and we give a callback for global errors.

We will also need to extend the `untangled.websockets.networking/push-received` multimethods, for handling pushed messages.

```clojure
(require '[untangled.websockets.networking :as un])

(defmethod un/push-received :app/data-update [{:keys [reconciler] :as app} {:keys [data]}]
  (let [state (om/app-state reconciler)
        {:keys [db/id]} data
        ident [:datum/by-id id]]
    (swap! state update :data (fnil conj []) ident)
    (swap! state assoc-in ident data)
    (refresh app)))
```

Note that you have access to the entire app here. That is so you can call `(refresh app)`, which will reload your entire app. You could also call `(om.next/merge! (:reconciler app) ...)` or `(om.next/transact! reconciler ...)` alternatively. That part is up to you.


Check out the [Untangled Cookbook](https://github.com/untangled-web/untangled-cookbook) for an example usage. Feel free to ping the `untangled` on clojurians slack for help.

## License

Copyright © 2016 NAVIS

Distributed under the MIT License.
