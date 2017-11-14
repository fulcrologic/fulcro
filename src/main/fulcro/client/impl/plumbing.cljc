(ns fulcro.client.impl.plumbing
  (:require [fulcro.client.primitives :as prim]
            [fulcro.util :as util]
            [fulcro.client.mutations :as m]
            [fulcro.client.logging :as log]
    #?(:cljs
       [cljs.core.async :as async]
       :clj
            [clojure.core.async :as async])
            [clojure.walk :as walk]))

(defn read-local
  "Read function for the Om parser.

  *** NOTE: This function only runs when it is called without a target -- it is not triggered for remote reads. To
  trigger a remote read, use the `fulcro/data-fetch` namespace. ***

  If a user-read is supplied, *it will be allowed* to trigger remote reads. This is not recommended, as you
  will probably have to augment the networking layer to get it to do what you mean. Use `load` instead. You have
  been warned. Triggering remote reads is allowed, but discouraged and unsupported.

  Returns the current locale when reading the :ui/locale keyword. Otherwise pulls data out of the app-state.
  "
  [user-read {:keys [query target state ast] :as env} dkey params]
  (if-let [custom-result (user-read env dkey params)]
    custom-result
    (when (not target)
      (case dkey
        (let [top-level-prop (nil? query)
              key            (or (:key ast) dkey)
              by-ident?      (util/ident? key)
              union?         (map? query)
              data           (if by-ident? (get-in @state key) (get @state key))]
          {:value
           (cond
             union? (get (prim/db->tree [{key query}] @state @state) key)
             top-level-prop data
             :else (prim/db->tree query data @state))})))))

(defn write-entry-point
  "This is the Om entry point for writes. In general this is simply a call to the multi-method
  defined by Fulcro (mutate); however, Fulcro supports the concept of a global `post-mutate`
  function that will be called anytime the general mutate has an action that is desired. This
  can be useful, for example, in cases where you have some post-processing that needs
  to happen for a given (sub)set of mutations (that perhaps you did not define)."
  [env k params]
  (let [rv     (try
                 (m/mutate env k params)
                 (catch #?(:cljs :default :clj Exception) e
                   (log/error (str "Mutation " k " failed with exception") e)
                   nil))
        action (:action rv)]
    (if action
      (assoc rv :action (fn []
                          (try
                            (let [action-result (action env k params)]
                              (try
                                (m/post-mutate env k params)
                                (catch #?(:cljs :default :clj Exception) e (log/error (str "Post mutate failed on dispatch to " k))))
                              action-result)
                            (catch #?(:cljs :default :clj Exception) e
                              (log/error (str "Mutation " k " failed with exception") e)
                              (throw e)))))
      rv)))

(defn resolve-tempids
  "Replaces all om-tempids in app-state with the ids returned by the server."
  [state tid->rid]
  (if (empty? tid->rid)
    state
    (walk/prewalk #(if (prim/tempid? %) (get tid->rid % %) %) state)))

(defn rewrite-tempids-in-request-queue
  "Rewrite any pending requests in the request queue to account for the fact that a response might have
  changed ids that are expressed in the mutations of that queue. tempid-map MUST be a map from om
  tempid to real ids, not idents."
  [queue tempid-map]
  (loop [entry (async/poll! queue) entries []]
    (cond
      entry (recur (async/poll! queue) (conj entries (resolve-tempids entry tempid-map)))
      (seq entries) (doseq [e entries] (when-not (async/offer! queue e) (log/error "Offer failed to enqueue a value."))))))

(defn remove-loads-and-fallbacks
  "Removes all fulcro/load and tx/fallback mutations from the query"
  [query]
  (let [symbols-to-filter #{'fulcro/load `fulcro.client.data-fetch/load 'tx/fallback `fulcro.client.data-fetch/fallback}
        ast               (prim/query->ast query)
        children          (:children ast)
        new-children      (filter (fn [child] (not (contains? symbols-to-filter (:dispatch-key child)))) children)
        new-ast           (assoc ast :children new-children)]
    (prim/ast->query new-ast)))

(defn fallback-query [query resp]
  "Filters out everything from the query that is not a fallback mutation.
  Returns nil if the resulting expression is empty."
  (let [symbols-to-find #{'tx/fallback 'fulcro.client.data-fetch/fallback}
        ast             (prim/query->ast query)
        children        (:children ast)
        new-children    (->> children
                          (filter (fn [child] (contains? symbols-to-find (:dispatch-key child))))
                          (map (fn [ast] (update ast :params assoc :execute true :error resp))))
        new-ast         (assoc ast :children new-children)
        fallback-query  (prim/ast->query new-ast)]
    (when (not-empty fallback-query)
      fallback-query)))

(defn- is-ui-query-fragment?
  "Check the given keyword to see if it is in the :ui namespace."
  [kw]
  (let [kw (if (map? kw) (-> kw keys first) kw)]
    (when (keyword? kw) (some->> kw namespace (re-find #"^ui(?:\.|$)")))))

(defn strip-ui
  "Returns a new query with fragments that are in the `ui` namespace removed."
  [query]
  (let [ast              (prim/query->ast query)
        drop-ui-children (fn drop-ui-children [ast-node]
                           (let [children (reduce (fn [acc n]
                                                    (if (is-ui-query-fragment? (:dispatch-key n))
                                                      acc
                                                      (conj acc (drop-ui-children n))))
                                            [] (:children ast-node))]
                             (if (seq children)
                               (assoc ast-node :children children)
                               (dissoc ast-node :children))))]

    (prim/ast->query (drop-ui-children ast))))

(def nf ::not-found)

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
                      (nil? result') (assoc result k ::not-found)
                      (vector? result') (assoc result k (mapv (fn [item] (mark-missing item query)) result'))
                      :otherwise (assoc result k (mark-missing result' query))))

                  ; pure ident query
                  (and (util/ident? element) (nil? (get result element)))
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
                  (and (util/join? element) (util/ident? (util/join-key element)) (nil? (get result (util/join-key element))))
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

