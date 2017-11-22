(ns fulcro.client.data-fetch
  (:refer-clojure :exclude [load])
  (:require
    [clojure.walk :refer [walk prewalk]]
    [fulcro.client.primitives :as prim]
    [fulcro.client.impl.data-fetch :as impl]
    [fulcro.client.mutations :refer [mutate defmutation]]
    [fulcro.client.logging :as log]
    [fulcro.client.dom :as dom]
    [fulcro.client.core :as fc]
    [fulcro.util :as util]
    [clojure.set :as set]))

(declare load load-action load-field load-field-action)

(defn bool? [v]
  #?(:clj  (or (true? v) (false? v))
     :cljs (boolean? v)))

(def marker-table
  "The name of the table in which fulcro load markers are stored"
  impl/marker-table)

(defn- computed-refresh
  "Computes the refresh for the load by ensuring the loaded data is on the
  list of things to re-render."
  [explicit-refresh load-key target]
  (let [to-refresh       (set explicit-refresh)
        truncated-target (vec (take 2 target))
        target-ident     (when (util/ident? truncated-target) truncated-target)]
    (vec (cond
           (or
             (util/ident? load-key)
             (and (keyword? load-key) (nil? target))) (conj to-refresh load-key)
           target-ident (conj to-refresh target-ident)
           (= 1 (count truncated-target)) (conj to-refresh (first target))
           :else to-refresh))))

(defn multiple-targets [& targets]
  (with-meta (vec targets) {::impl/multiple-targets true}))

(defn prepend-to [target]
  (with-meta target {::impl/prepend-target true}))

(defn append-to [target]
  (with-meta target {::impl/append-target true}))

(defn replace-at [target]
  (with-meta target {::impl/replace-target true}))

