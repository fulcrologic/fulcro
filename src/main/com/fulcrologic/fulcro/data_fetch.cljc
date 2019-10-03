(ns com.fulcrologic.fulcro.data-fetch
  (:refer-clojure :exclude [load])
  (:require
    [clojure.walk :refer [walk prewalk]]
    [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.mutations :as m]
    [clojure.spec.alpha :as s]
    [ghostwheel.core :refer [>defn =>]]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]))

(>defn data-state?
  "Is the given parameter a load marker?"
  [state]
  [any? => boolean?]
  (and (map? state) (contains? state :status)))

(>defn load-marker?
  "Is the given parameter a load marker?"
  [x]
  [any? => boolean?]
  (data-state? x))

(>defn ready? "Is the given load marker ready for loading?" [marker]
  [(s/nilable map?) => boolean?]
  (= :loading (:status marker)))

(>defn loading? "Is the given load marker loading?" [marker]
  [(s/nilable map?) => boolean?]
  (= :loading (:status marker)))

(>defn failed?
  "Is the given load marker indicate failed?

  WARNING: This function is current unimplemented and will be removed.  The new way of dealing with failure is to
  define an `error-action` for the load in question and modify your own state. You can also override"
  [marker]
  [(s/nilable map?) => boolean?]
  (= :failed (:status marker)))

(def marker-table
  "The name of the table in which fulcro load markers are stored. You must query for this via a link query
  `[df/marker-table '_]` in any component that needs to use them (and refresh) during loads."
  :ui.fulcro.client.data-fetch.load-markers/by-id)

