(ns untangled.client.protocol-support
  (:require
    [clojure.set :as set]
    [untangled-spec.core :refer-macros [specification behavior provided when-mocking component assertions]]
    [om.next :as om :refer-macros [defui]]
    [om.tempid :refer [TempId]]))

(defn is-om-tempid? [val]
  (= TempId (type val)))

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
    (assertions
      new-state => :fix-me)
    (doseq [[key-path value] delta]
      (assertions
        (get-in new-state key-path) => value))))

(defn allocate-tempids [tx]
  (let [allocated-ids (atom #{})]
    (clojure.walk/prewalk
      (fn [v] (when (tempid? v) (swap! allocated-ids conj v)) v)
      tx)
    (into {} (map #(vector % (om/tempid)) @allocated-ids))))

(defn check-optimistic-update
  "`app` is an UntangledApplication
   `data` is a map containing protocoly stuff"
  [{:keys [parser] :as app} {:keys [initial-ui-state ui-tx optimistic-delta] :as data}]
  (component "Optimistic Updates"
    (let [state (atom initial-ui-state)
          parse (partial parser {:state state})
          tempid-map (allocate-tempids ui-tx)
          ui-tx (rewrite-tempids ui-tx tempid-map)]
      (behavior "trigger correct state transitions"
        (parse ui-tx)
        (check-delta (rewrite-tempids @state (set/map-invert tempid-map)
                       is-om-tempid?)
          optimistic-delta)))))

(defn check-server-tx
  "`app` is an UntangledApplication
   `data` is a map containing protocoly stuff"
  [{:keys [parser] :as app} {:keys [initial-ui-state ui-tx server-tx] :as data}]
  (component "Client Remoting"
    (let [state (atom initial-ui-state)
          parse (partial parser {:state state})
          tempid-map (allocate-tempids ui-tx)
          ui-tx (rewrite-tempids ui-tx tempid-map)]
      (assertions "Generates the expected server query"
        (-> (parse ui-tx :remote)
          (rewrite-tempids (set/map-invert tempid-map)
            is-om-tempid?))
        => server-tx))))

(defn check-response
  [{:keys [reconciler] :as app} {:keys [response pre-response-state server-tx merge-delta]}]
  (component "Server response"
    (let [state (om/app-state reconciler)]
      (reset! state pre-response-state)
      (om/merge! reconciler response server-tx)
      (check-delta @state merge-delta))))
