(ns untangled.client.data-fetch
  (:require
    [clojure.walk :refer [walk prewalk]]
    [om.next :as om]
    [untangled.client.impl.data-fetch :as impl]
    [untangled.i18n :refer-macros [tr]]
    [om.dom :as dom]
    [untangled.client.core :as uc]))

(defn load-params*
  "Internal function to validate and process the parameters of `load` and `load-action`."
  [server-property SubqueryClass & {:keys [target params marker refresh parallel post-mutation fallback without]
                                    :or   {marker true parallel false refresh [] without #{}}}]
  {:pre [(or (nil? target) (vector? target))
         (or (nil? post-mutation) (symbol? post-mutation))
         (or (nil? fallback) (symbol? fallback))
         (vector? refresh)
         (or (nil? params) (map? params))
         (set? without)
         (keyword? server-property)
         (implements? om/IQuery SubqueryClass)]}
  (let [query (if (map? params)
                `[({~server-property ~(om/get-query SubqueryClass)} ~params)]
                [{server-property (om/get-query SubqueryClass)}])]
    {:query         query
     :target        target
     :without       without
     :post-mutation post-mutation
     :refresh       refresh
     :marker        marker
     :parallel      parallel
     :fallback      fallback}))

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
  - `server-property` : A keyword that represents the root of the query to send to the server
  - `SubqueryClass` : An Om componenet that implements IQuery. This will be combined with `server-property` into a join for the server query

  Optional Named Parameters:
  - `target` - An assoc-in path at which to put the result of the Subquery. If supplied, the data AND load marker will appear
    at this path. If not supplied the data and marker will appear at `server-property` in the top-level of you client app state
    database.
  - `params` - Optional parameters to add to the generated query
  - `marker` - Boolean to determine if you want a fetch-state marker in your app state. Defaults to true. Add `:ui/fetch-state` to the
  target component in order to see this data in your component.
  - `refresh` - A vector of keywords that will cause component re-renders after the final load/mutations. Same as follow-on
  reads in normal `transact!`
  - `parallel` - If true, indicates that this load does not have to go through the sequential network queue. Defaults to false.
  - `post-mutation` - A mutation (symbol) to run after the data is merged. Note, if target is supplied be sure your post mutation
  should expect the data at the targeted location.
  - `fallback` - A mutation (symbol) to run if there is a server/network error.
  - `without` - An optional set of keywords that should (recursively) be removed from the query.
  "
  [app-or-comp-or-reconciler server-property SubqueryClass & {:keys [target params marker refresh parallel post-mutation fallback without]
                                                              :or   {marker true parallel false refresh [] without #{}}
                                                              :as   config}]

  {:pre [(or (om/component? app-or-comp-or-reconciler)
             (om/reconciler? app-or-comp-or-reconciler)
             (instance? uc/UntangledApplication app-or-comp-or-reconciler))]}
  (let [reconciler (if (instance? uc/UntangledApplication app-or-comp-or-reconciler)
                     (get app-or-comp-or-reconciler :reconciler)
                     app-or-comp-or-reconciler)
        mutation-args (load-params* server-property SubqueryClass config)]
    (om/transact! reconciler (load-mutation mutation-args))))

(defn load-action
  [state-atom server-property SubqueryClass & {:keys [target params marker refresh parallel post-mutation fallback without]
                                               :or   {marker true parallel false refresh [] without #{}}
                                               :as   config}]
  "
  See `load` for descriptions of parameters.

  Queue up a remote load from within an already-running mutation. Similar to `load`, but usable from
  within a mutation.

  Note the `:refresh` parameter is supported, and defaults to empty. If you want anything to refresh other than
  the targeted component you will want to include the :refresh parameter.

  To use this function make sure your mutation specifies a return value with a remote. The remote
  should use the helper function `remote-load` as it's value:

  { :remote (df/remote-load env)
    :action (fn []
       (load-action ...)
       ; other optimistic updates/state changes)}"
  (impl/mark-ready (assoc (load-params* server-property SubqueryClass config) :state state-atom)))

(defn load-field
  "Load a field of the current component. Runs `om/transact!`.

  Parameters
  - `component`: The component (**instance**, not class). This component MUST have an Ident.
  - `field`: A field on the component's query that you wish to load.

  Named Parameters:
  - `without`: See `load-data`
  - `params`: See `load-data`
  - `post-mutation`: See `load-data`
  - `parallel`: See `load-data`
  - `fallback`: See `load-data`
  - `marker`: See `load-data`
  - `refresh`: See `load-data`

  NOTE: The :ui/loading-data attribute is always included in refresh. This means you probably don't want to
  query for that attribute near the root of your UI. Instead, create some leaf component with an ident that queries for :ui/loading-data
  using an Om link (e.g. `[:ui/loading-data '_]`). The presence of the ident on components will enable query optimization, which can
  improve your frame rate because Om will not have to run a full root query.
  "
  [component field & {:keys [without params post-mutation fallback parallel refresh marker] :or [refresh [] marker true]}]
  (when fallback (assert (symbol? fallback) "Fallback must be a mutation symbol."))
  (om/transact! component (into [(list 'untangled/load
                                       {:ident         (om/get-ident component)
                                        :field         field
                                        :query         (om/focus-query (om/get-query component) [field])
                                        :params        params
                                        :without       without
                                        :post-mutation post-mutation
                                        :parallel      parallel
                                        :marker        marker
                                        :refresh       refresh
                                        :fallback      fallback}) :ui/loading-data (om/get-ident component)] refresh)))

(defn load-data
  "
  DEPRECATED: use `load` and `load-field` instead.

  Load data from the remote. Runs `om/transact!`. See also `load-field`.

  Parameters
  - `comp-or-reconciler`: A component or reconciler (not a class)
  - `query`: The query for the element(s) attributes. Use defui to generate arbitrary queries so normalization will work.

  Optional Named parameters
  - `ident`: An ident, used if loading a singleton and you wish to specify 'which one'.
  - `post-mutation`: A mutation (symbol) invoked after the load succeeds.
  - `fallback`: A mutation (symbol) invoked after the load fails. App state is in env, server error is in the params under :error.
  - `parallel`: Boolean to indicate that this load should happen in the parallel on the server (non-blocking load). Any loads marked this way will happen in parallel.
  - `marker`: A boolean (default true). If true, a marker is placed in the app state in place of the target data during the load. If false, no marker is produced.
  - `refresh`: A vector of keywords indicating data that will be changing. If any of the listed keywords are queried by on-screen
    components, then those components will be re-rendered after the load has finished and post mutations have run.
  - `without`: A set of keywords. Any keyword appearing in this set will be recursively removed from the query (in a proper AST-preserving fashion).
  - `params`: A parameter map to augment onto the first element of the query

  "
  [comp-or-reconciler query & {:keys [ident without params post-mutation fallback parallel refresh marker] :or {refresh [] marker true}}]
  (when fallback (assert (symbol? fallback) "Fallback must be a mutation symbol."))
  (om/transact! comp-or-reconciler (into [(list 'untangled/load
                                                {:ident         ident
                                                 :query         query
                                                 :params        params
                                                 :without       without
                                                 :post-mutation post-mutation
                                                 :refresh       refresh
                                                 :marker        marker
                                                 :parallel      parallel
                                                 :fallback      fallback}) :ui/loading-data] refresh)))

(defn load-field-action
  "Queue up a remote load of a component's field from within an already-running mutation. Similar to `load-field`
  but usable from within a mutation. Note the `:refresh` parameter is supported, and defaults to nothing, even for
  fields, in actions. If you want anything to refresh other than the targeted component you will want to use the
  :refresh parameter.

  To use this function make sure your mutation specifies a return value with a remote. The remote
  should use the helper function `remote-load` as it's value:

  { :remote (df/remote-load env)
    :action (fn []
       (load-field-action ...)
       ; other optimistic updates/state changes)}"
  [app-state component-class ident field & {:keys [without params post-mutation fallback parallel refresh marker] :or [refresh [] marker true]}]
  (impl/mark-ready
    {:state         app-state
     :field         field
     :ident         ident
     :query         (om/focus-query (om/get-query component-class) [field])
     :params        params
     :without       without
     :parallel      parallel
     :refresh       refresh
     :marker        marker
     :post-mutation post-mutation
     :fallback      fallback}))

(defn load-data-action
  "
  DEPRECATED: Use `load-action` instead.

  Queue up a remote load from within an already-running mutation. Similar to `load-data`, but usable from
  within a mutation.

  Note the `:refresh` parameter is supported, and defaults to empty. If you want anything to refresh other than
  the targeted component you will want to include the :refresh parameter.

  To use this function make sure your mutation specifies a return value with a remote. The remote
  should use the helper function `remote-load` as it's value:

  { :remote (df/remote-load env)
    :action (fn []
       (load-data-action ...)
       ; other optimistic updates/state changes)}"
  [app-state query & {:keys [ident without params post-mutation fallback parallel refresh marker] :or {refresh [] marker true}}]
  (impl/mark-ready
    {:state         app-state
     :ident         ident
     :query         query
     :params        params
     :without       without
     :parallel      parallel
     :refresh       refresh
     :marker        marker
     :post-mutation post-mutation
     :fallback      fallback}))

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
  ALPHA WARNING: The transfer of read errors to failed data states is not implemented in this alpha version.

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
                        :or   {loading-render (fn [_] (dom/div #js {:className "lazy-loading-load"} "Loading..."))
                               ready-render   (fn [_] (dom/div #js {:className "lazy-loading-ready"} nil))
                               failed-render  (fn [_] (dom/div #js {:className "lazy-loading-failed"} nil))}}]

  (let [state (:ui/fetch-state props)]
    (cond
      (ready? state) (ready-render props)
      (loading? state) (loading-render props)
      (failed? state) (failed-render props)
      (and not-present-render (nil? props)) (not-present-render props)
      :else (data-render props))))