(defn elide-ast-nodes
  "Remove items from a query (AST) that have a key that returns true for the elision-predicate"
  [{:keys [key union-key children] :as ast} elision-predicate]
  (let [union-elision? (elision-predicate union-key)]
    (when-not (or union-elision? (elision-predicate key))
      (when (and union-elision? (<= (count children) 2))
        (log/warn "Unions are not designed to be used with fewer than two children. Check your calls to Fulcro
        load functions where the :without set contains " (pr-str union-key)))
      (let [new-ast (update ast :children (fn [c] (vec (keep #(elide-ast-nodes % elision-predicate) c))))]
        (if (seq (:children new-ast))
          new-ast
          (dissoc new-ast :children))))))

(defn elide-query-nodes
  "Remove items from a query when the query element where the (node-predicate key) returns true. Commonly used with
   a set as a predicate to elide specific well-known UI-only paths."
  [query node-predicate]
  (-> query eql/query->ast (elide-ast-nodes node-predicate) eql/ast->query))

(defn load-params*
  "Internal function to validate and process the parameters of `load` and `load-action`."
  [app server-property-or-ident class-or-factory {:keys [target params marker post-mutation post-mutation-params without
                                                         fallback focus ok-action post-action error-action remote
                                                         abort-id update-query]
                                                  :or   {remote :remote marker false}}]
  {:pre [(or (nil? target) (vector? target))
         (or (nil? post-mutation) (symbol? post-mutation))
         (or (nil? post-mutation-params) (map? post-mutation-params))
         (or (nil? params) (map? params))
         (or (eql/ident? server-property-or-ident) (keyword? server-property-or-ident))]}
  (let [state-map         (-> app :com.fulcrologic.fulcro.application/state-atom deref)
        transformed-query (if class-or-factory
                            (cond-> (comp/get-query class-or-factory state-map)
                              (set? without) (elide-query-nodes without)
                              focus (eql/focus-subquery focus)
                              update-query update-query)
                            nil)
        query             (cond
                            (and class-or-factory (map? params)) `[({~server-property-or-ident ~transformed-query} ~params)]
                            class-or-factory [{server-property-or-ident transformed-query}]
                            (map? params) [(list server-property-or-ident params)]
                            :else [server-property-or-ident])
        marker            (if (true? marker)
                            (do
                              (log/warn "Boolean load marker no longer supported.")
                              false)
                            marker)]
    {:query                query
     :source-key           server-property-or-ident
     :remote               remote
     :target               target
     :ok-action            ok-action
     :error-action         error-action
     :post-action          post-action
     :post-mutation        post-mutation
     :post-mutation-params post-mutation-params
     :fallback             fallback
     :marker               marker
     :abort-id             abort-id}))

(defn set-load-marker!
  "Adds a load marker at the given `marker` id to df/marker-table with the given status.

  NOTE: You must query for the marker table in any component that wants to show activity."
  [app marker status]
  (when marker
    (let [{:com.fulcrologic.fulcro.application/keys [state-atom]} app
          render! (ah/app-algorithm app :schedule-render!)]
      (log/debug "Setting load marker")
      (swap! state-atom assoc-in [marker-table marker] {:status status})
      ;; FIXME: Test refresh for this without the force root...it should work without it if ppl properly query for the marker table.
      (render! app {:force-root? true}))))

(defn remove-load-marker!
  "Removes the load marker with the given `marker` id from the df/marker-table."
  [app marker]
  (when marker
    (let [{:com.fulcrologic.fulcro.application/keys [state-atom]} app]
      (log/debug "Removing load marker")
      (swap! state-atom update marker-table dissoc marker))))

(defn finish-load!
  "Default processing when a load finishes successfully (called internally).

  Removes any load marker, then either:

  - Runs the `ok-action` (if defined).
  - Does normal post-processing (if the was no ok-action):
       - Merges the load result
       - Processes desired targets
       - Runs the post-mutation (if defined)
       - Runs the post-action (if defined)"
  [{:keys [app result] :as env} {:keys [query ok-action post-mutation post-mutation-params
                                        post-action target marker source-key] :as params}]
  (remove-load-marker! app marker)

  (let [env (assoc env :load-params params)]
    (if (fn? ok-action)
      (do
        (log/debug "Skipping default merge and calling user-supplied ok-action.")
        (ok-action env))
      (let [{:keys [body transaction]} result
            query (or transaction query)
            {:com.fulcrologic.fulcro.application/keys [state-atom]} app]
        (log/debug "Doing merge and targeting steps: " body query)
        (swap! state-atom (fn [s]
                            (cond-> (merge/merge* s query body {:remove-missing? true})
                              target (targeting/process-target source-key target))))
        (when (symbol? post-mutation)
          (log/debug "Doing post mutation " post-mutation)
          (comp/transact! app `[(~post-mutation ~(or post-mutation-params {}))]))
        (when (fn? post-action)
          (log/debug "Doing post action ")
          (post-action env))))))

(defn load-failed!
  "The normal internal processing of a load that has failed (error returned true).

  Sets the load marker, if present, to :failed.

  If an `error-action` was desired, it is used to process the rest of the failure.

  The `env` will include the network `:result` and the original load options as `:load-params`.

  *Otherwise*, this function will:

  - Trigger the global error action (if defined on the app) (arg is env as described above)
  - Trigger any fallback for the load. (params are the env described above)
  "
  [{:keys [app] :as env} {:keys [error-action marker fallback] :as params}]
  (log/debug "Running load failure logic.")
  (set-load-marker! app marker :failed)
  (let [env (assoc env :load-params params)]
    (if (fn? error-action)
      (do
        (log/debug "Skipping default load error action")
        (error-action env))
      (do
        (when-let [global-error-action (ah/app-algorithm app :global-error-action)]
          (global-error-action env))
        (when (symbol? fallback)
          (comp/transact! app `[(~fallback ~env)]))))))

(defmethod m/mutate `internal-load! [{:keys [ast] :as env}]
  (let [params     (get ast :params)
        {:keys [remote query marker]} params
        remote-key (or remote :remote)]
    (log/debug "Loading " remote " query:" query)
    (cond-> {:action        (fn [{:keys [app]}] (set-load-marker! app marker :loading))
             :result-action (fn [{:keys [result app] :as env}]
                              (let [remote-error? (ah/app-algorithm app :remote-error?)]
                                (if (remote-error? result)
                                  (load-failed! env params)
                                  (finish-load! env params))))
             remote-key     (fn [_]
                              (eql/query->ast query))})))

(defn load!
  "Load data from the server.

  This function triggers a server interaction and normalizes the server response into your app state database. During
  operation it also adds (by default) fetch markers into the app state so you can show busy indicators on the UI
  components that are waiting for data. The `:target` parameter can be used to place the data somewhere besides app
  state root (which is the default).

  The server will receive a query of the form: [({server-property (comp/get-query class-or-factory)} params)], which
  a Fulcro parser will correctly parse as a join on server-property with the given subquery and params. See the AST and
  instructions on parsing queries in the developer's guide.

  Parameters:
  - `app-or-comp` : A component instance or Fulcro application
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
  - `initialize` - REMOVED. Use component pre-merge instead.
  - `remote` - Optional. Keyword name of the remote that this load should come from.
  - `params` - Optional parameters to add to the generated query
  - `marker` - ID of marker. Normalizes a load marker into app state so you can see progress.
  - `refresh` - A list of things in the UI to refresh. Depends on rendering optimization.
  - `focus` - Focus the query along a path. See eql/focus-subquery.
  - `without` - A set of keys to remove (recursively) from the query.
  - `update-query` - A general-purpose function that can transform the component query before sending to remote. See also
     the application's `:global-eql-transform` option.
     For example, to focus a subquery using update-query: `{:update-query #(eql/focus-subquery % [:my {:sub [:query]}])}`
     Removing properties (like previous :without option): `{:update-query #(df/elide-query-nodes % #{:my :elisions})}`
  - `abort-id` - Set a unique key. If supplied, then the load can be cancelled via that abort ID.
  - `parallel` - Send the load out-of-order (immediately) without waiting for other loads in progress.
  - `post-mutation` - A mutation (symbol) to run *after* the data is merged. Note, if target is supplied be sure your post mutation
    should expect the data at the targeted location. The `env` of that mutation will be the env of the load (if available), but will also include `:load-request`.
  - `post-mutation-params` - An optional map that will be passed to the post-mutation when it is called.
  - `post-action` - A lambda that will a mutation env parameter `(fn [env] ...)`. Called after success, like post-mutation
    (and after post-mutation if also defined). `env` will include the original `:load-params` and raw network layer `:result`. If you
    want the post behavior to act as a top-level mutation, then prefer `post-mutation`. The action can also call `transact!`.
  - `fallback` - A mutation (symbol) to run if there is a server/network error. The `env` of the fallback will be like a mutation `env`, and will
    include a `:result` key with the real result from the server, along with the original `:load-params`.

  Special-purpose config options:

  The config options can also include the following things that completely override behaviors of other (respons-processing) options,
  and should only be used in very advanced situations where you know what you are doing:

  - `ok-action` - WARNING: OVERRIDES ALL DEFAULT OK BEHAVIOR (except load marker removal)! A lambda that will receive an env parameter `(fn [env] ...)` that
    includes the `:result` and original `:load-params`.
  - `error-action` - WARNING: OVERRIDES ALL DEFAULT ERROR BEHAVIOR (except load marker update). A lambda that will receive an `env`
    that includes the `:result` and original `:load-params`.
  "
  ([app-or-comp server-property-or-ident class-or-factory] (load! app-or-comp server-property-or-ident class-or-factory {}))
  ([app-or-comp server-property-or-ident class-or-factory config]
   (let [app           (comp/any->app app-or-comp)
         {:keys [load-marker-default query-transform-default load-mutation]} (-> app :com.fulcrologic.fulcro.application/config)
         {:keys [parallel] :as config} (merge
                                         (cond-> {:marker load-marker-default :parallel false :refresh [] :without #{}}
                                           query-transform-default (assoc :update-query query-transform-default))
                                         config)
         load-sym      (or load-mutation `internal-load!)
         mutation-args (load-params* app server-property-or-ident class-or-factory config)
         abort-id      (:abort-id mutation-args)]
     (comp/transact! app `[(~load-sym ~mutation-args)]
       (cond-> {}
         (boolean? parallel) (assoc :parallel? parallel)
         abort-id (assoc ::txn/abort-id abort-id))))))

(defn load-field!
  "Load a field of the current component. Runs `prim/transact!`.

  Parameters
  - `component`: The component (**instance**, not class). This component MUST have an Ident.
  - `field`: A field on the component's query that you wish to load.
  - `options` : A map of load options. See `load`.

  WARNING: If you're using dynamic queries, you won't really know what factory your parent is using,
  nor can you pass it as a parameter to this function. Therefore, it is not recommended to use load-field from within
  a component that has a dynamic query unless you can base it on the original static query.
  "
  [component field options]
  (let [app          (comp/any->app component)
        {:keys [parallel update-query]} options
        ident        (comp/get-ident component)
        update-query (fn [q]
                       (cond-> (eql/focus-subquery q [field])
                         update-query (update-query)))
        params       (load-params* app ident component (assoc options
                                                         :update-query update-query
                                                         :source-key (comp/get-ident component)))
        abort-id     (:abort-id params)]
    (comp/transact! app [(list `internal-load! params)]
      (cond-> {}
        (boolean? parallel) (assoc :parallel? parallel)
        abort-id (assoc ::txn/abort-id abort-id)))))

(defn refresh!
  ([component load-options]
   (load! component (comp/get-ident component) component load-options))
  ([component]
   (load! component (comp/get-ident component) component)))

(def load "DEPRECATED. Use `load!`" load!)
(def load-field "DEPRECATED. Use `load-field!`" load-field!)