(defn load-params*
  "Internal function to validate and process the parameters of `load` and `load-action`."
  [server-property-or-ident SubqueryClass {:keys [target params marker refresh parallel post-mutation post-mutation-params
                                                  fallback remote without]
                                           :or   {remote :remote marker true parallel false refresh [] without #{}}}]
  {:pre [(or (nil? target) (vector? target))
         (or (nil? marker) (bool? marker) (keyword? marker))
         (or (nil? post-mutation) (symbol? post-mutation))
         (or (nil? fallback) (symbol? fallback))
         (or (nil? post-mutation-params) (map? post-mutation-params))
         (vector? refresh)
         (or (nil? params) (map? params))
         (set? without)
         (or (util/ident? server-property-or-ident) (keyword? server-property-or-ident))
         (or (nil? SubqueryClass) #?(:cljs (implements? prim/IQuery SubqueryClass)
                                     :clj  (satisfies? prim/IQuery SubqueryClass)))]}
  (let [query (cond
                (and SubqueryClass (map? params)) `[({~server-property-or-ident ~(prim/get-query SubqueryClass)} ~params)]
                SubqueryClass [{server-property-or-ident (prim/get-query SubqueryClass)}]
                (map? params) [(list server-property-or-ident params)]
                :else [server-property-or-ident])]
    {:query                query
     :remote               remote
     :target               target
     :without              without
     :post-mutation        post-mutation
     :post-mutation-params post-mutation-params
     :refresh              (computed-refresh refresh server-property-or-ident target)
     :marker               marker
     :parallel             parallel
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

  The server will receive a query of the form: [({server-property (prim/get-query SubqueryClass)} params)], which
  a Fulcro parser will correctly parse as a join on server-property with the given subquery and params. See the AST and
  instructions on parsing queries in the developer's guide.

  Parameters:
  - `app-or-comp-or-reconciler` : A component instance, Fulcro application, or reconciler
  - `server-property-or-ident` : A keyword or ident that represents the root of the query to send to the server. If this is an ident
  you are loading a specific entity from the database into a local app db table. A custom target will be ignored.
  - `SubqueryClass` : An Om component that implements IQuery. This will be combined with `server-property` into a join for the server query. Needed to normalize results.
    SubqueryClass can be nil, in which case the resulting server query will not be a join.
  - `config` : A map of load configuration parameters.

  Config (all optional):
  - `target` - An assoc-in path at which to put the result of the Subquery. If supplied, the data AND load marker will appear
    at this path. If not supplied the data and marker will appear at `server-property` in the top-level of the client app state
    database. Ignored if you're loading via ident (the ident is your target).
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
  - `without` - An optional set of keywords that should (recursively) be removed from the query.

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
  ([app-or-comp-or-reconciler server-property-or-ident SubqueryClass] (load app-or-comp-or-reconciler server-property-or-ident SubqueryClass {}))
  ([app-or-comp-or-reconciler server-property-or-ident SubqueryClass config]
   {:pre [(or (prim/component? app-or-comp-or-reconciler)
            (prim/reconciler? app-or-comp-or-reconciler)
            #?(:cljs (implements? fc/FulcroApplication app-or-comp-or-reconciler)
               :clj  (satisfies? fc/FulcroApplication app-or-comp-or-reconciler)))]}
   (let [config                  (merge {:marker true :parallel false :refresh [] :without #{}} config)
         component-or-reconciler (if #?(:cljs (implements? fc/FulcroApplication app-or-comp-or-reconciler)
                                        :clj  (satisfies? fc/FulcroApplication app-or-comp-or-reconciler))
                                   (get app-or-comp-or-reconciler :reconciler)
                                   app-or-comp-or-reconciler)
         mutation-args           (load-params* server-property-or-ident SubqueryClass config)]
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
      (let [config (merge {:marker true :parallel false :refresh [] :without #{}} config)]
        (impl/mark-ready (assoc (load-params* server-property-or-ident SubqueryClass config) :env env))))))

(defn load-field
  "Load a field of the current component. Runs `prim/transact!`.

  Parameters
  - `component`: The component (**instance**, not class). This component MUST have an Ident.
  - `field`: A field on the component's query that you wish to load.

  Named Parameters:
  - `without`: See `load`
  - `params`: See `load`
  - `post-mutation`: See `load`
  - `post-mutation-params`: See `load`
  - `parallel`: See `load`
  - `fallback`: See `load`
  - `marker`: See `load`
  - `remote`: See `load`
  - `refresh`: See `load`

  NOTE: The :ui/loading-data attribute is always included in refresh. This means you probably don't want to
  query for that attribute near the root of your UI. Instead, create some leaf component with an ident that queries for :ui/loading-data
  using a link  query (e.g. `[:ui/loading-data '_]`). The presence of the ident on components will enable query optimization, which can
  improve your frame rate because we will not have to run a full root query.
  "
  [component field & {:keys [without params remote post-mutation post-mutation-params fallback parallel refresh marker]
                      :or   {remote :remote refresh [] marker true}}]
  {:pre [(or (nil? marker) (bool? marker) (keyword? marker))]}
  (when fallback (assert (symbol? fallback) "Fallback must be a mutation symbol."))
  (prim/transact! component (into [(list 'fulcro/load
                                     {:ident                (prim/get-ident component)
                                      :field                field
                                      :query                (prim/focus-query (prim/get-query component) [field])
                                      :params               params
                                      :without              without
                                      :remote               remote
                                      :post-mutation        post-mutation
                                      :post-mutation-params post-mutation-params
                                      :parallel             parallel
                                      :marker               marker
                                      :refresh              refresh
                                      :fallback             fallback}) :ui/loading-data :ui.fulcro.client.data-fetch.load-markers/by-id (prim/get-ident component)] refresh)))

(defn load-field-action
  "Queue up a remote load of a component's field from within an already-running mutation. Similar to `load-field`
  but usable from within a mutation. Note the `:refresh` parameter is supported, and defaults to nothing, even for
  fields, in actions. If you want anything to refresh other than the targeted component you will want to use the
  :refresh parameter.

  To use this function make sure your mutation specifies a return value with a remote. The remote
  should use the helper function `remote-load` as it's value:

  { :remote (df/remote-load env)
    ; NOTE: :remote must be the keyword name of a legal remote in your system; however,
    ; You must still name the remote in the `load-action` if it is something other than default.
    :action (fn []
       (load-field-action ...)
       ; other optimistic updates/state changes)}

  It is preferable that you use `env` instead of `app-state` for the first argument, as this allows more details to
  be available for post mutations and fallbacks."
  [env-or-app-state component-class ident field & {:keys [without remote params post-mutation post-mutation-params fallback parallel refresh marker]
                                                   :or   {remote :remote refresh [] marker true}}]
  {:pre [(or (nil? marker) (bool? marker) (keyword? marker))]}
  (impl/mark-ready
    {:env                  (if (and (map? env-or-app-state) (contains? env-or-app-state :state))
                             env-or-app-state
                             {:state env-or-app-state})
     :field                field
     :ident                ident
     :query                (prim/focus-query (prim/get-query component-class) [field])
     :params               params
     :remote               remote
     :without              without
     :parallel             parallel
     :refresh              refresh
     :marker               marker
     :post-mutation        post-mutation
     :post-mutation-params post-mutation-params
     :fallback             fallback}))

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
                        :or   {loading-render (fn [_] (dom/div (clj->js {"className" "lazy-loading-load"}) "Loading..."))
                               ready-render   (fn [_] (dom/div (clj->js {"className" "lazy-loading-ready"}) "Queued"))
                               failed-render  (fn [_] (dom/div (clj->js {"className" "lazy-loading-failed"}) "Loading error!"))}}]

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



(comment
  "ptransact! transactions are converted as follows:"
  [(f) (g) (h)] -> [(f) (deferred-transaction {:remote remote-of-f
                                               :tx     [(g) (deferred-transaction {:remote  remote-of-g
                                                                                   :post-tx [(h)]})]})]
  "And the deffered transaction triggers a mock load that runs the given tx through the post-mutatio mechanism. There
  is a short-circuit bit of logic in the actual send to networking to keep it uninvolved (no net traffic).")

(defn get-remote
  "Returns the remote against which the given mutation will try to execute. Returns nil if it is not a remote mutation"
  [dispatch-symbol]
  (try
    (let [mutation-map (mutate {:state (atom {})} dispatch-symbol {})
          ks           (set (keys mutation-map))
          remotes      (set/difference ks #{:action :refresh :keys :value})
          remote       (first remotes)]
      (if (and remote (get mutation-map remote false))
        remote
        nil))
    (catch #?(:clj Throwable :cljs :default) e
      (log/error (str "Attempting to get the declared remote for mutation " dispatch-symbol " threw an exception. Make sure that mutation is side-effect free!"))
      nil)))

(defn pessimistic-transaction->transaction
  "Converts a sequence of calls as if each call should run in sequence (deferring even the optimistic side until
  the prior calls have completed in a full-stack manner), and returns a tx that can be submitted via the normal
  `transact!`."
  [tx]
  (let [ast-nodes     (:children (prim/query->ast tx))
        {calls true reads false} (group-by #(= :call (:type %)) ast-nodes)
        first-call    (first calls)
        dispatch-key  (:dispatch-key first-call)
        remote        (get-remote dispatch-key)
        tx-to-run-now (into [(prim/ast->query first-call)] (prim/ast->query {:type :root :children reads}))]
    (if (seq (rest calls))
      (let [remaining-tx (prim/ast->query {:type :root :children (into (vec (rest calls)) reads)})]
        (into tx-to-run-now `[(deferred-transaction {:remote ~remote
                                                     :tx     ~(pessimistic-transaction->transaction remaining-tx)})]))
      tx-to-run-now)))

(defmutation run-deferred-transaction [{:keys [tx reconciler]}]
  (action [env]
    (let [reconciler (-> reconciler meta :reconciler)]
      (log/info "Running deferred mutation " tx)
      #?(:clj  (prim/transact! reconciler tx)
         :cljs (js/setTimeout (fn [] (prim/transact! reconciler tx)) 1)))))

(defmutation deferred-transaction [{:keys [tx remote]}]
  (action [env]
    (log/info (str "Queing deferred transaction " tx " against remote " remote))
    (let [{:keys [reconciler component] :as env} env
          reconciler (cond
                       reconciler reconciler
                       component (prim/get-reconciler component)
                       :otherwise nil)]
      (if reconciler
        (load-action env ::impl/deferred-transaction nil {:post-mutation        `run-deferred-transaction
                                                          :remote               remote
                                                          :post-mutation-params {:tx         tx
                                                                                 :reconciler (with-meta {} {:reconciler reconciler})}})
        (log/error (str "Cannot defer transaction. Reconciler was not available. Tx = " tx)))))
  (remote [env] (remote-load env)))

(defn- fallback-action*
  [env {:keys [action] :as params}]
  (some-> (mutate env action (dissoc params :action :execute)) :action (apply [])))

; A mutation that requests the installation of a fallback mutation on a transaction that should run if that transaction
; fails in a 'hard' way (e.g. network/server error). Data-related error handling should either be implemented as causing
; such a hard error, or as a post-mutation step.
(defmethod mutate 'tx/fallback [env _ {:keys [execute] :as params}]
  (if execute
    {:action #(fallback-action* env params)}
    {:remote true}))

(defmutation fallback
  "Om mutation: Add a fallback for network failures to the transaction.

  Parameters:
  `action` - The symbol of the mutation to run on error."
  [{:keys [action] :as params}]
  (action [env] (when (:execute params) (fallback-action* env params)))
  (remote [env] (not (:execute params))))
