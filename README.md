# untangled-websockets

A set of helpers to use websockets with your untangled applications.

Sente is used to define the websocket layer.

## Usage

### Server

Using untanlged websockets is simple. You first need to create your system:

```clojure
(defn make-system []
  (core/make-untangled-server
    :config-path "config/recipe.edn"
    :parser (om/parser {:read logging-query :mutate logging-mutate})
    :parser-injections #{}
    :components {:channel-server         (cs/make-channel-server)
                 :channel-wrapper        (cw/make-channel-wrapper push-queue)
                 :subscription-container (sc/make-subscription-container)}
    :extra-routes {:routes   ["" {["/chsk"] :web-socket}]
                   :handlers {:web-socket cs/route-handlers}}))
```

Three new components stand out:

1. `:channel-server` - the implementation of the socket server. In our case we are serving websockets with [Sente](https://github.com/ptaoussanis/sente)
2. `:channel-wrapper` - this wraps the channel server and hooks into incoming events, and redirects them to their cooresponding actions.
3. `:subscription-container` - The subscription container holds the link between topics and clients that are subscribed to those topics. This is the main means of directing push events to clients.

There is also a listing for `:extra-routes`. The extra route described is the endpoint for your websocket. This will leave the default "/api" route in tact in case you need to use if for other clients/reasons.

Note about the Subscription Container - This is a component that is easily replacable. The provided container maps topics to a set of user ids (the Sente notion of user id). If you implement another subscription container, it is likely worth looking into overriding the `:push-router-fn`.

In your api you may add something like this:

```clojure
(def push-queue (chan 50))

(defn enqueue-push [topic data & exclusions]
  (put! push-queue {:topic topic :data data :exclusions (set exclusions)}))

(defmulti apimutate om/dispatch)

(defmethod apimutate 'datum/add [{:keys [uid subscription-container] :as env} _ params]
  {:action (fn []
             (swap! db update :data conj params)
             (enqueue-push :app/data-update params uid) ;; Push to topic with data (params) excluding uid
             {})})

(defmethod apimutate 'app/subscribe [{:keys [uid subscription-container] :as env} _ {:keys [topic] :as params}]
  {:action (fn []
             (subscribe subscription-container uid topic))})
```

`push-queue` will get passed to the `:channel-wrapper`, and receive messages.
`enqueue-push` will take a message and put it on the queue to be pushed to subscribers.

If the behavior of the push router needs to be changed, feel free to override it by passing a keyword arg `:push-router-fn` to `make-channel-wrapper.`

Finally you will see a few mutations.  The first is `datum/add`. Here we get an added mutation from one client, and notify (via `enqueue-push`) all the other clients of the updated information. Secondly is `app/subscribe`. This lets a client subscribe to push information.


### Client

In the client we need to override the default networking:

```clojure
(defonce app (atom (uc/new-untangled-client
                     :networking (wn/make-channel-client "/chsk"
                                   :global-error-callback (constantly nil)
                                   :push-queue (chan 20))
                     :initial-state initial-state
                     :started-callback (fn [{:keys [reconciler]}]
                                         (om/transact! reconciler `[(app/subscribe {:topic :app/data-update})]) ;; subscribe at app start.
                                         ))))
```

We override the default network with our own implementation of a network. We hook the client up to use "/chsk" as the route for communicating with the server, we give a callback for global errors, and add a `push-queue`, which will get used by untangled internally to route pushed messages.

In your mutations you might find.

```clojure
(defmethod m/mutate 'datum/add [{:keys [state ast] :as env} _ params]
  {:remote ast
   :action (fn []
             (let [{:keys [db/id]} params
                   ident           [:datum/by-id id]]
               (swap! state assoc-in ident params)
               (swap! state update-in [:data] (fnil conj []) ident)))})

(defmethod m/mutate 'app/subscribe [{:keys [ast]} _ _]
  {:remote ast})
```

The first is straight forward. The second is less obvious. Here we are just sending the message to the server, because we don't need any action on the client.

We will also need to extend the `untangled.client.server-push/push-received` multimethods, for handling pushed messages.

```clojure
(defmethod untangled.client.server-push/push-received :app/data-update [{:keys [reconciler] :as app} {:keys [data]}]
  (let [state (om/app-state reconciler)
        {:keys [db/id]} data
        ident [:datum/by-id id]]
    (swap! state update :data (fnil conj []) ident)
    (swap! state assoc-in ident data)
    (refresh app)))
```

Note that you have access to the entire app here. That is so you can call `(refresh app)`, which will reload your entire app. You could also call `(om.next/merge! (:reconciler app) ...)` or `(om.next/transact! reconciler ...)` alternatively. That part is up to you.

## License

Copyright © 2016 NAVIS

Distributed under the MIT License.
