(ns com.fulcrologic.fulcro.algorithms.merge
  "Various algorithms that are used for merging trees of data into a normalized Fulcro database."
  (:require
    [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]))

(defn remove-ident*
  "Removes an ident, if it exists, from a list of idents in app state. This
  function is safe to use within mutations."
  [state-map ident path-to-idents]
  {:pre [(map? state-map)]}
  (let [new-list (fn [old-list]
                   (vec (filter #(not= ident %) old-list)))]
    (update-in state-map path-to-idents new-list)))

(defn is-ui-query-fragment?
  "Check the given keyword to see if it is in the :ui namespace."
  [kw]
  (let [kw (if (map? kw) (-> kw keys first) kw)]
    (when (keyword? kw) (some->> kw namespace (re-find #"^ui(?:\.|$)")))))

(defn not-found?
  "Returns true if the `k` in `props` is the sweep-merge not-found marker. This marker appears
  *during* merge, and can affect `:pre-merge` processing, since the data-tree will have these
  markers when the given data is missing."
  [props k]
  (= ::not-found (get props k)))

(defn nilify-not-found
  "Given x, return x value unless it's ::not-found (the mark/sweep missing marker), in which case it returns nil.

  This is useful when you are pre-processing a tree that has been marked for missing data sweep (see `mark-missing`),
  but has not yet been swept. This is basically the same as a `nil?` check in this circumstance since the given
  value will be removed after the final sweep."
  [x]
  (if (= x ::not-found) nil x))

(defn- as-leaf
  "Returns `data` with meta-data that marks it as a leaf in the result."
  [data]
  (if (coll? data)
    (with-meta data {:fulcro/leaf true})
    data))

(defn- leaf?
  "Returns true iff the given data is marked as a leaf in the result (according to the query). Requires pre-marking."
  [data]
  (or
    (not (coll? data))
    (and (vector? data) (empty? data))
    (and (coll? data)
      (-> data meta :fulcro/leaf boolean))))

(defn- union->query
  "Turn a union query into a query that attempts to encompass all possible things that might be queried."
  [union-query]
  (->> union-query vals flatten set vec))

(defn mark-missing-impl
  [result query]
  (let [missing-entity {}]
    (reduce (fn [result element]
              (let [element        (cond
                                     (list? element) (first element)
                                     :else element)
                    join?          (util/join? element)
                    jk             (when join? (util/join-key element))
                    result-key     (cond
                                     (keyword? element) element
                                     join? jk
                                     :else nil)
                    result-value   (get result result-key)
                    ident-element? (eql/ident? element)]
                (cond
                  (or (and ident-element? (= '_ (second element)))
                    (and (eql/ident? result-key) (= '_ (second result-key))))
                  result

                  (is-ui-query-fragment? result-key)
                  result

                  ; plain missing prop
                  (and (keyword? element) (nil? (get result element)))
                  (assoc result element ::not-found)

                  ; recursion
                  (and join? (or
                               (number? (util/join-value element))
                               (= '... (util/join-value element))))
                  (let [result' (get result jk)]
                    (cond
                      (nil? result') (assoc result jk ::not-found)
                      (vector? result') (assoc result jk (mapv (fn [item] (mark-missing-impl item query)) result'))
                      :otherwise (assoc result jk (mark-missing-impl result' query))))

                  ; pure ident query
                  (and ident-element? (nil? (get result element)))
                  (assoc result element missing-entity)

                  ; union (a join with a map as a target query)
                  (util/union? element)
                  (let [v          (get result result-key ::not-found)
                        to-one?    (map? v)
                        to-many?   (vector? v)
                        wide-query (union->query (util/join-value element))]
                    (cond
                      to-one? (assoc result result-key (mark-missing-impl v wide-query))
                      to-many? (assoc result result-key (mapv (fn [i] (mark-missing-impl i wide-query)) v))
                      (= ::not-found v) (assoc result result-key ::not-found)
                      :else result))

                  ; ident-based join to nothing (removing table entries)
                  (and join?
                    (eql/ident? jk)
                    (nil? (get result jk)))
                  (let [mock-missing-object (mark-missing-impl {} (util/join-value element))
                        v                   (merge mock-missing-object missing-entity)]
                    (assoc result jk v))

                  ; join to nothing
                  (and join? (= ::not-found (get result jk ::not-found)))
                  (assoc result jk ::not-found)

                  ; to-many join
                  (and join? (vector? (get result jk)))
                  (assoc result jk (mapv (fn [item] (mark-missing-impl item (util/join-value element))) (get result jk)))

                  ; to-one join
                  (and join? (map? (get result jk)))
                  (assoc result jk (mark-missing-impl (get result jk) (util/join-value element)))

                  ; join, but with a broken result (scalar instead of a map or vector)
                  (and join? (vector? (util/join-value element)) (not (or (map? result-value) (vector? result-value))))
                  (assoc result result-key (mark-missing-impl {} (util/join-value element)))

                  ; prop we found, but not a further join...mark it as a leaf so sweep can stop early on it
                  result-key
                  (update result result-key as-leaf)

                  :else result))) result query)))

(defn mark-missing
  "Recursively walk the query and response marking anything that was *asked for* in the query but is *not* in the response as missing.
  The sweep-merge process (which happens later in the plumbing) uses these markers as indicators to remove any existing
  data in the target of the merge (i.e. your state database).

  The naive approach to data merging (even recursive) would fail to remove such data.

  Returns the result with missing markers in place (which are then used/removed in a later stage).

  See the Developer Guide section on Fulcro's merge process for more information."
  [result query]
  (try
    (mark-missing-impl result query)
    (catch #?(:clj Exception :cljs :default) e
      (log/error e "Unable to mark missing on result. Returning unmarked result. See https://book.fulcrologic.com/#err-merge-unable2mark")
      result)))

(defn- sweep-one
  "Remove not-found keys from m (non-recursive). `m` can be a map (sweep the values) or vector (run sweep-one on each entry)."
  [m]
  (cond
    ;; tempids look like maps in CLJ
    (and (not (tempid/tempid? m)) (map? m))
    (reduce (fn [acc [k v]]
              (if (or (= ::not-found k) (= ::not-found v) (= :tempids k))
                acc
                (assoc acc k v)))
      (with-meta {} (meta m)) m)
    (vector? m) (with-meta (mapv sweep-one m) (meta m))
    :else m))

(defn sweep
  "Remove all of the not-found keys (recursively) from m, stopping at marked leaves (if present). Requires `m`
  to have been pre-marked via `mark-missing`."
  [m]
  (cond
    (leaf? m) (sweep-one m)
    ;; tempids look like maps in CLJ
    (and (not (tempid/tempid? m)) (map? m))
    (reduce (fn [acc [k v]]
              (cond
                (or (= ::not-found k) (= ::not-found v) (= :tempids k)) acc
                (and (eql/ident? v) (= ::not-found (second v))) acc
                :otherwise (assoc acc k (sweep v))))
      (with-meta {} (meta m))
      m)
    (vector? m) (with-meta (mapv sweep m) (meta m))
    :else m))

(defn sweep-merge
  "Do a recursive merge of source into target (both maps), but remove any target data that is marked as missing in the response.

  Requires that the `source` has been marked via `mark-missing`.

  The missing marker is generated in the source when something has been asked for in the query, but had no value in the
  response. This allows us to correctly remove 'empty' data from the database without accidentally removing something
  that may still exist on the server (in truth we don't know its status, since it wasn't asked for, but we leave
  it as our 'best guess')."
  [target source]
  (reduce
    (fn [acc [key new-value]]
      (let [existing-value (get acc key)]
        (cond
          (or (= key :tempids) (= key ::not-found)) acc
          (= new-value ::not-found) (dissoc acc key)
          (and (eql/ident? new-value) (= ::not-found (second new-value))) acc
          (leaf? new-value) (assoc acc key (sweep-one new-value))
          (and (map? existing-value) (map? new-value)) (update acc key sweep-merge new-value)
          :else (assoc acc key (sweep new-value)))))
    target
    source))

(defn- component-pre-merge [class query state data options]
  (if (rc/has-pre-merge? class)
    (let [entity (some->> (rc/get-ident class data) (get-in state))
          result (rc/pre-merge class {:state-map            state
                                        :current-normalized entity
                                        :data-tree          data
                                        :query              query})]
      result)
    data))

(defn pre-merge-transform
  "Transform function that modifies data using component pre-merge hook."
  ([state]
   (pre-merge-transform state {}))
  ([state options]
   (fn pre-merge-transform-internal [query data]
     (if-let [class (-> query meta :component)]
       (component-pre-merge class query state data options)
       data))))

(defn merge-mutation-joins
  "Merge all of the mutations that were joined with a query.

  The options currently do nothing. If you want mark/sweep, pre-mark the data-tree with `merge/mark-missing`,
  and this function will sweep the result."
  ([state query data-tree]
   (merge-mutation-joins state query data-tree {}))
  ([state query data-tree options]
   (if (map? data-tree)
     (reduce (fn [updated-state query-element]
               (let [k       (and (util/mutation-join? query-element) (util/join-key query-element))
                     subtree (get data-tree k)]
                 (if (and k subtree)
                   (let [subquery   (util/join-value query-element)
                         target     (-> (meta subquery) ::targeting/target)
                         idnt       ::temporary-key
                         norm-query [{idnt subquery}]
                         norm-tree  {idnt subtree}
                         db         (fnorm/tree->db norm-query norm-tree true (pre-merge-transform state options))]
                     (cond-> (sweep-merge updated-state db)
                       target (targeting/process-target idnt target)
                       (not target) (dissoc db idnt)))
                   updated-state))) state query)
     state)))

(defn merge-ident [app-state ident props]
  (update-in app-state ident (comp sweep-one merge) props))

(defn- sift-idents [res]
  (let [{idents true rest false} (group-by #(vector? (first %)) res)]
    [(into {} idents) (into {} rest)]))

(defn merge-tree
  "Handle merging incoming data and sweep it of values that are marked missing. This function also ensures that raw
   mutation join results are ignored (they must be merged via `merge-mutation-joins`)."
  [target source]
  (let [source-to-merge (into {}
                          (filter (fn [[k _]] (not (symbol? k))))
                          source)]
    (sweep-merge target source-to-merge)))

(defn merge-idents
  "Merge the given `refs` (a map from ident to props), query (a query that contains ident-joins), and tree:

  returns a new tree with the data merged into the proper ident-based tables."
  [tree query refs options]
  (let [ident-joins (into {} (comp
                               (map #(cond-> % (seq? %) first))
                               (filter #(and (util/join? %)
                                          (eql/ident? (util/join-key %)))))
                      query)]
    (letfn [(step [result-tree [ident props]]
              (let [component-query (get ident-joins ident '[*])
                    normalized-data (fnorm/tree->db component-query props false (pre-merge-transform tree options))
                    refs            (meta normalized-data)]
                (-> result-tree
                  (merge-ident ident normalized-data)
                  (merge-tree refs))))]
      (reduce step tree refs))))

(defn merge*
  "Merge the query-result of a query using Fulcro's standard merge and normalization logic.

  Typically used on the state atom as:

  ```
  (swap! state merge* query-result query)
  ```

  - `state-map` - The normalized database.
  - `query` - The query that was used to obtain the query-result. This query will be treated relative to the root of the database.
  - `tree` - The query-result to merge (a map).

  The options is currently doing nothing. If you want to sweep unreturned data use `merge/mark-missing` on your data tree
  before calling this.

  See `merge-component` and `merge-component!` for possibly more appropriate functions for your task.

  Returns the new normalized database."
  ([state-map query result-tree] (merge* state-map query result-tree {}))
  ([state-map query result-tree options]
   (let [[idts result-tree] (sift-idents result-tree)
         normalized-result (fnorm/tree->db query result-tree true (pre-merge-transform state-map options))]
     (-> state-map
       (merge-mutation-joins query result-tree options)
       (merge-idents query idts options)
       (merge-tree normalized-result)))))

(defn component-merge-query
  "Calculates the query that can be used to pull (or merge) a component with an ident
  to/from a normalized app database. Requires a tree of data that represents the instance of
  the component in question (e.g. ident will work on it)"
  [state-map component object-data]
  (let [ident        (rc/ident component object-data)
        object-query (rc/get-query component state-map)]
    [{ident object-query}]))

(defn merge-alternate-unions
  "Walks the given query and calls (merge-fn parent-union-component union-child-initial-state) for each non-default element of a union that has initial app state.
  You probably want to use merge-alternate-union-elements[!] on a state map or app."
  [merge-fn root-component]
  (letfn [(walk-ast
            ([ast visitor]
             (walk-ast ast visitor nil))
            ([{:keys [children component type dispatch-key union-key key] :as parent-ast} visitor parent-union]
             (when (and component parent-union (= :union-entry type))
               (visitor component parent-union))
             (when children
               (doseq [ast children]
                 (cond
                   (= (:type ast) :union) (walk-ast ast visitor component) ; the union's component is on the parent join
                   (= (:type ast) :union-entry) (walk-ast ast visitor parent-union)
                   ast (walk-ast ast visitor nil))))))
          (merge-union [component parent-union]
            (let [default-initial-state   (and parent-union (rc/has-initial-app-state? parent-union) (rc/get-initial-state parent-union {}))
                  to-many?                (vector? default-initial-state)
                  component-initial-state (and component (rc/has-initial-app-state? component) (rc/get-initial-state component {}))]
              (when (and component component-initial-state parent-union (not to-many?) (not= default-initial-state component-initial-state))
                (merge-fn parent-union component-initial-state))))]
    (walk-ast
      (eql/query->ast (rc/get-query root-component))
      merge-union)))

(defn merge!
  "Merge an arbitrary data-tree that conforms to the shape of the given query using Fulcro's
  standard merge and normalization logic.

  app - A fulcro application to merge into.
  query - A query, derived from components, that can be used to normalized a tree of data.
  data-tree - A tree of data that matches the nested shape of query.

  The options map currently does nothing. If you want to remove unreturned data use `merge/mark-missing` on the
  data tree before merging and a sweep will automatically be done.

  NOTE: This function assumes you are merging against the root of the tree. See
  `merge-component` and `merge-component!` for relative merging.

  See also `merge*`."

  ([app data-tree query]
   (merge! app data-tree query {}))
  ([app data-tree query options]
   (let [state-atom (:com.fulcrologic.fulcro.application/state-atom app)
         render!    (ah/app-algorithm app :schedule-render!)]
     (when state-atom
       (swap! state-atom merge* query data-tree options)
       (render! app {})))))

(defn merge-component
  "Given a state map of the application database, a component, and a tree of component-data: normalizes
   the tree of data and merges the component table entries into the state, returning a new state map.

   Since there is not an implied root, the component itself won't be linked into your graph (though it will
   remain correctly linked for its own consistency).

   * `state-map` - The normalized database
   * `component` - A component class
   * `component-data` - A tree of data that matches the shape of the component's query.
   * `named-parameters` - Parameters from `targeting/integrate-ident*` that will let you link the merged component into the graph.
   Named parameters may also include `:remove-missing?`, which will remove things that are queried for but do
   not appear in the data from the state.

   See also targeting/integrate-ident*, and merge/merge-component!"
  [state-map component component-data & named-parameters]
  (if (rc/has-ident? component)
    (let [options           (apply hash-map named-parameters)
          {:keys [remove-missing?]} options
          query             (rc/get-query component state-map)
          marked-data       (if remove-missing?
                              (mark-missing component-data query)
                              component-data)
          updated-state     (merge* state-map [{::merge query}] {::merge marked-data} options)
          real-ident        (get updated-state ::merge)
          integrate-params  (mapcat (fn [[k v]]
                                      (if (#{:append :prepend :replace} k)
                                        [k v]
                                        nil))
                              (partition 2 named-parameters))
          integrate-targets (fn [s]
                              (if (seq named-parameters)
                                (apply targeting/integrate-ident* s real-ident integrate-params)
                                s))]
      (-> updated-state
        (integrate-targets)
        (dissoc ::merge)))
    (do
      (log/error "Cannot merge component " component " because it does not have an ident! See https://book.fulcrologic.com/#err-merge-comp-missing-ident")
      state-map)))

(defn merge-component!
  "Normalize and merge a (sub)tree of application state into the application using a known UI component's query and ident.

  This utility function obtains the ident of the incoming object-data using the UI component's ident function. Once obtained,
  it uses the component's query and ident to normalize the data and places the resulting objects in the correct tables.
  It is also quite common to want those new objects to be linked into lists in other spots in app state, so this function
  supports optional named parameters for doing this. These named parameters can be repeated as many times as you like in order
  to place the ident of the new object into other data structures of app state.

  This function honors the data merge story for Fulcro: attributes that are queried for but do not appear in the
  data will be removed from the application. This function also uses the initial state for the component as a base
  for merge if there was no state for the object already in the database.

  This function will also trigger re-renders of components that directly render object merged, as well as any components
  into which you integrate that data via the named-parameters.

  This function is primarily meant to be used from things like server push and setTimeout/setInterval, where you're outside
  of the normal mutation story. Do not use this function within abstract mutations.

  * `app`: Your application.
  * `component`: The class of the component that corresponds to the data. Must have an ident.
  * `object-data`: A map (tree) of data to merge. Will be normalized for you.
  * `named-parameter`: Post-processing ident integration steps. see `targeting/integrate-ident*`. You may also
  include `:remove-missing? true/false` to indicate that data that is missing for the component's query
  should be removed from app state.

  Any keywords that appear in ident integration steps will be added to the re-render queue.

  See also `fulcro.client.primitives/merge!`.
  "
  [app component object-data & named-parameters]
  (when-let [app (rc/any->app app)]
    (if-not (rc/has-ident? component)
      (log/error "merge-component!: component must implement Ident. Merge skipped. See https://book.fulcrologic.com/#err-merge-comp-missing-ident2")
      (let [state   (:com.fulcrologic.fulcro.application/state-atom app)
            render! (ah/app-algorithm app :schedule-render!)]
        (swap! state (fn [s] (apply merge-component s component object-data named-parameters)))
        (render! app {})))))

(defn merge-alternate-union-elements
  "Just like merge-alternate-union-elements!, but usable from within mutations and on server-side rendering. Ensures
  that when a component has initial state it will end up in the state map, even if it isn't currently in the
  initial state of the union component (which can only point to one at a time)."
  [state-map root-component]
  (let [state-map-atom (atom state-map)
        merge-to-state (fn [comp tree] (swap! state-map-atom merge-component comp tree))
        _              (merge-alternate-unions merge-to-state root-component)
        new-state      @state-map-atom]
    new-state))

(defn merge-alternate-union-elements!
  "Walks the query and initial state of root-component and merges the alternate sides of unions with initial state into
  the application state database. See also `merge-alternate-union-elements`, which can be used on a state map and
  is handy for server-side rendering. This function side-effects on your app, and returns nothing."
  [app root-component]
  (let [app (rc/any->app app)]
    (merge-alternate-unions (partial merge-component! app) root-component)))

