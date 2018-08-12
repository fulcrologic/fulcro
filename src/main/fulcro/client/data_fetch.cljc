(ns fulcro.client.data-fetch
  (:refer-clojure :exclude [load])
  (:require
    [clojure.walk :refer [walk prewalk]]
    [fulcro.client.primitives :as prim]
    [fulcro.client.impl.data-fetch :as impl]
    [fulcro.client.impl.data-targeting :as targeting]
    [fulcro.client.mutations :as m :refer [mutate defmutation]]
    [fulcro.logging :as log]
    [fulcro.client :as fc]
    [fulcro.util :as util]
    [clojure.set :as set]))

(declare load load-action load-field load-field-action)

(defn bool? [v]
  #?(:clj  (or (true? v) (false? v))
     :cljs (boolean? v)))

(def marker-table
  "The name of the table in which fulcro load markers are stored"
  impl/marker-table)

(defn multiple-targets [& targets]
  (apply targeting/multiple-targets targets))

(defn prepend-to [target]
  (targeting/prepend-to target))

(defn append-to [target]
  (targeting/append-to target))

(defn replace-at [target]
  (targeting/replace-at target))

(defn- computed-refresh
  "Computes the refresh for the load by ensuring the loaded data is on the
  list of things to re-render."
  [explicit-refresh load-key target]
  (vec (let [result     (conj (set explicit-refresh))
             result     (if (or (nil? target) (util/ident? load-key))
                          (conj result load-key)
                          result)
             add-target (fn [r t]
                          (cond
                            (and (vector? t) (>= (count t) 2)) (conj r (vec (take 2 t)))
                            (vector? t) (conj r (first t))
                            :else (conj r t)))]
         (cond
           (impl/multiple-targets? target) (reduce (fn [refresh t] (add-target refresh t)) result target)
           target (add-target result target)
           :else result))))

