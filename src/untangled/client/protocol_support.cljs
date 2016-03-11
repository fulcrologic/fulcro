(ns untangled.client.protocol-support
  (:require
    [clojure.set :as set]
    [untangled-spec.core :refer-macros [specification behavior provided when-mocking component assertions]]
    [om.next :as om :refer-macros [defui]]
    [untangled.client.impl.om-plumbing :as plumbing]
    [om.tempid :as omt]
    [untangled.client.impl.protocol-support :as impl]))

(defn check-optimistic-update
  "Takes a map containing:
  `initial-ui-state`: denormalized app state prior to the optimistic update for transactions going to the server
  `ui-tx`: the om transaction that modifies the app state prior to receiving a server response
  `optimistic-delta`: the expected changes to the app state after executing ui-tx. See Protocol Testing README for how
  to build this properly."
  [{:keys [initial-ui-state ui-tx optimistic-delta] :as data}]
  (component "Optimistic Updates"
    (let [{:keys [parser]} (impl/init-testing)
          state (atom initial-ui-state)
          parse (partial parser {:state state})
          tempid-map (impl/allocate-tempids ui-tx)
          ui-tx (impl/rewrite-tempids ui-tx tempid-map)]
      (behavior "trigger correct state transitions"
        (parse ui-tx)
        (impl/check-delta (impl/rewrite-tempids @state (set/map-invert tempid-map)
                       omt/tempid?)
          optimistic-delta)))))

(defn check-server-tx
  "Takes a map containing:
  `initial-ui-state`: denormalized app state prior to sending the server transaction
  `ui-tx`: the om transaction that modifies the app state locally
  `server-tx`: the server transaction corresponding to ui-tx"
  [{:keys [initial-ui-state ui-tx server-tx] :as data}]
  (component "Client Remoting"
    (let [{:keys [parser]} (impl/init-testing)
          state (atom initial-ui-state)
          parse (partial parser {:state state})
          tempid-map (impl/allocate-tempids ui-tx)
          ui-tx (impl/rewrite-tempids ui-tx tempid-map)]
      (assertions "Generates the expected server query"
        (-> (parse ui-tx :remote)
          plumbing/remove-loads-and-fallbacks
          plumbing/strip-ui
          (impl/rewrite-tempids (set/map-invert tempid-map)
            omt/tempid?))
        => server-tx))))

(defn check-response-from-server
  "Takes a map containing:
  `response`: the exact data the server sends back to the client
  `pre-response-state`: normalized state prior to receiving `response`
  `server-tx`: the transaction originally sent to the server, yielding `response`
  `merge-delta`: the delta between `pre-response-state` and its integration with `response`"
  [{:keys [response pre-response-state ui-tx merge-delta]}]
  (component "Server response merged with app state"
    (let [{:keys [reconciler]} (impl/init-testing)
          state (om/app-state reconciler)]
      (reset! state pre-response-state)
      (om/merge! reconciler response ui-tx)
      (if merge-delta
        (impl/check-delta @state merge-delta)
        (assertions
          @state => pre-response-state)))))

(def with-behavior impl/with-behavior)
