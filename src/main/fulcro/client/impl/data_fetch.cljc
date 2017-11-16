(ns fulcro.client.impl.data-fetch
  (:require [fulcro.client.impl.parser :as op]
            [fulcro.client.primitives :as prim]
            [fulcro.client.impl.protocols :as omp]
            [fulcro.util :as util]
            [fulcro.client.util :refer [force-render integrate-ident]]
            [clojure.walk :refer [prewalk]]
            [clojure.set :as set]
            [fulcro.client.logging :as log]
            [fulcro.history :as hist]
            [fulcro.client.mutations :as m]
            [fulcro.client.impl.protocols :as p]
    #?(:clj
            [clojure.future :refer :all])
            [clojure.spec.alpha :as s]))

(defn optional [pred] (s/or :nothing nil? :value pred))
(s/def ::type keyword?)
(s/def ::uuid string?)
(s/def ::target (optional vector?))
(s/def ::field (optional keyword?))
(s/def ::post-mutation (optional symbol?))
(s/def ::post-mutation-params (optional map?))
(s/def ::refresh (optional vector?))
(s/def ::marker (s/or :kw keyword? :bool boolean? :nothing nil?))
(s/def ::parallel (optional boolean?))
(s/def ::fallback (optional symbol?))
(s/def ::original-env map?)
(s/def ::load-marker (s/keys :req [::type ::uuid ::prim/query ::original-env ::hist/tx-time]
                       :opt [::target ::prim/remote ::prim/ident ::field ::post-mutation-params ::post-mutation ::refresh ::marker ::parallel ::fallback]))

(s/def ::on-load fn?)
(s/def ::on-error fn?)
(s/def ::load-descriptors (s/coll-of ::load-marker))
(s/def ::payload (s/keys :req [::prim/query ::on-load ::on-error ::hist/history-atom ::hist/tx-time] :opt [::load-descriptors]))
(s/def ::network-error any?)
(s/def ::network-result (s/keys :opt [::load-descriptors ::network-error]))

(declare data-marker data-remote data-target data-path data-uuid data-field data-query-key data-query set-loading! full-query loaded-callback error-callback data-marker?)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation for public api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; Built-in mutation for adding a remote query to the network requests.
(defn data-state?
  "Test if the given bit of state is a data fetch state-tracking marker"
  [state] (and (map? state) (contains? state ::type)))

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

(defn is-direct-table-load? [load-marker]
  (and
    (not (data-field load-marker))
    (util/ident? (data-query-key load-marker))))

(def marker-table
  :ui.fulcro.client.data-fetch.load-markers/by-id)

(defn- place-load-marker [state-map marker]
  (let [marker-id      (data-marker marker)
        legacy-marker? (-> marker-id keyword? not)]
    (if legacy-marker?
      (update-in state-map (data-path marker)
        (fn [current-val]
          (if (is-direct-table-load? marker)
            (when (map? current-val) (assoc current-val :ui/fetch-state marker))
            {:ui/fetch-state marker})))
      (assoc-in state-map [marker-table marker-id] marker))))

