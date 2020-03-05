(ns com.fulcrologic.fulcro.algorithms.denormalize
  "The algorithm and support functions for converting a normalized Fulcro database to a tree of denormalized props."
  (:require
    [edn-query-language.core :as eql]))

(def ^:dynamic *denormalize-time* 0)

(defn link-ref?
  "Is the given `v` a link ref query (e.g. `[:table '_]) element."
  [v]
  (and
    (vector? v)
    (= 2 (count v))
    (keyword? (first v))
    (= '_ (second v))))

(defn lookup-ref?
  "Is the given `v` a lookup ref query (i.e. ident)?"
  [v]
  (and (vector? v) (= 2 (count v)) (keyword? (first v))))

(defn follow-ref
  "Returns the value defined by the `ref` from `state-map`.  Works for link refs and
  lookup refs."
  [state-map [table id :as ref]]
  (if (= '_ id)
    (get state-map table)
    (get-in state-map ref)))

(defn ref-key
  "Returns the key to use in results for the given ref (ident of lookup ref). For link refs this is just
  the first element, and for idents it is the ident."
  [[table id :as ref]]
  (if (= '_ id)
    table
    ref))

(declare denormalize)

(defn with-time
  "Associates time metadata with the given props. This time can be used by rendering optimizations to decide when
  stale props are passed to it from a parent in cases where props tunnelling was used for localized refresh."
  [props t]
  (vary-meta props assoc ::time t))

(defn- add-props!
  "Walk the given AST children (which MUST be prop nodes), and add their values from `current-entity`
  (if found)."
  [transient-node entity ast-prop-children state-map]
  (reduce
    (fn [n {:keys [key]}]
      (if (lookup-ref? key)
        (if-let [x (follow-ref state-map key)]
          (assoc! n (ref-key key) x)
          n)
        (if-let [entry (and (coll? entity) (find entity key))]
          (assoc! n key (second entry))
          n)))
    transient-node
    ast-prop-children))

(defn- reduce-depth
  "Reduce the query depth on `join-node` that appears within the children of `parent-node`."
  [parent-node join-node]
  (let [join-node-index (reduce
                          (fn [idx n] (if (identical? join-node n)
                                        (reduced idx)
                                        (inc idx)))
                          0
                          (:children parent-node))]
    (update-in parent-node [:children join-node-index :query] (fnil dec 1))))

(defn- add-join! [n {:keys [query key] :as join-node} entity state-map parent-node idents-seen]
  (let [link-join?      (lookup-ref? key)
        v               (if link-join? (follow-ref state-map key) (get entity key))
        key             (if (link-ref? key) (first key) key)
        is-ref?         (lookup-ref? v)
        join-entity     (if is-ref? (follow-ref state-map v) v)
        to-many?        (and (not is-ref?) (vector? join-entity))
        depth-based?    (int? query)
        recursive?      (or depth-based? (= '... query))
        stop-recursion? (and recursive? (or (= 0 query)
                                          (and is-ref?
                                            ;; NOTE: allows depth-based to ignore loops
                                            (not depth-based?)
                                            (contains? (get idents-seen key) v))))
        parent-node     (if (and depth-based? (not stop-recursion?))
                          (reduce-depth parent-node join-node)
                          parent-node)
        target-node     (if recursive? parent-node join-node)
        ;; NOTE: fixed bug with old db->tree, so behavior is different
        idents-seen     (if is-ref?
                          (update idents-seen key (fnil conj #{}) v)
                          idents-seen)]
    (cond
      stop-recursion? n
      to-many? (assoc! n key
                 (into []
                   (keep (fn [x]
                           (let [e (if (lookup-ref? x)
                                     (follow-ref state-map x)
                                     x)]
                             (denormalize target-node e state-map idents-seen))))
                   join-entity))
      (and recursive? join-entity) (if depth-based?
                                     (let [join-node-index (reduce
                                                             (fn [idx n] (if (identical? join-node n)
                                                                           (reduced idx)
                                                                           (inc idx)))
                                                             0
                                                             (:children parent-node))
                                           parent-node     (update-in parent-node [:children join-node-index :query] (fnil dec 1))]
                                       (assoc! n key (denormalize parent-node join-entity state-map idents-seen)))
                                     (assoc! n key (denormalize parent-node join-entity state-map idents-seen)))
      (map? join-entity) (assoc! n key (denormalize target-node join-entity state-map idents-seen))
      (and (contains? entity key)
        (not recursive?)
        (not link-join?)) (assoc! n key v)
      :otherwise n)))

(defn- add-union! [n {:keys [key] :as join-node} entity state-map idents-seen]
  (let [link-join?       (lookup-ref? key)
        v                (if link-join? key (get entity key))
        union-node       (-> join-node :children first)
        union-key->query (reduce
                           (fn [result {:keys [union-key] :as node}]
                             (assoc result union-key node))
                           {}
                           (:children union-node))
        is-ref?          (lookup-ref? v)
        to-many?         (and (not is-ref?) (vector? v))]
    (cond
      to-many? (assoc! n key
                 (into []
                   (keep (fn [lookup-ref]
                           (if-let [e (and (lookup-ref? lookup-ref)
                                        (follow-ref state-map lookup-ref))]
                             (let [[table] lookup-ref]
                               (if-let [target-ast-node (union-key->query table)]
                                 (denormalize target-ast-node e state-map idents-seen)
                                 {}))
                             {})))
                   v))
      is-ref? (if-let [e (follow-ref state-map v)]
                (if-let [target-ast-node (union-key->query (first v))]
                  (assoc! n key (denormalize target-ast-node e state-map idents-seen))
                  (assoc! n key {}))
                n)
      (and (contains? entity key)
        (not link-join?)) (assoc! n key v)
      :otherwise n)))

(defn- add-joins! [transient-node entity state-map parent-node ast-join-nodes idents-seen]
  (reduce
    (fn [n join-node]
      (let [union? (map? (:query join-node))]
        (if union?
          (add-union! n join-node entity state-map idents-seen)
          (add-join! n join-node entity state-map parent-node idents-seen))))
    transient-node
    ast-join-nodes))

(defn denormalize
  "Internal implementation of `db->tree`.  You should normally use `db->tree` instead of this function.

  - `top-node`: an AST for the query.
  - `current-entity`: The entity to start denormalization from.
  - `state-map`: a normalized database.
  - `idents-seen`: a map of the idents seen so far (for recursion loop tracking)."
  [{:keys [type children] :as top-node} current-entity state-map idents-seen]
  (assert (not= type :prop))
  (let [current-entity   (if (lookup-ref? current-entity)
                           (follow-ref state-map current-entity)
                           current-entity)
        grouped-children (group-by :type children)
        nil-nodes        (get grouped-children nil false)
        ;; NOTE: wildcard works better than the old db->tree (which ignores wildcard when joins are present)
        wildcard?        (and nil-nodes (= '* (some-> nil-nodes first :key)))
        result-node      (add-props! (transient (if wildcard? current-entity {})) current-entity (:prop grouped-children) state-map)
        result-node      (add-joins! result-node current-entity state-map
                           top-node
                           (:join grouped-children)
                           idents-seen)]
    (some-> result-node (persistent!) (with-time *denormalize-time*))))

(defn db->tree
  "Pull a tree of data from a fulcro normalized database as a tree corresponding to the given query.

  query - EQL.
  starting-entity - A map of data or ident at which to start.
  state-map - The overall normalized database from which idents can be resolved.

  Returns a tree of data where each resolved data node is also marked with the current
  *denormalize-time* (dynamically bound outside of this call). Users of this function that
  are hydrating the UI should ensure that this time is bound to Fulcro's current internal
  basis-time using `binding`.

  The `state-map` needs to be your entire Fulcro database. This database is used to resolve the joins in the EQL query
  (which are represented as `idents`).

  The starting entity can be `state-map` as well if your EQL query starts from your root. If not, it can simply be
  the map (taken from the `state-map`) of the entity whose query you're using.

  For example:

  ```
  (defsc SomeComponent [this props]
    {:ident :thing/id
     :query [...]})

  ;; Get the sub-tree of data for thing 1:
  (db->tree
    (comp/get-query SomeComponent)
    (get-in state-map [:thing/id 1])
    state-map)
  ```
  "
  [query starting-entity state-map]
  (let [ast (eql/query->ast query)]
    (some-> (denormalize ast starting-entity state-map {})
      (with-time *denormalize-time*))))

(defn denormalization-time
  "Gets the time at which the given props were processed by `db->tree`, if known."
  [props]
  (some-> props meta ::time))
