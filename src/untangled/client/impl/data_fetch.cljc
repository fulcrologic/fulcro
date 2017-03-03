(ns untangled.client.impl.data-fetch
  (:require [om.next.impl.parser :as op]
            [om.next :as om]
            [om.next.protocols :as omp]
            [om.util :as util]
            [clojure.walk :refer [prewalk]]
            [clojure.set :as set]
            [untangled.client.mutations :as m]
            [untangled.client.logging :as log]
            [untangled.client.impl.om-plumbing :as plumbing]
            [untangled.dom :as udom]
    #?(:cljs [cljs-uuid-utils.core :as uuid])))


; TODO: Some of this API is public, and should be in the non-impl ns.

(declare data-remote data-target data-path data-uuid data-query set-loading! full-query loaded-callback error-callback data-marker?)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation for public api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn data-state?
  "Test if the given bit of state is a data fetch state-tracking marker"
  [state] (contains? state ::type))

(letfn [(is-kind? [state type]
          (if (data-state? state)
            (= type (::type state))
            false))]
  (defn ready?
    "Test if the given item is a data state marker that is in the ready state"
    [state] (is-kind? state :ready))
  (defn loading?
    "Test if the given item is a data state marker in the loading state"
    [state] (is-kind? state :loading))
  (defn failed?
    "Test if the given item is a data state marker in the failed state"
    [state] (is-kind? state :failed)))

(defn- place-load-markers
  "Place load markers in the app state at their data paths so that UI rendering can see them."
  [state-atom items-to-load]
  (doseq [item items-to-load]
    (let [i            (set-loading! item)
          place-marker (fn [state] (if (data-marker? i)
                                     (assoc-in state (data-path i) {:ui/fetch-state i})
                                     state))]
      (swap! state-atom (fn [s] (-> s
                                  place-marker
                                  (update :untangled/loads-in-progress (fnil conj #{}) (data-uuid i))))))))

(defn mark-parallel-loading
  "Marks all of the items in the ready-to-load state as loading, places the loading markers in the appropriate locations
  in the app state, and return maps with the keys:

  `query` : The full query to send to the server.
  `on-load` : The function to call to merge a response. Detects missing data and sets failure markers for those.
  `on-error` : The function to call to set network/server error(s) in place of loading markers.
  `load-descriptors` : Args to pass back to on-load and on-error. These are separated
    so that `rewrite-tempids-in-request-queue` can rewrite tempids for merge and
    error callbacks

  response-channel will have the response posted to it when the request is done.
  ."
  [remote-name reconciler]
  (let [state                (om/app-state reconciler)
        queued-items         (get @state :untangled/ready-to-load)
        is-eligible?         (fn [item] (and (::parallel item) (= remote-name (data-remote item))))
        other-items-loading? (boolean (seq (get @state :untangled/loads-in-progress)))
        items-to-load        (filter is-eligible? queued-items)
        remaining-items      (filter (comp not is-eligible?) queued-items)
        loading?             (or (seq items-to-load) other-items-loading?)]
    (when-not (empty? items-to-load)
      (place-load-markers state items-to-load)
      (swap! state assoc :ui/loading-data loading? :untangled/ready-to-load remaining-items)
      (for [item items-to-load]
        {:query            (full-query [item])
         :on-load          (loaded-callback reconciler)
         :on-error         (error-callback reconciler)
         :load-descriptors [item]}))))

(defn dedupe-by
  "Returns a lazy sequence of the elements of coll with dupes removed.
   An element is a duplicate IFF (keys-fn element) has key collision with any prior element
   to come before it. E.g. (dedupe-by identity [[:a] [:b] [:a] [:a :c]]) => [[:a] [:b]]
   Returns a stateful transducer when no collection is provided."
  ([keys-fn]                                                ;; transducer fn
   (fn [rf]
     (let [keys-seen (volatile! #{})]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input]
          (let [input-keys (set (keys-fn input))]
            ;; if no keys seen, include input in the reduction
            (if (empty? (set/intersection @keys-seen input-keys))
              (do (vswap! keys-seen set/union input-keys)
                  (rf result input))
              result)))))))
  ([keys-fn coll] (sequence (dedupe-by keys-fn) coll)))

(defn join-key-or-nil [expr]
  (when (util/join? expr)
    (let [join-key-or-ident (util/join-key expr)]
      (if (util/ident? join-key-or-ident)
        (first join-key-or-ident)
        join-key-or-ident))))

(defn split-items-ready-to-load
  "This function is used to split accidental colliding queries into separate network
  requests. The most general description of this issue is
  from two unrelated `load` calls when black-box composing functions. The two
  separate queries: One issues `[{:entitlements [:foo]}]`, and the other
  asks for `[{:entitlements [:bar]}]`. Untangled merges these into a single query
  [{:entitlements [:foo]} {:entitlements [:bar]}]. However, the response to a query
  is a map, and such a query would result in the backend parser being called twice (once per key in the subquery)
  but one would stomp on the other. Thus, this function ensures such accidental collisions are
  not combined into a single network request."
  [items-ready-to-load]
  (let [items-to-load-now (->> items-ready-to-load
                            (dedupe-by (fn [item]
                                         (->> (data-query item)
                                           (map join-key-or-nil))))
                            set)
        items-to-defer    (->> items-ready-to-load
                            (remove items-to-load-now)
                            (vec))]
    [items-to-load-now items-to-defer]))

(defn mark-loading
  "Marks all of the items in the ready-to-load state as loading, places the loading markers in the appropriate locations
  in the app state, and returns a map with the keys:

  `query` : The full query to send to the server.
  `on-load` : The function to call to merge a response. Detects missing data and sets failure markers for those.
  `on-error` : The function to call to set network/server error(s) in place of loading markers.
  `load-descriptors` : Args to pass back to on-load and on-error. These are separated
    so that `rewrite-tempids-in-request-queue` can rewrite tempids for merge and
    error callbacks

  response-channel will have the response posted to it when the request is done.
  ."
  [remote reconciler]
  (let [state                   (om/app-state reconciler)
        is-eligible?            (fn [item] (= remote (data-remote item)))
        all-items               (get @state :untangled/ready-to-load)
        items-ready-to-load     (filter is-eligible? all-items)
        items-for-other-remotes (filter (comp not is-eligible?) all-items)
        other-items-loading?    (boolean (seq (get @state :untangled/loads-in-progress)))
        [items-to-load-now items-to-defer] (split-items-ready-to-load items-ready-to-load)
        remaining-items         (concat items-for-other-remotes items-to-defer)
        loading?                (or (seq items-to-load-now) other-items-loading?)]
    (when-not (empty? items-to-load-now)
      (place-load-markers state items-to-load-now)
      (swap! state assoc :ui/loading-data loading? :untangled/ready-to-load remaining-items)
      {:query            (full-query items-to-load-now)
       :on-load          (loaded-callback reconciler)
       :on-error         (error-callback reconciler)
       :load-descriptors items-to-load-now})))

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
  (let [items-to-load (get @state :untangled/ready-to-load)]
    (when-not (empty? items-to-load)
      (op/expr->ast {:items-to-load (vec (mapcat data-query items-to-load))}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers -- not intended for public use
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn elide-ast-nodes
  "Remove items from a query (AST) that have a key listed in the elision-set"
  [{:keys [key union-key children] :as ast} elision-set]
  (let [union-elision? (contains? elision-set union-key)]
    (when-not (or union-elision? (contains? elision-set key))
      (when (and union-elision? (<= (count children) 2))
        (log/warn "Om unions are not designed to be used with fewer than two children. Check your calls to Untangled
        load functions where the :without set contains " (pr-str union-key)))
      (update ast :children (fn [c] (vec (keep #(elide-ast-nodes % elision-set) c)))))))

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
        param-keys     (set (keys params))
        unknown-keys   (set/difference param-keys top-level-keys)]
    (when (not (empty? unknown-keys))
      (log/error (str "Error: You attempted to add parameters for " (pr-str unknown-keys) " to top-level key(s) of " (pr-str (om/ast->query ast)))))
    (update-in ast [:children] #(map (fn [c] (if-let [new-params (get params (:dispatch-key c))]
                                               (update c :params merge new-params)
                                               c)) %))))


(defn ready-state
  "Generate a ready-to-load state with all of the necessary details to do
  remoting and merging."
  [{:keys [ident field params remote without query post-mutation post-mutation-params fallback parallel refresh marker target]
    :or   {remote :remote without #{} refresh [] marker true}}]
  (assert (or field query) "You must supply a query or a field/ident pair")
  (assert (or (not field) (and field (util/ident? ident))) "Field requires ident")
  (let [old-ast     (om/query->ast query)
        ast         (cond-> old-ast
                      (not-empty without) (elide-ast-nodes without)
                      params (inject-query-params params))
        query-field (first query)
        key         (if (util/join? query-field) (util/join-key query-field) query-field)
        query'      (om/ast->query ast)]
    (assert (or (not field) (= field key)) "Component fetch query does not match supplied field.")
    {::type                 :ready
     ::uuid                 #?(:cljs
                                    (uuid/uuid-string (uuid/make-random-squuid))
                               :clj (str (System/currentTimeMillis)))
     ::target               target
     ::remote               remote
     ::ident                ident                           ; only for component-targeted loads
     ::field                field                           ; for component-targeted load
     ::query                query'                          ; query, relative to root of db OR component
     ::post-mutation        post-mutation
     ::post-mutation-params post-mutation-params
     ::refresh              refresh
     ::marker               marker
     ::parallel             parallel
     ::fallback             fallback}))

(defn mark-ready
  "Place a ready-to-load marker into the application state. This should be done from
  a mutate function that is abstractly loading something. This is intended for internal use.

  See the `load-data` and `load-field` functions in `untangled.client.data-fetch` for the public API."
  [{:keys [state] :as config}]
  (swap! state update :untangled/ready-to-load (fnil conj []) (ready-state (merge {:marker true :refresh [] :without #{}} config))))

(defn data-target
  "Return the ident (if any) of the component related to the query in the data state marker. An ident is required
  to be present if the marker is targeting a field."
  [state] (::target state))
(defn data-ident
  "Return the ident (if any) of the component related to the query in the data state marker. An ident is required
  to be present if the marker is targeting a field."
  [state] (::ident state))
(defn data-query
  "Get the query that will be sent to the server as a result of the given data state marker"
  [state]
  (if (data-ident state)
    [{(data-ident state) (::query state)}]
    (::query state)))
(defn data-field
  "Get the target field (if any) from the data state marker"
  [state] (::field state))
(defn data-uuid
  "Get the UUID of the data fetch"
  [state] (::uuid state))
(defn data-marker?
  "Test if the user desires a copy of the state marker to appear in the app state at the data path of the target data."
  [state] (::marker state))
(defn data-refresh
  "Get the list of query keywords that should be refreshed (re-rendered) when this load completes."
  [state] (::refresh state))
(defn data-remote
  "Get the remote that this marker is meant to talk to"
  [state] (::remote state))
(defn data-query-key
  "Get the 'primary' query key of the data fetch. This is defined as the first keyword of the overall query (which might
  be a simple prop or join key for example)"
  [state]
  (let [ast  (om/query->ast (-> state ::query))
        node (-> ast :children first)]
    (:key node)))

(defn data-path
  "Get the app-state database path of the target of the load that the given data state marker is trying to load."
  [state]
  (let [target (data-target state)]
    (cond
      (vector? (data-ident state)) (data-ident state)
      (and (vector? target) (not-empty target)) target
      (and (vector? (data-ident state)) (keyword? (data-field state))) (conj (data-ident state) (data-field state))
      :otherwise [(data-query-key state)])))

(defn data-params
  "Get the parameters that the user wants to add to the first join/keyword of the data fetch query."
  [state] (::params state))

(defn data-exclusions
  "Get the keywords that should be (recursively) removed from the query that will be sent to the server."
  [state] (::without state))

;; Setters
(letfn [(set-type [state type params]
          (merge state {::type   type
                        ::params params}))]
  (defn set-ready!
    "Returns a state (based on the input state) that is in the 'ready' to load state."
    ([state] (set-ready! state nil))
    ([state params] (set-type state :ready params)))
  (defn set-loading!
    "Returns a marker (based on the input state) that is in the loading state (and ensures that it has a UUID)"
    ([state] (set-loading! state nil))
    ([state params] (let [rv (set-type state :loading params)]
                      (with-meta rv {:state rv}))))
  (defn set-failed!
    "Returns a marker (based on the input state) that is in the error state"
    ([state] (set-failed! state nil))
    ([state params]
     (set-type state :failed params))))

(defn full-query
  "Composes together the queries of a sequence of data states into a single query."
  [items] (vec (mapcat (fn [item] (data-query item)) items)))

(defn- set-global-loading [reconciler]
  "Sets the global :ui/loading-data to false if there are no loading fetch states in the entire app-state, otherwise sets to true."
  (let [state-atom (om/app-state reconciler)
        loading?   (boolean (seq (get @state-atom :untangled/loads-in-progress)))]
    (swap! state-atom assoc :ui/loading-data loading?)))

(defn relocate-targeted-results
  "For items that are manually targeted, move them in app state from their result location to their target location."
  [state-atom items]
  (doseq [item items]
    (let [default-target  [(data-query-key item)]
          field-target    (conj (or (data-ident item) []) (::field item))
          explicit-target (or (::target item) [])
          relocate?       (and (not-empty explicit-target)
                            (not= explicit-target field-target)
                            (not= explicit-target default-target))]
      (when relocate?
        (let [value (get-in @state-atom default-target)]
          (swap! state-atom (fn [m]
                              (-> m
                                (dissoc (data-query-key item))
                                (assoc-in explicit-target value)))))))))

(defn- loaded-callback
  "Generates a callback that processes all of the post-processing steps once a remote load has completed. This includes:

  - Marking the items that were queries for but not returned as 'missing' (see documentation on mark and sweep of db)
  - Refreshing elements of the UI that were included in the data fetch :refresh option
  - Removing loading markers related to the executed loads that were not overwritten by incoming data
  - Merging the incoming data into the normalized database
  - Running post-mutations for any fetches that completed
  - Updating the global loading marker
  - Forcing a global re-render if post-mutations ran (may change in future versions)
  "
  [reconciler]
  (fn [response items]
    (let [query              (full-query items)
          loading-items      (into #{} (map set-loading! items))
          refresh-set        (into #{:ui/loading-data} (mapcat data-refresh items))
          to-refresh         (vec refresh-set)
          marked-response    (plumbing/mark-missing response query)
          app-state          (om/app-state reconciler)
          ran-mutations      (atom false)
          remove-markers     (fn [] (doseq [item loading-items]
                                      (swap! app-state (fn [s]
                                                         (cond-> s
                                                           :always (update :untangled/loads-in-progress disj (data-uuid item))
                                                           (data-marker? item) (assoc-in (data-path item) nil))))))
          run-post-mutations (fn [] (doseq [item loading-items]
                                      (when-let [mutation-symbol (::post-mutation item)]
                                        (reset! ran-mutations true)
                                        (let [params (or (::post-mutation-params item) {})]
                                          (some->
                                            (m/mutate {:state (om/app-state reconciler)} mutation-symbol params)
                                            :action
                                            (apply []))))))]
      (remove-markers)
      (om/merge! reconciler marked-response query)
      (relocate-targeted-results app-state loading-items)
      (run-post-mutations)
      (set-global-loading reconciler)
      (if (contains? refresh-set :untangled/force-root)
        (udom/force-render reconciler)
        (udom/force-render reconciler to-refresh)))))

(defn- error-callback
  "Generates a callback that is used whenever a hard server error occurs (status code 400+ or network error).

  The generated callback:

  - Replaces affected loading markers with error markers (if :marker is true on the load item)
  - Runs fallbacks associated with the loads
  - Sets the global error marker (:untangled/server-error)
  - Refreshes UI
  "
  [reconciler]
  (fn [error items]
    (let [loading-items (into #{} (map set-loading! items))
          app-state     (om/app-state reconciler)
          refresh-set   (into #{:ui/loading-data} (mapcat data-refresh items))
          to-refresh    (vec refresh-set)
          ran-fallbacks (atom false)
          mark-errors   (fn []
                          (swap! app-state assoc :untangled/server-error error)
                          (doseq [item loading-items]
                            (swap! app-state (fn [s]
                                               (cond-> s
                                                 (data-marker? item) (update-in (conj (data-path item) :ui/fetch-state) set-failed! error)
                                                 :always (update :untangled/loads-in-progress disj (data-uuid item)))))))
          run-fallbacks (fn [] (doseq [item loading-items]
                                 (when-let [fallback-symbol (::fallback item)]
                                   (reset! ran-fallbacks true)
                                   (some->
                                     (m/mutate {:state app-state} fallback-symbol {:error error})
                                     :action
                                     (apply [])))))]
      (mark-errors)
      (om/merge! reconciler {:ui/react-key (udom/unique-key)})
      (run-fallbacks)
      (set-global-loading reconciler)
      (if (contains? refresh-set :untangled/force-root)
        (udom/force-render reconciler)
        (udom/force-render reconciler to-refresh)))))
