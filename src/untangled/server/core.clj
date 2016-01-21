(ns untangled.server.core
  (:require [datomic.api :as d]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutation Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn arg-assertion [mutation & args]
  "The function will throw an assertion error if any args are nil."
  (assert (every? (comp not nil?) args) (str "All parameters to " mutation " mutation must be provided.")))

(defn retract-datomic-entity [connection entity-id] @(d/transact connection [[:db.fn/retractEntity entity-id]]))

(defn transitive-join
  "Takes a map from a->b and a map from b->c and returns a map a->c."
  [a->b b->c]
  (reduce (fn [result k] (assoc result k (->> k (get a->b) (get b->c)))) {} (keys a->b)))

(defn resolve-ids [new-db omids->tempids tempids->realids]
  (reduce
    (fn [acc [cid dtmpid]]
      (assoc acc cid (d/resolve-tempid new-db tempids->realids dtmpid)))
    {}
    omids->tempids))

(defn replace-ref-types [dbc refs m]
  "@dbc   the database to query
   @refs  a set of keywords that ref datomic entities, which you want to access directly
          (rather than retrieving the entity id)
   @m     map returned from datomic pull containing the entity IDs you want to deref"
  (clojure.walk/postwalk
    (fn [arg]
      (if (and (coll? arg) (refs (first arg)))
        (update-in arg [1] (comp :db/ident (partial d/entity dbc) :db/id))
        arg))
    m))

(defn query-pull
  "Given a datomic-pull query and connection `conn`, returns the query response from all entities
   containing `db-attr` as an attribute. If `ref-set` is provided, query-pull will pull each entity-id
   in the query response that is joined to the attributes specified in `ref-set`. The entity id is then
   replaced with the pulled data.

   e.g. (query-pull {:thing {:ref [:foo/bar]}} conn :thing) -> {:thing {:ref {:foo/bar {:db/id 1234567}}}
          vs.
        (query-pull {:thing {:ref [:foo/bar]}} conn :thing #{:foo/bar}) -> {:thing {:ref {:foo/bar {:referenced :data}}}}

   @{required} query    a datomic query
   @{required} conn     connection to a datomic db
   @{required} db-attr  attribute used to collect the entities to which the query is applied
   @{optional} ref-set  attributes of type `ref` that you want to be dereferenced in the query response"

  ([query conn db-attr & {:keys [ref-set] :or {ref-set #{}}}]
   (let [db (d/db conn)
         initial-result (vec (flatten (d/q `[:find (~'pull ?e ~query) :where [?e ~db-attr]] db)))
         response (if (nil? ref-set) initial-result (replace-ref-types db ref-set initial-result))]

     {:value response})))

(defn datomicid->tempid [m x]
  (let [inverter (clojure.set/map-invert m)]
    (clojure.walk/postwalk
      #(if-let [tid (get inverter %)]
        tid %)
      x)))