(defn- place-load-markers
  "Place load markers in the app state at their data paths so that UI rendering can see them."
  [state-map items-to-load]
  (reduce (fn [s item]
            (let [i (set-loading! item)]
              (cond-> (update s :fulcro/loads-in-progress (fnil conj #{}) (data-uuid i))
                (data-marker? i) (place-load-marker i))))
    state-map items-to-load))

(s/fdef place-load-markers
  :args (s/cat :state map? :items ::load-descriptors)
  :ret map?)

(defn earliest-load-time
  "Given a sequence of load markers, returns the history tx-time of the earliest one. Returns hist/max-tx-time if there
  are no markers or none have a time."
  [load-markers]
  (reduce min hist/max-tx-time (map ::hist/tx-time load-markers)))

(defn mark-parallel-loading!
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
  (let [state                (prim/app-state reconciler)
        queued-items         (get @state :fulcro/ready-to-load)
        is-eligible?         (fn [item] (and (::parallel item) (= remote-name (data-remote item))))
        other-items-loading? (boolean (seq (get @state :fulcro/loads-in-progress)))
        items-to-load        (filter is-eligible? queued-items)
        remaining-items      (filter (comp not is-eligible?) queued-items)
        loading?             (or (boolean (seq items-to-load)) other-items-loading?)
        history-atom         (prim/get-history reconciler)
        ok                   (loaded-callback reconciler)
        error                (error-callback reconciler)
        tx-time              (earliest-load-time items-to-load)]
    (when-not (empty? items-to-load)
      (swap! state (fn [s] (-> s
                             (place-load-markers items-to-load)
                             (assoc :ui/loading-data loading? :fulcro/ready-to-load remaining-items))))
      (for [item items-to-load]
        {::prim/query        (full-query [item])
         ::hist/tx-time      tx-time
         ::hist/history-atom history-atom
         ::on-load           ok
         ::on-error          error
         ::load-descriptors  [item]}))))

(s/fdef mark-parallel-loading!
  :args (s/cat :remote keyword? :reconciler prim/reconciler?)
  :ret ::load-descriptors)

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
  asks for `[{:entitlements [:bar]}]`. Fulcro merges these into a single query
  [{:entitlements [:foo]} {:entitlements [:bar]}]. However, the response to a query
  is a map, and such a query would result in the backend parser being called twice (once per key in the subquery)
  but one would stomp on the other. Thus, this function ensures such accidental collisions are
  not combined into a single network request."
  [items-ready-to-load]
  (let [items-to-load-now (->> items-ready-to-load
                            (dedupe-by (fn [item]
                                         (->> (data-query item)
                                           (map join-key-or-nil))))
                            vec)
        is-loading-now?   (set items-to-load-now)
        items-to-defer    (->> items-ready-to-load
                            (remove is-loading-now?)
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
  (let [state                   (prim/app-state reconciler)
        is-eligible?            (fn [item] (= remote (data-remote item)))
        all-items               (get @state :fulcro/ready-to-load)
        items-ready-to-load     (filter is-eligible? all-items)
        items-for-other-remotes (filter (comp not is-eligible?) all-items)
        other-items-loading?    (boolean (seq (get @state :fulcro/loads-in-progress)))
        [items-to-load-now items-to-defer] (split-items-ready-to-load items-ready-to-load)
        remaining-items         (concat items-for-other-remotes items-to-defer)
        loading?                (or (boolean (seq items-to-load-now)) other-items-loading?)
        ; CAUTION: We use the earliest time of all items, so that we don't accidentally clear history for something we have not even sent.
        tx-time                 (earliest-load-time all-items)]
    (when-not (empty? items-to-load-now)
      (let [history-atom (prim/get-history reconciler)]
        (when history-atom
          (swap! history-atom hist/remote-activity-started remote tx-time))
        (swap! state (fn [s]
                       (-> s
                         (place-load-markers items-to-load-now)
                         (assoc :ui/loading-data loading? :fulcro/ready-to-load remaining-items))))
        {::prim/query        (full-query items-to-load-now)
         ::hist/history-atom (prim/get-history reconciler)
         ::hist/tx-time      tx-time
         ::on-load           (loaded-callback reconciler)
         ::on-error          (error-callback reconciler)
         ::load-descriptors  items-to-load-now}))))

(s/fdef mark-loading
  :args (s/cat :remote keyword? :reconciler prim/reconciler?)
  :ret ::payload)

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers -- not intended for public use
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn elide-ast-nodes
  "Remove items from a query (AST) that have a key listed in the elision-set"
  [{:keys [key union-key children] :as ast} elision-set]
  (let [union-elision? (contains? elision-set union-key)]
    (when-not (or union-elision? (contains? elision-set key))
      (when (and union-elision? (<= (count children) 2))
        (log/warn "Unions are not designed to be used with fewer than two children. Check your calls to Fulcro
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
      (log/error (str "Error: You attempted to add parameters for " (pr-str unknown-keys) " to top-level key(s) of " (pr-str (prim/ast->query ast)))))
    (update-in ast [:children] #(map (fn [c] (if-let [new-params (get params (:dispatch-key c))]
                                               (update c :params merge new-params)
                                               c)) %))))


(defn ready-state
  "Generate a ready-to-load state with all of the necessary details to do
  remoting and merging."
  [{:keys [ident field params remote without query post-mutation post-mutation-params fallback parallel refresh marker target env]
    :or   {remote :remote without #{} refresh [] marker true}}]
  (assert (or field query) "You must supply a query or a field/ident pair")
  (assert (or (not field) (and field (util/ident? ident))) "Field requires ident")
  (let [old-ast     (prim/query->ast query)
        ast         (cond-> old-ast
                      (not-empty without) (elide-ast-nodes without)
                      (and field params (not (contains? params field))) (inject-query-params {field params})
                      params (inject-query-params params))
        query-field (first query)
        key         (if (util/join? query-field) (util/join-key query-field) query-field)
        query'      (prim/ast->query ast)]
    (assert (or (not field) (= field key)) "Component fetch query does not match supplied field.")
    {::type                 :ready
     ::uuid                 #?(:cljs (str (cljs.core/random-uuid))
                               :clj  (str (System/currentTimeMillis)))
     ::target               target
     ::prim/remote          remote
     ::prim/ident           ident                           ; only for component-targeted loads
     ::field                field                           ; for component-targeted load
     ::prim/query           query'                          ; query, relative to root of db OR component
     ::post-mutation        post-mutation
     ::post-mutation-params post-mutation-params
     ::refresh              refresh
     ::marker               marker
     ::parallel             parallel
     ::fallback             fallback
     ; stored on metadata so it doesn't interfere with serializability (this marker ends up in state)
     ::original-env         (with-meta {} env)
     ::hist/tx-time         (if (some-> env :reconciler)
                              (prim/get-current-time (:reconciler env))
                              (do
                                (log/warn "Data fetch request created without a reconciler. No history time available. This could affect auto-error recovery operation.")
                                hist/max-tx-time))}))

(defn mark-ready
  "Place a ready-to-load marker into the application state. This should be done from
  a mutate function that is abstractly loading something. This is intended for internal use.

  See the `load` and `load-field` functions in `fulcro.client.data-fetch` for the public API."
  [{:keys [env] :as config}]
  (let [state        (get env :state)
        marker?      (not (identical? false (:marker config)))
        load-request (ready-state (merge {:marker true :refresh [] :without #{} :env env} config))]
    (swap! state (fn [s]
                   (cond-> (update s :fulcro/ready-to-load (fnil conj []) load-request)
                     marker? (place-load-marker load-request))))))

(defn data-target
  "Return the ident (if any) of the component related to the query in the data state marker. An ident is required
  to be present if the marker is targeting a field."
  [state] (::target state))
(defn data-ident
  "Return the ident (if any) of the component related to the query in the data state marker. An ident is required
  to be present if the marker is targeting a field."
  [state] (::prim/ident state))
(defn data-query
  "Get the query that will be sent to the server as a result of the given data state marker"
  [state]
  (if (data-ident state)
    [{(data-ident state) (::prim/query state)}]
    (::prim/query state)))
(defn data-field
  "Get the target field (if any) from the data state marker"
  [state] (::field state))
(defn data-uuid
  "Get the UUID of the data fetch"
  [state] (::uuid state))
(defn data-marker
  "Returns the ID of the data marker, or nil/false if there isn't one. True means to use the old marker behavior of
  replacing the data in app state with a marker (DEPRECATED)"
  [state] (::marker state))
(defn data-marker?
  "Test if the user desires a copy of the state marker to appear in the app state at the data path of the target data."
  [state] (boolean (::marker state)))
(defn data-refresh
  "Get the list of query keywords that should be refreshed (re-rendered) when this load completes."
  [state] (::refresh state))
(defn data-remote
  "Get the remote that this marker is meant to talk to"
  [state] (::prim/remote state))
(defn data-query-key
  "Get the 'primary' query key of the data fetch. This is defined as the first keyword of the overall query (which might
  be a simple prop or join key for example)"
  [state]
  (let [ast  (prim/query->ast (-> state ::prim/query))
        node (-> ast :children first)]
    (:key node)))

(defn data-path
  "Get the app-state database path of the target of the load that the given data state marker is trying to load."
  [state]
  (let [target (data-target state)]
    (cond
      (and (nil? (data-field state)) (vector? target) (not-empty target)) target
      (and (vector? (data-ident state)) (keyword? (data-field state))) (conj (data-ident state) (data-field state))
      (util/ident? (data-query-key state)) (data-query-key state)
      :otherwise [(data-query-key state)])))

(defn data-params
  "Get the parameters that the user wants to add to the first join/keyword of the data fetch query."
  [state] (::params state))

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

(defn- set-global-loading! [reconciler]
  "Sets the global :ui/loading-data to false if there are no loading fetch states in the entire app-state, otherwise sets to true."
  (let [state-atom (prim/app-state reconciler)
        loading?   (boolean (seq (get @state-atom :fulcro/loads-in-progress)))]
    (swap! state-atom assoc :ui/loading-data loading?)))

(defn replacement-target? [t] (-> t meta ::replace-target boolean))
(defn prepend-target? [t] (-> t meta ::prepend-target boolean))
(defn append-target? [t] (-> t meta ::append-target boolean))
(defn multiple-targets? [t] (-> t meta ::multiple-targets boolean))

(defn special-target? [target]
  (boolean (seq (set/intersection (-> target meta keys) #{::replace-target ::append-target ::prepend-target ::multiple-targets}))))

(defn process-target
  ([state source-path target] (process-target state source-path target true))
  ([state source-path target remove-ok?]
   {:pre [(vector? target)]}
   (let [ident-to-place (cond (util/ident? source-path) source-path
                              (keyword? source-path) (get state source-path)
                              :else (get-in state source-path))
         many-idents?   (every? util/ident? ident-to-place)]
     (cond
       (and (util/ident? source-path)
         (not (special-target? target))) (-> state
                                           (assoc-in target ident-to-place))
       (not (special-target? target)) (cond->
                                        (assoc-in state target ident-to-place)
                                        remove-ok? (dissoc source-path))
       (multiple-targets? target) (cond-> (reduce (fn [s t] (process-target s source-path t false)) state target)
                                    (and (not (util/ident? source-path)) remove-ok?) (dissoc source-path))
       (and many-idents? (special-target? target)) (let [state            (if remove-ok?
                                                                            (dissoc state source-path)
                                                                            state)
                                                         target-has-many? (vector? (get-in state target))]
                                                     (if target-has-many?
                                                       (cond
                                                         (prepend-target? target) (update-in state target (fn [v] (vec (concat ident-to-place v))))
                                                         (append-target? target) (update-in state target (fn [v] (vec (concat v ident-to-place))))
                                                         :else state)
                                                       (assoc-in state target ident-to-place)))
       (special-target? target) (cond-> (dissoc state source-path)
                                  (prepend-target? target) (integrate-ident ident-to-place :prepend target)
                                  (append-target? target) (integrate-ident ident-to-place :append target)
                                  (replacement-target? target) (integrate-ident ident-to-place :replace target))
       :else state))))


(defn relocate-targeted-results!
  "For items that are manually targeted, move them in app state from their result location to their target location."
  [state-atom items]
  (swap! state-atom
    (fn [state-map]
      (reduce (fn [state item]
                (let [default-target  (data-query-key item)
                      explicit-target (or (data-target item) [])
                      relocate?       (and
                                        (nil? (data-field item))
                                        (not-empty explicit-target))]
                  (if relocate?
                    (process-target state default-target explicit-target)
                    state))) state-map items))))

(defn- remove-marker
  "Returns app-state without the load marker for the given item."
  [app-state item]
  (let [marker-id      (data-marker item)
        legacy-marker? (-> marker-id keyword? not)]
    (if legacy-marker?
      (let [path (data-path item)
            data (get-in app-state path)]
        (cond
          (and (map? data) (= #{:ui/fetch-state} (set (keys data)))) (assoc-in app-state path nil) ; to-many (will become a vector)
          (and (map? data) (contains? data :ui/fetch-state)) (update-in app-state path dissoc :ui/fetch-state)
          :else (assoc-in app-state path nil)))
      (update app-state marker-table dissoc marker-id))))

(defn callback-env
  "Build a callback env for post mutations and fallbacks"
  [reconciler load-request original-env]
  (let [state (prim/app-state reconciler)
        {:keys [::target ::prim/remote ::prim/ident ::field ::prim/query ::post-mutation ::post-mutation-params ::refresh ::marker ::parallel ::fallback]} load-request]
    (merge original-env
      {:state state
       :load-request
              (cond-> {:target target :remote remote :marker marker :server-query query :parallel (boolean parallel)}
                post-mutation (assoc :post-mutation post-mutation)
                post-mutation-params (assoc :post-mutation-params post-mutation-params)
                refresh (assoc :refresh refresh)
                fallback (assoc :fallback fallback))})))

(defn clear-history-activity!
  "Update the history atom with a new history that does not include activity for the given load markers"
  [history-atom load-markers]
  (when history-atom
    (swap! history-atom (fn [h]
                          (reduce (fn [hist {:keys [::prim/remote ::hist/tx-time]}]
                                    (log/debug (str "Clearing remote load activity on " remote " for tx-time " tx-time))
                                    (hist/remote-activity-finished hist (or remote :remote) tx-time)) load-markers)))))

(defn- loaded-callback
  "Generates a callback that processes all of the post-processing steps once a remote load has completed. This includes:

  - Marking the items that were queries for but not returned as 'missing' (see documentation on mark and sweep of db)
  - Refreshing elements of the UI that were included in the data fetch :refresh option
  - Removing loading markers related to the executed loads that were not overwritten by incoming data
  - Merging the incoming data into the normalized database
  - Running post-mutations for any fetches that completed
  - Updating the global loading marker
  - Triggering re-render for all data item refresh lists
  - Removing the activity from history tracking
  "
  [reconciler]
  (fn [response items]
    (let [query               (full-query items)
          loading-items       (into #{} (map set-loading! items))
          refresh-set         (into #{:ui/loading-data :ui/fetch-state marker-table} (mapcat data-refresh items))
          marked-response     (prim/mark-missing response query)
          to-refresh          (into (vec refresh-set) (remove symbol?) (keys marked-response))
          app-state           (prim/app-state reconciler)
          ran-mutations       (atom false)
          remove-markers!     (fn [] (doseq [item loading-items]
                                       (swap! app-state (fn [s]
                                                          (cond-> s
                                                            :always (update :fulcro/loads-in-progress disj (data-uuid item))
                                                            (data-marker? item) (remove-marker item))))))
          history             (prim/get-history reconciler)

          run-post-mutations! (fn [] (doseq [item loading-items]
                                       (when-let [mutation-symbol (::post-mutation item)]
                                         (reset! ran-mutations true)
                                         (let [params       (or (::post-mutation-params item) {})
                                               original-env (-> item ::original-env meta)]
                                           (some-> (m/mutate (callback-env reconciler item original-env) mutation-symbol params)
                                             :action
                                             (apply []))))))]
      (remove-markers!)
      (clear-history-activity! history loading-items)
      (prim/merge! reconciler marked-response query)
      (relocate-targeted-results! app-state loading-items)
      (run-post-mutations!)
      (set-global-loading! reconciler)
      (if (contains? refresh-set :fulcro/force-root)
        (prim/force-root-render! reconciler)
        (force-render reconciler to-refresh)))))

(defn record-network-error!
  "Record a network error in history"
  [reconciler items error]
  (when-let [history (prim/get-history reconciler)]
    (p/tick! reconciler)
    (swap! history hist/record-history-step (p/basis-t reconciler) {::hist/db-before      @(prim/app-state reconciler)
                                                                    ::hist/network-result {::load-descriptors items
                                                                                           ::network-error    error}
                                                                    ::hist/db-after       @(prim/app-state reconciler)})))
(defn- error-callback
  "Generates a callback that is used whenever a hard server error occurs (status code 400+ or network error).

  The generated callback:

  - Replaces affected loading markers with error markers (if :marker is true on the load item)
  - Runs fallbacks associated with the loads
  - Sets the global error marker (:fulcro/server-error)
  - Refreshes UI (from root if there were fallbacks)
  "
  [reconciler]
  (fn [error items]
    (record-network-error! reconciler items error)
    (let [loading-items (into #{} (map set-loading! items))
          app-state     (prim/app-state reconciler)
          refresh-set   (into #{:ui/loading-data :ui/fetch-state marker-table} (mapcat data-refresh items))
          to-refresh    (vec refresh-set)
          ran-fallbacks (atom false)
          history       (prim/get-history reconciler)
          mark-errors   (fn []
                          (swap! app-state assoc :fulcro/server-error error)
                          (doseq [item loading-items]
                            (swap! app-state (fn [s]
                                               (cond-> s
                                                 (and (data-marker? item) (keyword? (data-marker item))) (update-in [marker-table (data-marker item)] set-failed! error)
                                                 (data-marker? item) (update-in (conj (data-path item) :ui/fetch-state) set-failed! error)
                                                 :always (update :fulcro/loads-in-progress disj (data-uuid item)))))))
          run-fallbacks (fn [] (doseq [item loading-items]
                                 (when-let [fallback-symbol (::fallback item)]
                                   (let [original-env (-> item ::original-env meta)
                                         env          (callback-env reconciler item original-env)]
                                     (reset! ran-fallbacks true)
                                     (some->
                                       (m/mutate env fallback-symbol {:error error})
                                       :action
                                       (apply []))))))]
      (mark-errors)
      (run-fallbacks)
      (set-global-loading! reconciler)
      (clear-history-activity! history loading-items)
      (prim/force-root-render! reconciler))))
