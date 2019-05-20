(ns com.fulcrologic.fulcro.algorithms.merge
  (:require
    [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.helpers :as util]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(defn remove-ident*
  "Removes an ident, if it exists, from a list of idents in app state. This
  function is safe to use within mutations."
  [state-map ident path-to-idents]
  {:pre [(map? state-map)]}
  (let [new-list (fn [old-list]
                   (vec (filter #(not= ident %) old-list)))]
    (update-in state-map path-to-idents new-list)))

(defn integrate-ident*
  "Integrate an ident into any number of places in the app state. This function is safe to use within mutation
  implementations as a general helper function.

  The named parameters can be specified any number of times. They are:

  - append:  A vector (path) to a list in your app state where this new object's ident should be appended. Will not append
  the ident if that ident is already in the list.
  - prepend: A vector (path) to a list in your app state where this new object's ident should be prepended. Will not append
  the ident if that ident is already in the list.
  - replace: A vector (path) to a specific location in app-state where this object's ident should be placed. Can target a to-one or to-many.
   If the target is a vector element then that element must already exist in the vector."
  [state ident & named-parameters]
  (let [actions (partition 2 named-parameters)]
    (reduce (fn [state [command data-path]]
              (let [already-has-ident-at-path? (fn [data-path] (some #(= % ident) (get-in state data-path)))]
                (case command
                  :prepend (if (already-has-ident-at-path? data-path)
                             state
                             (update-in state data-path #(into [ident] %)))
                  :append (if (already-has-ident-at-path? data-path)
                            state
                            (update-in state data-path (fnil conj []) ident))
                  :replace (let [path-to-vector (butlast data-path)
                                 to-many?       (and (seq path-to-vector) (vector? (get-in state path-to-vector)))
                                 index          (last data-path)
                                 vector         (get-in state path-to-vector)]
                             (assert (vector? data-path) (str "Replacement path must be a vector. You passed: " data-path))
                             (when to-many?
                               (do
                                 (assert (vector? vector) "Path for replacement must be a vector")
                                 (assert (number? index) "Path for replacement must end in a vector index")
                                 (assert (contains? vector index) (str "Target vector for replacement does not have an item at index " index))))
                             (assoc-in state data-path ident))
                  (throw (ex-info "Unknown post-op to merge-state!: " {:command command :arg data-path})))))
      state actions)))

(defn- is-ui-query-fragment?
  "Check the given keyword to see if it is in the :ui namespace."
  [kw]
  (let [kw (if (map? kw) (-> kw keys first) kw)]
    (when (keyword? kw) (some->> kw namespace (re-find #"^ui(?:\.|$)")))))

(defn nilify-not-found
  "Given x, return x value unless it's ::prim/not-found, in which case it returns nil.

  This is useful when you wanna do a nil check but you are in a position where the value
  could be ::prim/not-found (and you want to consider it as nil). A common pattern
  looks like: `(or (prim/nilify-not-found x) 10)"
  [x]
  (if (= x ::not-found) nil x))

(defn as-leaf
  "Returns data with meta-data marking it as a leaf in the result."
  [data]
  (if (coll? data)
    (with-meta data {:fulcro/leaf true})
    data))

(defn leaf?
  "Returns true iff the given data is marked as a leaf in the result (according to the query). Requires pre-marking."
  [data]
  (or
    (not (coll? data))
    (empty? data)
    (and (coll? data)
      (-> data meta :fulcro/leaf boolean))))

(defn union->query
  "Turn a union query into a query that attempts to encompass all possible things that might be queried"
  [union-query]
  (->> union-query vals flatten set vec))

(defn mark-missing
  "Recursively walk the query and response marking anything that was *asked for* in the query but is *not* in the response as missing.
  The merge process (which happens later in the plumbing) looks for these markers as indicators to remove any existing
  data in the database (which has provably disappeared).

  The naive approach to data merging (even recursive) would fail to remove such data.

  Returns the result with missing markers in place (which are then used/removed in a later stage)."
  [result query]
  (let [missing-entity {:ui/fetch-state {:fulcro.client.impl.data-fetch/type :not-found}}]
    (reduce (fn [result element]
              (let [element      (cond
                                   (list? element) (first element)
                                   :else element)
                    result-key   (cond
                                   (keyword? element) element
                                   (util/join? element) (util/join-key element)
                                   :else nil)
                    result-value (get result result-key)]
                (cond
                  (or (and (eql/ident? result-key) (= '_ (second result-key)))
                    (and (eql/ident? element) (= '_ (second element))))
                  result

                  (is-ui-query-fragment? result-key)
                  result

                  ; plain missing prop
                  (and (keyword? element) (nil? (get result element)))
                  (assoc result element ::not-found)

                  ; recursion
                  (and (util/join? element) (or (number? (util/join-value element)) (= '... (util/join-value element))))
                  (let [k       (util/join-key element)
                        result' (get result k)]
                    (cond
                      (nil? result') (assoc result k ::not-found) ; TODO: Is this right? Or, should it just be `result`?
                      (vector? result') (assoc result k (mapv (fn [item] (mark-missing item query)) result'))
                      :otherwise (assoc result k (mark-missing result' query))))

                  ; pure ident query
                  (and (eql/ident? element) (nil? (get result element)))
                  (assoc result element missing-entity)

                  ; union (a join with a map as a target query)
                  (util/union? element)
                  (let [v          (get result result-key ::not-found)
                        to-one?    (map? v)
                        to-many?   (vector? v)
                        wide-query (union->query (util/join-value element))]
                    (cond
                      to-one? (assoc result result-key (mark-missing v wide-query))
                      to-many? (assoc result result-key (mapv (fn [i] (mark-missing i wide-query)) v))
                      (= ::not-found v) (assoc result result-key ::not-found)
                      :else result))

                  ; ident-based join to nothing (removing table entries)
                  (and (util/join? element) (eql/ident? (util/join-key element)) (nil? (get result (util/join-key element))))
                  (let [mock-missing-object (mark-missing {} (util/join-value element))]
                    (assoc result (util/join-key element) (merge mock-missing-object missing-entity)))

                  ; join to nothing
                  (and (util/join? element) (= ::not-found (get result (util/join-key element) ::not-found)))
                  (assoc result (util/join-key element) ::not-found)

                  ; to-many join
                  (and (util/join? element) (vector? (get result (util/join-key element))))
                  (assoc result (util/join-key element) (mapv (fn [item] (mark-missing item (util/join-value element))) (get result (util/join-key element))))

                  ; to-one join
                  (and (util/join? element) (map? (get result (util/join-key element))))
                  (assoc result (util/join-key element) (mark-missing (get result (util/join-key element)) (util/join-value element)))

                  ; join, but with a broken result (scalar instead of a map or vector)
                  (and (util/join? element) (vector? (util/join-value element)) (not (or (map? result-value) (vector? result-value))))
                  (assoc result result-key (mark-missing {} (util/join-value element)))

                  ; prop we found, but not a further join...mark it as a leaf so sweep can stop early on it
                  result-key
                  (update result result-key as-leaf)

                  :else result))) result query)))

(defn sweep-one "Remove not-found keys from m (non-recursive)" [m]
  (cond
    (map? m) (reduce (fn [acc [k v]]
                       (if (or (= ::not-found k) (= ::not-found v) (= ::tempids k) (= :tempids k))
                         acc
                         (assoc acc k v)))
               (with-meta {} (meta m)) m)
    (vector? m) (with-meta (mapv sweep-one m) (meta m))
    :else m))

(defn sweep "Remove all of the not-found keys (recursively) from v, stopping at marked leaves (if present)"
  [m]
  (cond
    (leaf? m) (sweep-one m)
    (map? m) (reduce (fn [acc [k v]]
                       (cond
                         (or (= ::not-found k) (= ::not-found v) (= ::tempids k) (= :tempids k)) acc
                         (and (eql/ident? v) (= ::not-found (second v))) acc
                         :otherwise (assoc acc k (sweep v))))
               (with-meta {} (meta m))
               m)
    (vector? m) (with-meta (mapv sweep m) (meta m))
    :else m))

(defn sweep-merge
  "Do a recursive merge of source into target, but remove any target data that is marked as missing in the response. The
  missing marker is generated in the source when something has been asked for in the query, but had no value in the
  response. This allows us to correctly remove 'empty' data from the database without accidentally removing something
  that may still exist on the server (in truth we don't know its status, since it wasn't asked for, but we leave
  it as our 'best guess')"
  [target source]
  (reduce
    (fn [acc [key new-value]]
      (let [existing-value (get acc key)]
        (cond
          (or (= key ::tempids) (= key :tempids) (= key ::not-found)) acc
          (= new-value ::not-found) (dissoc acc key)
          (and (eql/ident? new-value) (= ::not-found (second new-value))) acc
          (leaf? new-value) (assoc acc key (sweep-one new-value))
          (and (map? existing-value) (map? new-value)) (update acc key sweep-merge new-value)
          :else (assoc acc key (sweep new-value)))))
    target
    source))

(defn component-pre-merge [class query state data]
  (if (comp/has-pre-merge? class)
    (let [entity (some->> (comp/get-ident class data) (get-in state))]
      (comp/pre-merge class {:state-map          state
                             :current-normalized entity
                             :data-tree          data
                             :query              query}))
    data))

(defn pre-merge-transform
  "Transform function that modifies data using component pre-merge hook."
  [state]
  (fn pre-merge-transform-internal [query data]
    (if-let [class (-> query meta :component)]
      (component-pre-merge class query state data)
      data)))

(defn merge-handler
  "Handle merging incoming data, but be sure to sweep it of values that are marked missing. Also triggers the given mutation-merge
  if available."
  [mutation-merge target source]
  (let [source-to-merge (->> source
                          (filter (fn [[k _]] (not (symbol? k))))
                          (into {}))
        merged-state    (sweep-merge target source-to-merge)]
    (reduce (fn [acc [k v]]
              (if (and mutation-merge (symbol? k))
                (if-let [updated-state (mutation-merge acc k (dissoc v :tempids ::tempids))]
                  updated-state
                  (do
                    (log/info "Return value handler for" k "returned nil. Ignored.")
                    acc))
                acc)) merged-state source)))

(defn merge-mutation-joins
  "Merge all of the mutations that were joined with a query"
  [state query data-tree]
  (if (map? data-tree)
    (reduce (fn [updated-state query-element]
              (let [k       (and (util/mutation-join? query-element) (util/join-key query-element))
                    subtree (get data-tree k)]
                (if (and k subtree)
                  (let [subquery         (util/join-value query-element)
                        target           (-> (meta subquery) :fulcro.client.impl.data-fetch/target)
                        idnt             ::temporary-key
                        norm-query       [{idnt subquery}]
                        norm-tree        {idnt subtree}
                        norm-tree-marked (mark-missing norm-tree norm-query)
                        db               (fnorm/tree->db norm-query norm-tree-marked true (pre-merge-transform state))]
                    (cond-> (sweep-merge updated-state db)
                      target (targeting/process-target idnt target)
                      (not target) (dissoc db idnt)))
                  updated-state))) state query)
    state))

(defn merge-ident [app-state ident props]
  (update-in app-state ident (comp sweep-one merge) props))



(defn- sift-idents [res]
  (let [{idents true rest false} (group-by #(vector? (first %)) res)]
    [(into {} idents) (into {} rest)]))

(defn merge-tree
  "Handle merging incoming data, but be sure to sweep it of values that are marked missing. Also triggers the given mutation-merge
  if available."
  [target source]
  (let [source-to-merge (into {}
                          (filter (fn [[k _]] (not (symbol? k))))
                          source)]
    (sweep-merge target source-to-merge)))

(defn- merge-idents [tree query refs]
  (let [ident-joins (into {} (comp
                               (map #(cond-> % (seq? %) first))
                               (filter #(and (util/join? %)
                                          (eql/ident? (util/join-key %)))))
                      query)]
    (letfn [(step [result-tree [ident props]]
              (let [component-query (get ident-joins ident '[*])
                    props'          (fnorm/tree->db component-query props false (pre-merge-transform tree))
                    refs            (meta props')]
                (merge-tree (merge-ident result-tree ident props') refs)))]
      (reduce step tree refs))))

(defn merge*
  "Merge the query-result of a query using Fulcro's standard merge and normalization logic.

  Typically used on the state atom as:

  ```
  (swap! state merge* query-result query)
  ```

  state-map - The state map of the current application.
  query - The query that was used to obtain the query-result.
  query-result - The query-result to merge (a map).

  Returns the new state."
  [state-map query result-tree]
  (let [[idts result-tree] (sift-idents result-tree)
        normalized-result (fnorm/tree->db query result-tree true (pre-merge-transform state-map))]
    (-> state-map
      (merge-mutation-joins query result-tree)
      (merge-idents query idts)
      (merge-tree normalized-result))))

(defn component-merge-query
  "Calculates the query that can be used to pull (or merge) a component with an ident
  to/from a normalized app database. Requires a tree of data that represents the instance of
  the component in question (e.g. ident will work on it)"
  [state-map component object-data]
  (let [ident        (comp/ident component object-data)
        object-query (comp/get-query component state-map)]
    [{ident object-query}]))

(defn -preprocess-merge
  "PRIVATE.

  Does the steps necessary to honor the data merge technique defined by Fulcro with respect
  to data overwrites in the app database."
  [state-map component object-data]
  (let [ident         (comp/get-ident component object-data)
        object-query  (comp/get-query component state-map)
        object-query  (if (map? object-query) [object-query] object-query)
        base-query    (component-merge-query state-map component object-data)
        ;; :fulcro/merge is way to make unions merge properly when joined by idents
        merge-query   [{:fulcro/merge base-query}]
        ;; TASK: need basis-t
        existing-data (get (fdn/db->tree base-query state-map state-map) ident {})
        marked-data   (mark-missing object-data object-query)
        merge-data    {:fulcro/merge {ident (util/deep-merge existing-data marked-data)}}]
    {:merge-query merge-query
     :merge-data  merge-data}))

