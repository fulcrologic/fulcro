(ns untangled.client.protocol-support
  (:require
    [clojure.set :as set]
    [untangled-spec.core :refer-macros [specification behavior provided when-mocking component assertions]]
    [om.next :as om :refer-macros [defui]]
    [om.dom :as dom]
    [untangled.client.impl.om-plumbing :as plumbing]
    [untangled.client.core :as core]
    [untangled.client.logging :as log]
    [om.tempid :as omt]))

(defn tempid?
  "Is the given keyword a seed data tempid keyword (namespaced to `tempid`)?"
  [kw] (and (keyword? kw) (= "om.tempid" (namespace kw))))

(defn vectors-to-sets
  "Convert nested vectors in the given data into sets (useful for comparison in tests where order should not matter)"
  [data] (clojure.walk/prewalk (fn [v]
                                 (if (and (vector? v) (map? (first v)))
                                   (set v)
                                   v)) data))

(defn rewrite-tempids
  "Rewrite tempid keywords in the given state using the tid->rid map. Leaves the keyword alone if the map
   does not contain an entry for it."
  [state tid->rid & [pred]]
  (clojure.walk/prewalk #(if ((or pred tempid?) %)
                          (get tid->rid % %) %)
    state))

(defn protocol-data
  "Returns a function "
  [& {:keys [query response seed-data]}]
  (fn [tid->rid]
    {:query     (rewrite-tempids query tid->rid)
     :response  (rewrite-tempids response tid->rid)
     :seed-data seed-data}))

(defn check-delta
  "Checks that `new-state` includes the `delta`, where `delta` is a map keyed by data path (as in get-in). The
   values of `delta` are literal values to verify at that path (nil means the path should be missing)."
  [new-state delta]
  (if (empty? delta)
    (throw (ex-info "Cannot have empty :merge-delta"
             {:new-state new-state}))
    (doseq [[key-path value] delta]
      (assertions
        (get-in new-state key-path) => value))))

(defn allocate-tempids [tx]
  (let [allocated-ids (atom #{})]
    (clojure.walk/prewalk
      (fn [v] (when (tempid? v) (swap! allocated-ids conj v)) v)
      tx)
    (into {} (map #(vector % (om/tempid)) @allocated-ids))))

(defui Root
  static om/IQuery (query [this] [:fake])
  Object (render [this] (dom/div nil "if you see this something is wrong")))

(defn- init-testing []
  (-> (core/new-untangled-client :started-callback #() :networking #())
    (core/mount Root "invisible-specs")))

;;; Public API

(defn check-optimistic-update
  "Takes a map containing:
  `initial-ui-state`: denormalized app state prior to the optimistic update for transactions going to the server
  `ui-tx`: the om transaction that modifies the app state prior to receiving a server response
  `optimistic-delta`: the expected changes to the app state after executing ui-tx. See Protocol Testing README for how
  to build this properly."
  [{:keys [initial-ui-state ui-tx optimistic-delta] :as data}]
  (component "Optimistic Updates"
    (let [{:keys [parser]} (init-testing)
          state (atom initial-ui-state)
          parse (partial parser {:state state})
          tempid-map (allocate-tempids ui-tx)
          ui-tx (rewrite-tempids ui-tx tempid-map)]
      (behavior "trigger correct state transitions"
        (parse ui-tx)
        (check-delta (rewrite-tempids @state (set/map-invert tempid-map)
                       omt/tempid?)
          optimistic-delta)))))

(defn check-server-tx
  "Takes a map containing:
  `initial-ui-state`: denormalized app state prior to sending the server transaction
  `ui-tx`: the om transaction that modifies the app state locally
  `server-tx`: the server transaction corresponding to ui-tx"
  [{:keys [initial-ui-state ui-tx server-tx] :as data}]
  (component "Client Remoting"
    (let [{:keys [parser]} (init-testing)
          state (atom initial-ui-state)
          parse (partial parser {:state state})
          tempid-map (allocate-tempids ui-tx)
          ui-tx (rewrite-tempids ui-tx tempid-map)]
      (assertions "Generates the expected server query"
        (-> (parse ui-tx :remote)
          plumbing/filter-loads-and-fallbacks
          plumbing/strip-ui
          (rewrite-tempids (set/map-invert tempid-map)
            omt/tempid?))
        => server-tx))))

(defn check-response-from-server
  "Takes a map containing:
  `response`: the exact data the server sends back to the client
  `pre-response-state`: normalized state prior to receiving `response`
  `server-tx`: the transaction originally sent to the server, yielding `response`
  `merge-delta`: the delta between `pre-response-state` and its integration with `response`"
  [{:keys [response pre-response-state server-tx merge-delta]}]
  (component "Server response"
    (let [{:keys [reconciler]} (init-testing)
          state (om/app-state reconciler)]
      (reset! state pre-response-state)
      (om/merge! reconciler response server-tx)
      (if merge-delta
        (check-delta @state merge-delta)
        (assertions
          @state => pre-response-state)))))