(defn load-params*
  "Internal function to validate and process the parameters of `load` and `load-action`."
  [state-map server-property-or-ident class-or-factory {:keys [target params marker refresh parallel post-mutation post-mutation-params
                                                               fallback remote focus without initialize abort-id]
                                                        :or   {remote     :remote marker true parallel false refresh [] without #{}
                                                               initialize false}}]
  {:pre [(or (nil? target) (vector? target))
         (or (nil? post-mutation) (symbol? post-mutation))
         (or (nil? fallback) (symbol? fallback))
         (or (nil? post-mutation-params) (map? post-mutation-params))
         (vector? refresh)
         (or (nil? params) (map? params))
         (set? without)
         (or (nil? focus) (vector? focus))
         (or (util/ident? server-property-or-ident) (keyword? server-property-or-ident))]}
  (let [query' (if class-or-factory
                 (cond-> (prim/get-query class-or-factory state-map)
                   focus (prim/focus-subquery focus))
                 nil)
        query  (cond
                 (and class-or-factory (map? params)) `[({~server-property-or-ident ~query'} ~params)]
                 class-or-factory [{server-property-or-ident query'}]
                 (map? params) [(list server-property-or-ident params)]
                 :else [server-property-or-ident])
        marker (if (and (true? marker) (impl/special-target? target)) (do
                                                                (log/warn (str "Load of " server-property-or-ident ": Boolean load marker not allowed. Turned off so load target will not overwrite a to-many relation. To fix this warning, set :marker to false or a marker ID."))
                                                                false) marker)]
    {:query                query
     :remote               remote
     :target               target
     :focus                focus
     :without              without
     :post-mutation        post-mutation
     :post-mutation-params post-mutation-params
     :initialize           (when (and initialize class-or-factory server-property-or-ident)
                             (let [class (if-let [c (-> class-or-factory meta :class)]
                                           c class-or-factory)]
                               {server-property-or-ident (cond
                                                           (map? initialize) initialize
                                                           (and initialize (prim/has-initial-app-state? class)) (prim/get-initial-state class {})
                                                           :else {})}))
     :refresh              (computed-refresh refresh server-property-or-ident target)
     :marker               marker
     :parallel             parallel
     :abort-id             abort-id
     :fallback             fallback}))

(defn load-mutation
  "Generates a transaction expression for a load mutation. It includes a follow-on read for :ui/loading-data. The args
  must be a map of the parameters usable from `load`. Returns a complete tx (as a vector), not just the mutation
  since follow-on reads are part of the mutation. You may use `concat` to join this with additional expressions."
  [load-args]
  {:pre [(or (nil? (:refresh load-args)) (vector? (:refresh load-args)))]}
  (let [refresh (or (:refresh load-args) [])]
    (into [(list 'fulcro/load load-args) :ui/loading-data] refresh)))

(defn load
  "Load data from the server.

  This function triggers a server interaction and normalizes the server response into your app state database. During
  operation it also adds (by default) fetch markers into the app state so you can show busy indicators on the UI
  components that are waiting for data. The `:target` parameter can be used to place the data somewhere besides app
  state root (which is the default).

  The server will receive a query of the form: [({server-property (prim/get-query class-or-factory)} params)], which
  a Fulcro parser will correctly parse as a join on server-property with the given subquery and params. See the AST and
  instructions on parsing queries in the developer's guide.

  Parameters:
  - `app-or-comp-or-reconciler` : A component instance, Fulcro application, or reconciler
  - `server-property-or-ident` : A keyword or ident that represents the root of the query to send to the server. If this is an ident
  you are loading a specific entity from the database into a local app db table. A custom target will be ignored.
  - `class-or-factory` : A component that implements IQuery, or a factory for it (if using dynamic queries). This will be combined with `server-property` into a join for the server query. Needed to normalize results.
    class-or-factory can be nil, in which case the resulting server query will not be a join.
  - `config` : A map of load configuration parameters.

  Config (all optional):
  - `target` - An assoc-in path at which to put the result of the Subquery (as an edge (normalized) or value (not normalized)).
    Can also be special targets (multiple-targets, append-to,
    prepend-to, or replace-at). If you are loading by keyword (into root), then this relocates the result (ident or value) after load.
    When loading an entity (by ident), then this option will place additional idents at the target path(s) that point to that entity.
  - `initialize` - Optional. If `true`, uses `get-initial-state` on class-or-factory to  get a basis for merge of the result. This allows you
    to use initial state to pre-populate loads with things like UI concerns. If `:initialize` is passed a map, then it uses that as
    the base target merge value for class-or-factory instead.
  - `remote` - Optional. Keyword name of the remote that this load should come from.
  - `params` - Optional parameters to add to the generated query
  - `marker` - Boolean to determine if you want a fetch-state marker in your app state. Defaults to true. Add `:ui/fetch-state` to the
  target component in order to see this data in your component.
  - `refresh` - A vector of keywords that will cause component re-renders after the final load/mutations. Same as follow-on
  reads in normal `transact!`
  - `parallel` - If true, indicates that this load does not have to go through the sequential network queue. Defaults to false.
  - `post-mutation` - A mutation (symbol) to run after the data is merged. Note, if target is supplied be sure your post mutation
  should expect the data at the targeted location. The `env` of that mutation will be the env of the load (if available), but will also include `:load-request`.
  - `post-mutation-params` - An optional map  that will be passed to the post-mutation when it is called. May only contain raw data, not code!
  - `fallback` - A mutation (symbol) to run if there is a server/network error. The `env` of the fallback will be the env of the load (if available), but will also include `:load-request`.
  - `focus` - An optional subquery to focus on some parts of the original query.
  - `without` - An optional set of keywords that should (recursively) be removed from the query.
  - `abort-id` - An ID (typically a keyword) that you can use to cancel the load via `fulcro.client/abort`.

  Notes on UI Refresh:
  The refresh list will automatically include what you load (as a non-duplicate):
  - When target is set and has 2+ elements: refresh will include an ident of the first two elements
     - e.g. `:target [:a 1 :thing]` -> `:refresh [[:a 1]]`
  - When target has a single element, refresh will include that element as a keyword
     - e.g. `:target [:thing]` -> `:refresh [:thing]`
  - When there is no target:
     - If prop-or-ident is a kw -> `:refresh [kw]`
     - If prop-or-ident is an ident -> `:refresh [ident]`
  In all cases, any explicit refresh things you include will not be dropped. The computed refresh list
  is essentially a `(-> original-refresh-list set add-computed-bits vec)`.
  "
  ([app-or-comp-or-reconciler server-property-or-ident class-or-factory] (load app-or-comp-or-reconciler server-property-or-ident class-or-factory {}))
  ([app-or-comp-or-reconciler server-property-or-ident class-or-factory config]
   {:pre [(or (prim/component? app-or-comp-or-reconciler)
            (prim/reconciler? app-or-comp-or-reconciler)
            #?(:cljs (implements? fc/FulcroApplication app-or-comp-or-reconciler)
               :clj  (satisfies? fc/FulcroApplication app-or-comp-or-reconciler)))]}
   (let [config                  (merge {:marker true :parallel false :refresh [] :without #{}} config)
         component-or-reconciler (if #?(:cljs (implements? fc/FulcroApplication app-or-comp-or-reconciler)
                                        :clj  (satisfies? fc/FulcroApplication app-or-comp-or-reconciler))
                                   (get app-or-comp-or-reconciler :reconciler)
                                   app-or-comp-or-reconciler)
         reconciler              (if (prim/reconciler? component-or-reconciler) component-or-reconciler (prim/get-reconciler component-or-reconciler))
         state                   (prim/app-state reconciler)
         mutation-args           (load-params* @state server-property-or-ident class-or-factory config)]
     (prim/transact! component-or-reconciler (load-mutation mutation-args)))))

#?(:cljs
   (defn load-action
     "
     See `load` for descriptions of parameters and config.

     Queue up a remote load from within an already-running mutation. Similar to `load`, but usable from
     within a mutation. IMPORTANT: Make sure you specify the `:remote` parameter to this function, as
     well as including a `remote-load` for that remote.

     Note the `:refresh` parameter is supported, and defaults to empty. If you want anything to refresh other than
     the targeted component you will want to include the :refresh parameter.

     To use this function make sure your mutation specifies a return value with a remote. The remote
     should use the helper function `remote-load` as it's value:

     { :remote (df/remote-load env)
       ; NOTE: :remote must be the keyword name of a legal remote in your system; however,
       ; You must still name the remote in the `load-action` if it is something other than default.
       :action (fn []
          (load-action env ...)
          ; other optimistic updates/state changes)}

     `env` is the mutation's environment parameter."
     ([env server-property-or-ident SubqueryClass] (load-action env server-property-or-ident SubqueryClass {}))
     ([env server-property-or-ident SubqueryClass config]
      {:pre [(and (map? env) (contains? env :state))]}
      (let [config    (merge {:marker true :parallel false :refresh [] :without #{}} config)
            state-map @(:state env)]
        (impl/mark-ready (assoc (load-params* state-map server-property-or-ident SubqueryClass config) :env env))))))

(defn load-field
  "Load a field of the current component. Runs `prim/transact!`.

  Parameters
  - `component`: The component (**instance**, not class). This component MUST have an Ident.
  - `field`: A field on the component's query that you wish to load.
  - `parameters` : A map of: (will also accept as named parameters)

    - `without`: See `load`
    - `params`: See `load`
    - `post-mutation`: See `load`
    - `post-mutation-params`: See `load`
    - `parallel`: See `load`
    - `fallback`: See `load`
    - `marker`: See `load`
    - `remote`: See `load`
    - `refresh`: See `load`
    - `abort-id`: See `load`

  NOTE: The :ui/loading-data attribute is always included in refresh. This means you probably don't want to
  query for that attribute near the root of your UI. Instead, create some leaf component with an ident that queries for :ui/loading-data
  using a link  query (e.g. `[:ui/loading-data '_]`). The presence of the ident on components will enable query optimization, which can
  improve your frame rate because we will not have to run a full root query.

  WARNING: If you're using dynamic queries, you won't really know what factory your parent is using,
  nor can you pass it as a parameter to this function. Therefore, it is not recommended to use load-field from within
  a component that has a dynamic query unless you can base it on the original static query (which
  is what this function will use).
  "
  [component field & params]
  (let [params    (if (map? (first params)) (first params) params)
        {:keys [without params remote post-mutation post-mutation-params fallback parallel refresh marker abort-id]
         :or   {remote :remote refresh [] marker true}} params
        state-map (some-> component prim/get-reconciler prim/app-state deref)]
    (when fallback (assert (symbol? fallback) "Fallback must be a mutation symbol."))
    (prim/transact! component (into [(list 'fulcro/load
                                       {:ident                (prim/get-ident component)
                                        :field                field
                                        :query                (prim/focus-query (prim/get-query component state-map) [field])
                                        :params               params
                                        :without              without
                                        :remote               remote
                                        :post-mutation        post-mutation
                                        :post-mutation-params post-mutation-params
                                        :parallel             parallel
                                        :marker               marker
                                        :refresh              refresh
                                        :abort-id             abort-id
                                        :fallback             fallback}) :ui/loading-data :ui.fulcro.client.data-fetch.load-markers/by-id (prim/get-ident component)] refresh))))

(defn load-field-action
  "Queue up a remote load of a component's field from within an already-running mutation. Similar to `load-field`
  but usable from within a mutation. Note the `:refresh` parameter is supported, and defaults to nothing, even for
  fields, in actions. If you want anything to refresh other than the targeted component you will want to use the
  :refresh parameter.

  `params` can be a map or named parameters, just like in `load-field`.

  To use this function make sure your mutation specifies a return value with a remote. The remote
  should use the helper function `remote-load` as it's value:

  { :remote (df/remote-load env)
    ; NOTE: :remote must be the keyword name of a legal remote in your system; however,
    ; You must still name the remote in the `load-action` if it is something other than default.
    :action (fn []
       (load-field-action ...)
       ; other optimistic updates/state changes)}

  It is preferable that you use `env` instead of `app-state` for the first argument, as this allows more details to
  be available for post mutations and fallbacks.
  "
  [env-or-app-state component-class ident field & params]
  (let [params    (if (map? (first params)) (first params) params)
        {:keys [without params remote post-mutation post-mutation-params fallback parallel refresh marker abort-id]
         :or   {remote :remote refresh [] marker true}} params
        env       (if (and (map? env-or-app-state) (contains? env-or-app-state :state))
                    env-or-app-state
                    {:state env-or-app-state})
        state-map (some-> env :state deref)]
    (impl/mark-ready
      {:env                  env
       :field                field
       :ident                ident
       :query                (prim/focus-query (prim/get-query component-class state-map) [field])
       :params               params
       :remote               remote
       :without              without
       :parallel             parallel
       :refresh              refresh
       :marker               marker
       :post-mutation        post-mutation
       :post-mutation-params post-mutation-params
       :abort-id             abort-id
       :fallback             fallback})))

(defn remote-load
  "Returns the correct value for the `:remote` side of a mutation that should act as a
  trigger for remote loads. Must be used in conjunction with running `load-action` or
  `load-field-action` in the `:action` side of the mutation (which queues the exact things to
  load)."
  [parsing-env]
  (let [ast (:ast parsing-env)]
    (assoc ast :key 'fulcro/load :dispatch-key 'fulcro/load)))

;; Predicate functions
(defn data-state? [state] (impl/data-state? state))
(defn ready? [state] (impl/ready? state))
(defn loading? [state] (impl/loading? state))
(defn failed? [state] (impl/failed? state))

#?(:clj (defn clj->js [m] m))

(defn lazily-loaded
  "Custom rendering for use while data is being lazily loaded using the data fetch methods
  load-collection and load-field.

  `data-render` : the render method to call once the data has been successfully loaded from
  the server. Can be a factory method or a React rendering function.

  `props` : the React properties for the element to be loaded.

  Optional:

  `ready-render` : the render method to call when the desired data has been marked as ready
  to load, but the server request has not yet been sent.

  `loading-render` : render method once the server request has been sent, and UI is waiting
  on the response

  `failed-render` : render method when the server returns a failure state for the requested data

  `not-present-render` : called when props is nil (helpful for differentiating between a nil and
  empty response from the server).

  Example Usage:

  ```
  (defui Thing
    static prim/IQuery
    (query [this] [{:thing2 (prim/get-query Thing2)}])
    Object
    (componentDidMount [this]
       (load-field this :thing2))

    (render [this]
      (let [thing2 (:thing2 (prim/props this))]
        (lazily-loaded ui-thing2 thing2))))

  (defui Thing2
    static prim/IQuery
    (query [this] [:ui/fetch-state])
    Object
    (render [this]
      (display-thing-2))

  (def ui-thing2 (prim/factory Thing2))
  ```"
  [data-render props & {:keys [ready-render loading-render failed-render not-present-render]
                        :or   {loading-render (fn [_] "Loading...")
                               ready-render   (fn [_] "Queued")
                               failed-render  (fn [_] "Loading error!")}}]

  (let [state (:ui/fetch-state props)]
    (cond
      (ready? state) (ready-render props)
      (loading? state) (loading-render props)
      (failed? state) (failed-render props)
      (and not-present-render (nil? props)) (not-present-render props)
      :else (data-render props))))

(defn refresh!
  ([component load-options]
   (load component (prim/get-ident component) (prim/react-type component) load-options))
  ([component]
   (load component (prim/get-ident component) (prim/react-type component))))

(defn- load* [env {:keys [post-mutation remote] :as config}]
  (when (and post-mutation (not (symbol? post-mutation))) (log/error "post-mutation must be a symbol or nil"))
  {(if remote remote :remote) true
   :action                    (fn [] (impl/mark-ready (assoc config :env env)))})

(defmethod mutate 'fulcro/load [env _ params] (load* env params))
(defmethod mutate `load [env _ params] (load* env params))

(defmutation run-deferred-transaction [{:keys [tx ref reconciler]}]
  (action [env]
    (let [reconciler (-> reconciler meta :reconciler)]
      #?(:clj  (prim/transact! reconciler ref tx)
         :cljs (js/setTimeout (fn [] (prim/transact! reconciler ref tx)) 1)))))

(defmutation deferred-transaction [{:keys [tx remote ref]}]
  (action [env]
    (let [{:keys [reconciler component] :as env} env
          reconciler (cond
                       reconciler reconciler
                       component (prim/get-reconciler component)
                       :otherwise nil)]
      (if reconciler
        (load-action env ::impl/deferred-transaction nil {:post-mutation        `run-deferred-transaction
                                                          :remote               remote
                                                          :marker               false
                                                          :post-mutation-params {:tx         tx
                                                                                 :ref        ref
                                                                                 :reconciler (with-meta {} {:reconciler reconciler})}})
        (log/error "Cannot defer transaction. Reconciler was not available. Tx = " tx))))
  (remote [env] (remote-load env)))

