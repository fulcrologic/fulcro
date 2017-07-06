(ns untangled.client.data-fetch
  (:refer-clojure :exclude [load])
  (:require
    [clojure.walk :refer [walk prewalk]]
    [om.next :as om]
    [untangled.client.impl.data-fetch :as impl]
    [untangled.client.mutations :refer [mutate defmutation]]
    [untangled.client.logging :as log]
    [om.dom :as dom]
    [untangled.client.core :as uc]
    [om.util :as util]))

(declare load load-action load-field load-field-action)

(defn- computed-refresh
  "Computes the refresh for the load by ensuring the loaded data is on the Om
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

(defn load-params*
  "Internal function to validate and process the parameters of `load` and `load-action`."
  [server-property-or-ident SubqueryClass {:keys [target params marker refresh parallel post-mutation post-mutation-params
                                                  fallback remote without]
                                           :or   {remote :remote marker true parallel false refresh [] without #{}}}]
  {:pre [(or (nil? target) (vector? target))
         (or (nil? post-mutation) (symbol? post-mutation))
         (or (nil? fallback) (symbol? fallback))
         (or (nil? post-mutation-params) (map? post-mutation-params))
         (vector? refresh)
         (or (nil? params) (map? params))
         (set? without)
         (or (util/ident? server-property-or-ident) (keyword? server-property-or-ident))
         (or (nil? SubqueryClass) #?(:cljs (implements? om/IQuery SubqueryClass)
                                     :clj  (satisfies? om/IQuery SubqueryClass)))]}
  (let [query (cond
                (and SubqueryClass (map? params)) `[({~server-property-or-ident ~(om/get-query SubqueryClass)} ~params)]
                SubqueryClass [{server-property-or-ident (om/get-query SubqueryClass)}]
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
  "Generates an Om transaction expression for a load mutation. It includes a follow-on read for :ui/loading-data. The args
  must be a map of the parameters usable from `load`. Returns a complete Om expression (vector), not just the mutation
  since follow-on reads are part of the mutation. You may use `concat` to join this with additional expressions."
  [load-args]
  {:pre [(or (nil? (:refresh load-args)) (vector? (:refresh load-args)))]}
  (let [refresh (or (:refresh load-args) [])]
    (into [(list 'untangled/load load-args) :ui/loading-data] refresh)))

(defn load
  "Load data from the server.

  This function triggers a server interaction and normalizes the server response into your app state database. During
  operation it also adds (by default) fetch markers into the app state so you can show busy indicators on the UI
  components that are waiting for data. The `:target` parameter can be used to place the data somewhere besides app
  state root (which is the default).

  The server will receive an Om query of the form: [({server-property (om/get-query SubqueryClass)} params)], which
  the Om parser will correctly parse as a Join on server-property with the given subquery and params. See Om AST and
  instructions on parsing Om queries.

  Parameters:
  - `app-or-comp-or-reconciler` : An Om component instance, Untangled application, or Om reconciler
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
  should expect the data at the targeted location.
  - `post-mutation-params` - An optional map  that will be passed to the post-mutation when it is called. May only contain raw data, not code!
  - `fallback` - A mutation (symbol) to run if there is a server/network error.
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
   {:pre [(or (om/component? app-or-comp-or-reconciler)
            (om/reconciler? app-or-comp-or-reconciler)
            #?(:cljs (implements? uc/UntangledApplication app-or-comp-or-reconciler)
               :clj  (satisfies? uc/UntangledApplication app-or-comp-or-reconciler)))]}
   (let [config        (merge {:marker true :parallel false :refresh [] :without #{}} config)
         reconciler    (if #?(:cljs (implements? uc/UntangledApplication app-or-comp-or-reconciler)
                              :clj  (satisfies? uc/UntangledApplication app-or-comp-or-reconciler))
                         (get app-or-comp-or-reconciler :reconciler)
                         app-or-comp-or-reconciler)
         mutation-args (load-params* server-property-or-ident SubqueryClass config)]
     (om/transact! reconciler (load-mutation mutation-args)))))

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
          (load-action ...)
          ; other optimistic updates/state changes)}"
     ([state-atom server-property-or-ident SubqueryClass] (load-action state-atom server-property-or-ident SubqueryClass {}))
     ([state-atom server-property-or-ident SubqueryClass config]
      (let [config (merge {:marker true :parallel false :refresh [] :without #{}} config)]
        (impl/mark-ready (assoc (load-params* server-property-or-ident SubqueryClass config) :state state-atom))))))

(defn load-field
  "Load a field of the current component. Runs `om/transact!`.

  Parameters
  - `component`: The component (**instance**, not class). This component MUST have an Ident.
  - `field`: A field on the component's query that you wish to load.

  Named Parameters:
  - `without`: See `load-data`
  - `params`: See `load-data`
  - `post-mutation`: See `load-data`
  - `post-mutation-params`: See `load-data`
  - `parallel`: See `load-data`
  - `fallback`: See `load-data`
  - `marker`: See `load-data`
  - `remote`: See `load-data`
  - `refresh`: See `load-data`

  NOTE: The :ui/loading-data attribute is always included in refresh. This means you probably don't want to
  query for that attribute near the root of your UI. Instead, create some leaf component with an ident that queries for :ui/loading-data
  using an Om link (e.g. `[:ui/loading-data '_]`). The presence of the ident on components will enable query optimization, which can
  improve your frame rate because Om will not have to run a full root query.
  "
  [component field & {:keys [without params remote post-mutation post-mutation-params fallback parallel refresh marker]
                      :or   {remote :remote refresh [] marker true}}]
  (when fallback (assert (symbol? fallback) "Fallback must be a mutation symbol."))
  (om/transact! component (into [(list 'untangled/load
                                   {:ident                (om/get-ident component)
                                    :field                field
                                    :query                (om/focus-query (om/get-query component) [field])
                                    :params               params
                                    :without              without
                                    :remote               remote
                                    :post-mutation        post-mutation
                                    :post-mutation-params post-mutation-params
                                    :parallel             parallel
                                    :marker               marker
                                    :refresh              refresh
                                    :fallback             fallback}) :ui/loading-data (om/get-ident component)] refresh)))

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
       ; other optimistic updates/state changes)}"
  [app-state component-class ident field & {:keys [without remote params post-mutation post-mutation-params fallback parallel refresh marker]
                                            :or   {remote :remote refresh [] marker true}}]
  (impl/mark-ready
    {:state                app-state
     :field                field
     :ident                ident
     :query                (om/focus-query (om/get-query component-class) [field])
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
  trigger for remote loads. Must be used in conjunction with running `load-data-action` or
  `load-data-field` in the `:action` side of the mutation (which queues the exact things to
  load)."
  [parsing-env]
  (let [ast (:ast parsing-env)]
    (assoc ast :key 'untangled/load :dispatch-key 'untangled/load)))

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
  the server. Can be an Om factory method or a React rendering function.

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
    static om/IQuery
    (query [this] [{:thing2 (om/get-query Thing2)}])
    Object
    (componentDidMount [this]
       (load-field this :thing2))

    (render [this]
      (let [thing2 (:thing2 (om/props this))]
        (lazily-loaded ui-thing2 thing2))))

  (defui Thing2
    static om/IQuery
    (query [this] [:ui/fetch-state])
    Object
    (render [this]
      (display-thing-2))

  (def ui-thing2 (om/factory Thing2))
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

(defn refresh! [component]
  (load component (om/get-ident component) (om/react-type component)))

(defmethod mutate 'untangled/load
  [{:keys [state]} _ {:keys [post-mutation remote] :as config}]
  (when (and post-mutation (not (symbol? post-mutation))) (log/error "post-mutation must be a symbol or nil"))
  {(if remote remote :remote) true
   :action                    (fn [] (impl/mark-ready (assoc config :state state)))})

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
