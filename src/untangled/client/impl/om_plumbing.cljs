(ns untangled.client.impl.om-plumbing
  (:require [om.next :as om]
            [untangled.i18n.core :as i18n]
            [untangled.client.mutations :as m]
            [untangled.client.logging :as log]
            [cljs.core.async :as async])
  (:require-macros
    [cljs.core.async.macros :refer [go]]))

(defn read-local
  "Read function for the Om parser.

  *** NOTE: This function only runs when it is called without a target -- it is not triggered for remote reads. To
  trigger a remote read, use the `untangled/data-fetch` namespace. ***

  Returns the current locale when reading the :app/locale keyword. Otherwise pulls data out of the app-state.
  "
  [{:keys [query target state]} dkey _]
  (when (not target)
    (case dkey
      :app/locale {:value (deref i18n/*current-locale*)}
      (let [top-level-prop (nil? query)
            key (or (:ast key) dkey)
            by-ident? (om/ident? key)
            union? (map? query)
            data (if by-ident? (get-in @state key) (get @state key))]
        {:value
         (cond
           union? (get (om/db->tree [{dkey query}] @state @state) dkey)
           top-level-prop data
           :else (om/db->tree query data @state))}))))

(defn write-entry-point [env k params]
  (let [rv (try
             (m/mutate env k params)
             (catch :default e
               (log/error (str "Mutation " k " failed with exception") e)
               nil))
        action (:action rv)]
    (if action
      (assoc rv :action (fn [env k params]
                          (try
                            (action env k params)
                            (catch :default e
                              (log/error (str "Mutation " k " failed with exception") e)
                              (throw e)))))
      rv)))

(defn resolve-tempids [state tid->rid]
  "Replaces all om-tempids in app-state with the ids returned by the server."
  (clojure.walk/prewalk #(if (-> % type (= om.tempid/TempId)) (get tid->rid % %) %) state))

(defn rewrite-tempids-in-request-queue
  "Rewrite any pending requests in the request queue to account for the fact that a response might have
  changed ids that are expressed in the mutations of that queue. tempid-map MUST be a map from om
  tempid to real ids, not idents."
  [queue tempid-map]
  (loop [entry (async/poll! queue) entries []]
    (cond
      entry (recur (async/poll! queue) (conj entries (resolve-tempids entry tempid-map)))
      (seq entries) (doseq [e entries] (assert (async/offer! queue e) "Queue should not block.")))))

(defn filter-loads-and-fallbacks
  "Removes all app/load and tx/fallback mutations from the query"
  [query]
  (let [symbols-to-filter #{'app/load 'tx/fallback}
        ast (om/query->ast query)
        children (:children ast)
        new-children (filter (fn [child] (not (contains? symbols-to-filter (:dispatch-key child)))) children)
        new-ast (assoc ast :children new-children)]
    (om/ast->query new-ast)))

(defn fallback-query [query]
  "Filters out everything from the query that is not a fallback mutation.
  Returns nil if the resulting expression is empty."
  (let [symbols-to-find #{'tx/fallback}
        ast (om/query->ast query)
        children (:children ast)
        new-children (->> children
                       (filter (fn [child] (contains? symbols-to-find (:dispatch-key child))))
                       (map (fn [ast] (update ast :params assoc :execute true))))
        new-ast (assoc ast :children new-children)
        fallback-query (om/ast->query new-ast)]
    (when (not-empty fallback-query)
      fallback-query)))

(defn- is-ui-query-fragment? [kw]
  (when (keyword? kw) (some->> kw namespace (re-find #"^ui(?:\.|$)"))))

(defn- remove-ui-query-fragments [v]
  (->> v
    (remove is-ui-query-fragment?)
    (remove #(when (list? %)
              (-> % first is-ui-query-fragment?)))
    vec))

(defn strip-ui
  "Returns a new query with fragments beginning with `ui` removed."
  [query]
  (clojure.walk/prewalk #(if (vector? %) (remove-ui-query-fragments %) %) query))

(def nf ::not-found)

(defn mark-missing [result query]
  (letfn [(paramterized? [q]
            (and (list? q)
              (or (symbol? (first q))
                (= 2 (count q)))))
          (ok*not-found [res k]
            (if (contains? res k) res
                                  (assoc (if (map? res) res {})
                                    k nf)))
          (union->query [u] (->> u vals flatten set))
          (union? [q]
            (let [expr (cond-> q (seq? q) first)]
              (and (map? expr)
                (< 1 (count (seq expr))))))
          (step [res q]
            (let [q (if (paramterized? q) (first q) q)]
              (let [[query-key ?sub-query] (cond
                                             (om/join? q)
                                             [(om/join-key q) (om/join-value q)]

                                             :else [q nil])
                    res+nf (ok*not-found res query-key)
                    sub-result (get res+nf query-key)]
                (cond
                  ;; singleton union result
                  (and (union? ?sub-query) (map? sub-result))
                  (assoc res+nf query-key
                                (mark-missing sub-result
                                  (union->query (get q query-key))))

                  ;; list union result
                  (union? ?sub-query)
                  (as-> sub-result <>
                    (mapv #(mark-missing % (union->query (get q query-key))) <>)
                    (assoc res+nf query-key <>))

                  ;; recur
                  (and ?sub-query (not= nf sub-result))
                  (as-> sub-result <>
                    (if (vector? <>)
                      (mapv #(mark-missing % ?sub-query) <>)
                      (mark-missing <> ?sub-query))
                    (assoc res+nf query-key <>))

                  ;; nf so next step
                  :else res+nf))))]
    (reduce step
      result query)))

(defn sweep-missing [result]
  (letfn [(clean [[k v]] (when-not (= v nf) [k v]))]
    (clojure.walk/prewalk
      #(if (map? %)
        (into {} (map clean %)) %)
      result)))