(defn- fallback-action*
  [env {:keys [action] :as params}]
  (some-> (mutate env action (dissoc params :action :execute)) :action (apply [])))

; A mutation that requests the installation of a fallback mutation on a transaction that should run if that transaction
; fails in a 'hard' way (e.g. network/server error). Data-related error handling should either be implemented as causing
; such a hard error, or as a post-mutation step.
(defmethod mutate 'tx/fallback [{:keys [target ast ref] :as env} _ {:keys [execute action] :as params}]
  (cond
    execute {:action #(fallback-action* env params)}
    target {target (if ref
                     (update ast :params assoc ::prim/ref ref)
                     true)}
    :else nil))

(defmethod mutate `fallback [env _ params] (mutate env 'tx/fallback params))

(defn fallback
  "Mutation: Add a fallback to the current tx. `action` is the symbol of the mutation to run if this tx fails due to
  network or server errors (bad status codes)."
  [{:keys [action]}]
  ; placeholder...this function is never actually used. It is here for docstring support only. See the defmethod above
  ; for actual implementation. Cannot use `defmutation`, because we have to derive the remote to target.
  )

(defn get-remotes
  "Returns the remote against which the given mutation will try to execute. Returns nil if it is not a remote mutation.
  `legal-remotes` is a set of legal remote names. Defaults to `#{:remote}`.

  Returns a set of the remotes that will be triggered for this mutation, which may be empty.
  "
  ([state-map dispatch-symbol] (get-remotes state-map dispatch-symbol #{:remote}))
  ([state-map dispatch-symbol legal-remotes]
   (letfn [(run-mutation [remote]
             (mutate {:ast    (prim/query->ast1 `[(~dispatch-symbol)])
                      :parser (constantly nil)
                      :target remote
                      :state  (atom state-map)} dispatch-symbol {}))]
     (reduce (fn [remotes r]
               (try
                 (let [mutation-map     (run-mutation r)
                       ks               (set (keys mutation-map))
                       possible-remotes (set/difference ks #{:action :refresh :keys :value})
                       active-now?      #(get mutation-map % false)]
                   (into remotes (filter active-now? possible-remotes)))
                 (catch #?(:clj Throwable :cljs :default) e
                   (log/error "Attempting to get the remotes for mutation " dispatch-symbol " threw an exception. Make sure that mutation is side-effect free!" e)
                   (reduced (if (seq remotes) remotes #{:remote})))))
       #{} legal-remotes))))
