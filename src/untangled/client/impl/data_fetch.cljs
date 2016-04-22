(ns untangled.client.impl.data-fetch
  (:require [om.next.impl.parser :as op]
            [om.next :as om]
            [om.util :as util]
            [clojure.walk :refer [prewalk]]
            [clojure.set :as set]
            [untangled.client.mutations :as m]
            [untangled.client.logging :as log]
            [untangled.client.impl.om-plumbing :as plumbing])
  (:require-macros
    [cljs.core.async.macros :refer [go]]))

(declare data-path data-query set-loading! full-query loaded-callback error-callback)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation for public api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn data-state? [state] (contains? state ::type))

(letfn [(is-kind? [state type]
          (if (data-state? state)
            (= type (::type state))
            false))]
  (defn ready? [state] (is-kind? state :ready))
  (defn loading? [state] (is-kind? state :loading))
  (defn failed? [state] (is-kind? state :failed)))

(defn mark-loading
  "Marks all of the items in the ready-to-load state as loading, places the loading markers in the appropriate locations
  in the app state, and returns a map with the keys:

  `query` : The full query to send to the server.
  `on-load` : The function to call to merge a response. Detects missing data and sets failure markers for those.
  `on-error` : The function to call to set network/server error(s) in place of loading markers.

  response-channel will have the response posted to it when the request is done.
  ."
  [reconciler]
  (let [state (om/app-state reconciler)
        items-to-load (get @state :om.next/ready-to-load)]
    (when-not (empty? items-to-load)
      (om/merge! reconciler {:ui/loading-data true})
      (doseq [item items-to-load]
        (swap! state assoc-in
               (data-path item)
               {:ui/fetch-state (set-loading! item)}))
      (swap! state assoc :om.next/ready-to-load [])
      (om/force-root-render! reconciler)
      {:query    (full-query items-to-load)
       :on-load  (loaded-callback reconciler items-to-load)
       :on-error (error-callback reconciler items-to-load)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Testing API, used to write tests against specific data states
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; not-present represented by nil
;; ok represented by data
(def valid-types #{:ready :loading :failed})

(defn make-data-state
  "This is just a testing function -- using ready-state as public interface and call the
  `set-{type}!` functions to change it as needed."
  ([type]
   (make-data-state type {}))

  ([type params]
   (if (get valid-types type)
     {::type type ::params params}
     (throw (ex-info (str "INVALID DATA STATE TYPE: " type) {})))))

(defn get-ready-query
  "Get the query for items that are ready to load into the given app state. Can be called any number of times
  (side effect free)."
  [state]
  (let [items-to-load (get @state :om.next/ready-to-load)]
    (when-not (empty? items-to-load)
      (op/expr->ast {:items-to-load (vec (mapcat data-query items-to-load))}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers -- not intended for public use
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn elide-ast-nodes
  "Remove items from a query (AST) that have a key listed in the elision-set"
  [{:keys [key] :as ast} elision-set]
  (when-not (contains? elision-set key)
    (update ast :children (fn [c] (vec (keep #(elide-ast-nodes % elision-set) c))))))

(defn inject-query-params
  "Inject parameters into elements of the top-level query.

  `params` is a map from keyword (on the query in the AST) to parameter maps. So, given the AST for this query:

  ```
  [:a :b :c]
  ```

  and a `params` of `{:a {:x 1} :c {:y 2}}` you'll get an AST representing:

  ```
  [(:a {:x 1}) :b (:c {:y 2})]
  ```
  "
  [ast params]
  (let [top-level-keys (set (map :dispatch-key (:children ast)))
        param-keys (set (keys params))
        unknown-keys (set/difference param-keys top-level-keys)]
    (when (not (empty? unknown-keys))
      (log/error (str "Error: You attempted to add parameters for " (pr-str unknown-keys) " to top-level key(s) of " (pr-str (om/ast->query ast)))))
    (update-in ast [:children] #(map (fn [c] (if-let [new-params (get params (:dispatch-key c))]
                                               (update c :params merge new-params)
                                               c)) %))))


(defn ready-state
  "Generate a ready-to-load state with all of the necessary details to do
  remoting and merging."
  [& {:keys [ident field params without query post-mutation fallback] :or {:without #{}}}]
  (assert (or field query) "You must supply a query or a field/ident pair")
  (assert (or (not field) (and field (util/ident? ident))) "Field requires ident")
  (let [old-ast (om/query->ast query)
        ast (cond-> old-ast
                    (not-empty without) (elide-ast-nodes without)
                    params (inject-query-params params))
        query-field (first query)
        key (if (util/join? query-field) (util/join-key query-field) query-field)
        query' (om/ast->query ast)]
    (assert (or (not field) (= field key)) "Component fetch query does not match supplied field.")
    {::type          :ready
     ::ident         ident                                  ; only for component-targeted loads
     ::field         field                                  ; for component-targeted load
     ::query         query'                                 ; query, relative to root of db OR component
     ::post-mutation post-mutation
     ::fallback      fallback}))

(defn mark-ready
  "Place a ready-to-load marker into the application state. This should be done from
  a mutate function that is abstractly loading something. This is intended for internal use.

  See `load-field` for public API."
  [& {:keys [state query ident field without params post-mutation fallback] :or {:without #{}}}]
  (swap! state update :om.next/ready-to-load conj
         (ready-state
           :ident ident
           :field field
           :params params
           :without without
           :query query
           :post-mutation post-mutation
           :fallback fallback)))

;; TODO: Rename "getters"
(defn data-ident [state] (::ident state))
(defn data-query [state]
  (if (data-ident state)
    [{(data-ident state) (::query state)}]
    (::query state)))
(defn data-field [state] (::field state))
(defn data-query-key [state]
  (let [expr (-> state ::query first)
        key (cond
              (keyword? expr) expr
              (map? expr) (ffirst expr)
              )]
    key))

(defn data-path [state] (if (and (nil? (data-ident state)) (nil? (data-field state)))
                          [(data-query-key state)]
                          (conj (data-ident state) (data-field state))))
(defn data-params [state] (::params state))

(defn data-exclusions [state] (::without state))

;; Setters
(letfn [(set-type [state type params]
          (merge state {::type   type
                        ::params params}))]
  (defn set-ready!
    ([state] (set-ready! state nil))
    ([state params] (set-type state :ready params)))
  (defn set-loading!
    ([state] (set-loading! state nil))
    ([state params] (let [rv (set-type state :loading params)]
                      (with-meta rv {:state rv}))))
  (defn set-failed!
    ([state] (set-failed! state nil))
    ([state params] (set-type state :failed params))))

(defn swap-data-states
  "Swaps all data states in a map that satisfy `from-state-pred` to the `to-state-fn` type.
  The `to-state-fn` methods are intended to be the setters."

  ([state from-state-pred to-state-fn]
   (swap-data-states state from-state-pred to-state-fn nil))

  ([state from-state-pred to-state-fn params]
   (->> state
        (prewalk #(if (from-state-pred %) (to-state-fn % params) %))
        (prewalk #(when-not (= % {:ui/fetch-state nil}) %)))))

(defn full-query
  "Compose together a sequence of states into a single query."
  [items] (vec (mapcat (fn [item] (data-query item)) items)))

;; TODO: aren't all loading-items in the app-state necessarily from this iteration of the algorithm?
(defn- active-loads? [loading-items]
  "Useful for simultaneous `mark-loading` calls, so that loading states from other calls are not cleared accidentally."
  (fn [fetch-state] (and (loading? fetch-state) (contains? loading-items fetch-state))))

(defn- set-global-loading [reconciler]
  "Sets :ui/loading to false if there are no loading fetch states in the entire app-state, otherwise sets to true."
  (om/merge! reconciler {:ui/loading-data false})

  (prewalk (fn [value]
             (cond
               (:ui/loading-data @reconciler) nil           ;short-circuit traversal if ui/loading-data already true
               (loading? value) (do (om/merge! reconciler {:ui/loading-data true}) value)
               :else value))
           @reconciler))

(defn- loaded-callback [reconciler items]
  (fn [response]
    (let [query (full-query items)
          loading-items (into #{} (map set-loading! items))
          marked-response (plumbing/mark-missing response query)
          app-state (om/app-state reconciler)]
      (om/merge! reconciler marked-response query)
      (doseq [item loading-items]
        (when-let [mutation-symbol (::post-mutation item)]
          (some->
            (m/mutate {:state (om/app-state reconciler)} mutation-symbol {})
            :action
            (apply []))))
      (om/force-root-render! reconciler)                    ; Don't love this, but ok for now. TK

      ;; Any loading states that didn't resolve to data are marked as not present
      (swap! app-state swap-data-states (active-loads? loading-items) (fn [_] nil))

      (set-global-loading reconciler))))

(defn- error-callback [reconciler items]
  (let [loading-items (into #{} (map set-loading! items))]
    (fn [error]
      (swap! (om/app-state reconciler)
             (fn [st]
               (-> st
                   (assoc :untangled/server-error error)
                   (swap-data-states (active-loads? loading-items) #(set-failed! % error)))))
      (doseq [item loading-items]
        (when-let [fallback-symbol (::fallback item)]
          (some->
            (m/mutate {:state (om/app-state reconciler)} fallback-symbol {:error error})
            :action
            (apply []))))
      (set-global-loading reconciler))))
