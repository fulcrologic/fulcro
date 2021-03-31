(ns com.fulcrologic.fulcro.alpha.raw-components3
  "
  ********************************************************************************
  ALPHA: This namespace will disappear once the API is stable and adopted. Until then, each release that changes the API
  will use a new namespace to allow you to rely on a particular version. The final API will evolve new features and may
  rename things, but should be trivial to port to.
  ********************************************************************************

  Support for using Fulcro as a pure transaction/networking engine in React apps.

  Basic usage:

  * Create a tree of Fulcro components.
  * Include query/ident/initial-state as normal.
  * Use a normal Fulcro app, or the raw one from here (this one will not render things)

  Define a `raw/factory` for the top-most component of this tree. The top-most component MUST have an ident.

  ```
  (def app (raw/fulcro-app {})
  (defsc Thing [this props] {:query/ident/initial-state ...})
  (def ui-thing (raw/factory app Thing {})

  Then in your raw react code:

  ```
  (defn RawHookFunctionalComponent [props]
    (let [thing-props (raw/use-fulcro app Thing {:initial-state-params {}})
      (ui-thing thing-props)))
  ```

  Transactions should be run against `app` when outside of a Fulcro component, but `this` works fine within a Fulcro
  component.

  NOTES:

  * Dynamic routing expects routes to compose to root, but you may not have a Fulcro root. Therefore, you must use
    change-route-relative! instead of change-route!.
  * Other namespaces may have similar introspection abilities based on a full-app query.
  * Some rendering optimizations will not be available if you are not using Fulcro for rendering.

  ALPHA: This namespace is not well-tested, but there is a good chance that most things will 'just work'. It is not
  complicated to add this extension, it is just that some parts of Fulcro (particularly the dyn routers) expect a
  fully-composed query.
  "
  #?(:cljs (:require-macros com.fulcrologic.fulcro.alpha.raw-components3))
  (:require
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [com.fulcrologic.fulcro.components :as comp]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    #?@(:cljs
        [["react" :as react]
         [goog.object :as gobj]])
    [com.fulcrologic.fulcro.application :as app]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]))

(defn- create-element
  "Create a DOM element for which there exists no corresponding function.
   Useful to create DOM elements not included in React.DOM. Equivalent
   to calling `js/React.createElement`"
  ([tag]
   (create-element tag nil))
  ([tag opts]
   #?(:cljs (react/createElement tag opts)))
  ([tag opts & children]
   #?(:cljs (apply react/createElement tag opts children))))

(defn fulcro-app
  "Just like application/fulcro-app, but turns off internal rendering, and starts inspect client (if in dev mode)"
  [options]
  (let [app (app/fulcro-app (merge {:optimized-render! nil} options))]
    (inspect/app-started! app)
    app))

(defn factory
  "A Fulcro component factory for RAW React usage.

  app - The Fulcro app that this factory will be used with
  class - The Fulcro component that you need a factory for. Can be hooks-based or not.
  options - A map that can include the normal factory options. See `comp/factory`."
  [class {:keys [keyfn qualifier]}]
  (let [qid (comp/query-id class qualifier)]
    #?(:cljs
       (with-meta
         (fn element-factory [props & children]
           (when-not comp/*app*
             (log/error "No app is bound. Make sure your are calling this from code that is wrapped with `with-fulcro`."))
           (this-as this
             (let [key              (:react-key props)
                   key              (cond
                                      key key
                                      keyfn (keyfn props))
                   ref              (:ref props)
                   ref              (cond-> ref (keyword? ref) str)
                   props-middleware (some-> comp/*app* (ah/app-algorithm :props-middleware))
                   props            #js {:fulcro$value   props
                                         :fulcro$queryid qid
                                         :fulcro$app     comp/*app*
                                         :fulcro$parent  this
                                         :fulcro$depth   0}
                   props            (if props-middleware
                                      (props-middleware class props)
                                      props)]
               #?(:cljs
                  (do
                    (when key
                      (gobj/set props "key" key))
                    (when ref
                      (gobj/set props "ref" ref))))
               (create-element class props children))))
         {:class     class
          :queryid   qid
          :qualifier qualifier}))))

(defn- pcs [app component prior-props-tree-or-ident]
  (let [ident           (if (eql/ident? prior-props-tree-or-ident)
                          prior-props-tree-or-ident
                          (comp/get-ident component prior-props-tree-or-ident))
        state-map       (app/current-state app)
        starting-entity (get-in state-map ident)
        query           (comp/get-query component state-map)]
    (fdn/db->tree query starting-entity state-map)))

(defn- use-db-lifecycle [app component current-props-tree set-state!]
  (let [[id _] (hooks/use-state #?(:cljs (random-uuid) :clj (java.util.UUID/randomUUID)))]
    (hooks/use-lifecycle
      (fn []
        (let [state-map (app/current-state app)
              ident     (comp/get-ident component current-props-tree)
              exists?   (map? (get-in state-map ident))]
          (when-not exists?
            (merge/merge-component! app component current-props-tree))
          (app/add-render-listener! app id
            (fn [app _]
              (let [props (pcs app component ident)]
                (set-state! props))))))
      (fn [] (app/remove-render-listener! app id)))))

(defn use-component
  "Use Fulcro from raw React. This is a Hook effect/state combo that will connect you to the transaction/network/data
  processing of Fulcro, but will not rely on Fulcro's render. Thus, you can embed the use of the returned props in any
  stock React context. Technically, you do not have to use Fulcro components for rendering, but they are necessary to define the
  query/ident/initial-state for startup and normalization.

  The arguments are:

  app - A Fulcro app
  component - A component with query/ident. Queries MUST have co-located normalization info. You
              can create this with normal `defsc`.
  options - A map of options, containing:

  * :initial-state-params - The parameters to use when getting the initial state of the component. See `comp/get-initial-state`.
    If no initial state exists on the top-level component, then an empty map will be used. This will mean your props will be
    empty to start.
  * :keep-existing? - A boolean. If true, then the state of the component will not be initialized if there
    is already data at the component's ident (which will be computed using the initial state params provided, if
    necessary).
  * :ident - Only needed if you are NOT initializing state, AND the component has a dynamic ident.

  Returns the props from the Fulcro database. The component using this function will automatically refresh after Fulcro
  transactions run (Fulcro is not a watched-atom system. Updates happen at transaction boundaries).
  "
  [app component {:keys [ident initial-state-params keep-existing?]}]
  (let [[current-props-tree set-state!] (hooks/use-state
                                          (let [ident     (or
                                                            ident
                                                            (when initial-state-params
                                                              (comp/get-ident component (comp/get-initial-state component initial-state-params))))
                                                state-map (app/current-state app)
                                                exists?   (map? (get-in state-map ident))]
                                            (cond
                                              (and exists? keep-existing?) (pcs app component (or ident {}))
                                              initial-state-params (comp/get-initial-state component initial-state-params)
                                              (comp/has-initial-app-state? component) (comp/get-initial-state component {})
                                              :else {})))]
    (use-db-lifecycle app component current-props-tree set-state!)
    current-props-tree))



#?(:clj
   (defmacro with-fulcro
     "Wraps the given body with the correct internal bindings so that Fulcro internals
     will work when that body is not rendered by Fulcro (e.g. async render from controlling component, rendered by
     non-Fulcro parent.
     "
     [app & body]
     `(binding [comp/*app*    ~app
                comp/*depth*  0
                comp/*shared* (comp/shared ~app)
                comp/*parent* nil]
        ~@body)))

(defn use-initial-state [normalizing-query initial-state-tree options])

(defn- ast-id-key [children]
  (:key
    (first
      (filter (fn [{:keys [type key]}]
                (and
                  (keyword? key)
                  (= :prop type)
                  (= "id" (name key))))
        children))))

(defn- normalize* [{:keys [children] :as original-node} {:keys [componentName] :as top-component-options}]
  (let [detected-id-key (ast-id-key children)
        real-id-key     (or detected-id-key)
        component       (fn [& args])
        new-children    (mapv
                          (fn [{:keys [type] :as node}]
                            (if (and (= type :join) (not (:component node)))
                              (normalize* node {})
                              node))
                          children)
        updated-node    (assoc original-node :children new-children :component component)
        query           (if (= type :join)
                          (eql/ast->query (assoc updated-node :type :root))
                          (eql/ast->query updated-node))
        _               (comp/add-hook-options! component (cond-> (with-meta
                                                                    (merge
                                                                      {:initial-state (fn [& args] {})}
                                                                      top-component-options
                                                                      {:query  (fn [& args] query)
                                                                       "props" {"fulcro$queryid" :anonymous}})
                                                                    {:query-id :anonymous})
                                                            componentName (assoc :componentName componentName)
                                                            real-id-key (assoc :ident (fn [_ props] [real-id-key (get props real-id-key)]))))]
    updated-node))

(defn nc
  "Create an anonymous normalizing query component. By default the normalization will be auto-detected based on there being a prop at each
   entity level that has (any) namespace, but with the name `id`. For example:

   ```
   [:list/id :list/name {:list/items [:item/id :item/complete? :item/label]}]
   ```

   will create a normalizing query that expects the top-level values to be normalized by `:list/id` and the nested
   items to be normalized by `:item/id`. If there is more than one ID in your props, make sure the *first* one is
   the one to use for normalization.

   The `top-component-options` becomes the options map of the component. You can include :componentName to push the
   resulting anonymous component definition into the component registry, which is needed by some parts of Fulcro, like
   UISM.

   NOTE: `nc` is recursive, and *does* compose if you want to name the components at various levels. It can be used with queries from
   other defsc components:

   ```
   (def query (nc [:user/id
                   :user/name
                   ;; Generate an anonymous component that is available in the registry under ::Session
                   {:user/session-details (nc [:session/id :session/last-login] {:componentName ::Session})}
                   ;; Use a defsc query as the source
                   {:user/settings (comp/get-query Settings)}
                   ;; Autogenerates an anonymous address query component that has no name
                   {:user/address [:address/id :address/street]}]))
   ```
   "
  ([query] (nc query {}))
  ([query {:keys [componentName] :as top-component-options}]
   (let [ast (eql/query->ast query)]
     (:component (normalize* ast top-component-options)))))

(defn- normalize-form* [{:keys [children type] :as original-node} top-component-options]
  (let [detected-id-key (or (ast-id-key children) (throw (ex-info "Query must have an ID field for normalization detection" {:query (eql/ast->query original-node)})))
        _               detected-id-key
        form-fields     (into #{}
                          (comp
                            (map :key)
                            (filter #(and
                                       (not (vector? %))
                                       (not= "ui" (namespace %))
                                       (not= % detected-id-key))))
                          children)
        children        (conj children (eql/expr->ast fs/form-config-join))
        component       (fn [& args])
        new-children    (mapv
                          (fn [{:keys [type] :as node}]
                            (if (and (= type :join) (not (:component node)))
                              (normalize-form* node {})
                              node))
                          children)
        updated-node    (assoc original-node :children new-children :component component)
        query           (if (= type :join)
                          (eql/ast->query (assoc updated-node :type :root))
                          (eql/ast->query updated-node))
        _               (comp/add-hook-options! component (cond-> (with-meta
                                                                    (merge
                                                                      {:initial-state (fn [& args] {})}
                                                                      top-component-options
                                                                      {:query       (fn [& args] query)
                                                                       :ident       (fn [_ props] [detected-id-key (get props detected-id-key)])
                                                                       :form-fields form-fields
                                                                       "props"      {"fulcro$queryid" :anonymous}})
                                                                    {:query-id :anonymous})))]
    updated-node))

(defn formc
  "Create an anonymous normalizing form component from EQL. In this version every level of the query must
   have an `:<???>/id` field which is used to build the ident, and every non-id attribute will be considered part
   of the form. This auto-adds the necessary form-state form-join, and populates the anonymous component with the
   `:form-fields` option. You can add additional component options to the top-level anonymous component with
   `top-component-options`.

   See also `nc`, which is similar but does not autogenerate form-related add-ins."
  ([EQL] (formc EQL {}))
  ([EQL top-component-options]
   (let [ast (eql/query->ast EQL)]
     (:component (normalize-form* ast top-component-options)))))

(defn use-root
  "Use a root key and component as an subtree managed by Fulcro. The `root-key` must be a unique
   (namespace recommended) key among all keys used within the application, since the root of the database is where it
   will live.

   The `component` should be a real Fulcro component or a generated normalizing component from `nc` (or similar).

   Returns the props (not including `root-key`) that satisfy the query of `component`.
  "
  [app root-key component {:keys [initialize? initial-params]}]
  (let [[listener-id _] (hooks/use-state #?(:cljs (random-uuid) :clj (java.util.UUID/randomUUID)))
        [current-props set-props!] (hooks/use-state {})]
    (hooks/use-lifecycle
      (fn []
        (when (and initialize? (not (contains? (app/current-state app) root-key)))
          (swap! (::app/state-atom app) (fn use-root-merge* [s]
                                          (merge/merge-component s component
                                            (comp/get-initial-state component (or initial-params {}))
                                            :replace [root-key]))))
        (let [get-props (fn use-root-get-props* []
                          (let [query     [{root-key (comp/get-query component)}]
                                state-map (app/current-state app)]
                            (fdn/db->tree query state-map state-map)))]
          (set-props! (get-props))
          (app/add-render-listener! app listener-id (fn use-root-render-listener* [app _] (set-props! (get-props))))))
      (fn use-tree-remove-render-listener* [] (app/remove-render-listener! app listener-id)))
    (get current-props root-key)))

(defn id-key
  "Returns the keyword of the most likely ID attribute in the given props (the first one with the `name` \"id\").
  Returns nil if there isn't one."
  [props]
  (first (filter #(= "id" (name %)) (keys props))))

(defn set-value!!
  "Run a transaction that will update the given k/v pair in the props of the database. Uses the `current-props` to
   derive the ident of the database entry. The props must contain an ID key that can be used to derive the ident from
   the current-props."
  [app current-props k v]
  (let [ik    (id-key current-props)
        ident [ik (get current-props ik)]]
    (if (some nil? ident)
      (log/error "Cannot set-value!! because current-props could not be used to derive the ident of the component." current-props)
      (do
        (comp/transact!! app `[(m/set-props ~{k v})] {:ref ident})))))

(defn update-value!!
  "Run a transaction that will update the given k/v pair in the props of the database. Uses the `current-props` as the basis
   for the update, and to find the ident of the target. The `current-props` must contain an ID field that can be used to derive
   the ident from the passed props."
  [app current-props k f & args]
  (let [ik        (id-key current-props)
        ident     [ik (get current-props ik)]
        old-value (get current-props k)
        new-value (apply f old-value args)]
    (if (some nil? ident)
      (log/error "Cannot update-value!! because current-props could not be used to derive the ident of the component." current-props)
      (do
        (comp/transact!! app `[(m/set-props ~{k new-value})] {:ref ident})))))

(defn use-uism
  "Use a UISM as an effect hook. This will set up the given state machine under the given ID, and start it (if not
   already started). Your initial state handler MUST set up actors and otherwise initialize based on initial-event-data.

   If the machine is already started at the given ID then this effect will send it an `:event/remounted` event.
   This hook will send an `:event/unmounted` when the component using this effect goes away. In both cases you may choose
   to ignore the event.

   You MUST include `:componentName` in each of your actor's normalizing component options (e.g. `(nc query {:componentName ::uniqueName})`)
   because UISM requires component appear in the component registry (components cannot be safely stored in app state, just their
   names).

   Returns a map that contains the actor props (by actor name) and the current state of the state machine as `:active-state`."
  [app state-machine-definition id initial-event-data]
  (let [[listener-id _] (hooks/use-state #?(:cljs (random-uuid) :clj (java.util.UUID/randomUUID)))
        [uism-data set-uism-data!] (hooks/use-state nil)]
    (hooks/use-lifecycle
      (fn []
        (app/add-render-listener! app listener-id (fn [app _]
                                                    (let [state-map (app/current-state app)
                                                          {::uism/keys [active-state actor->ident actor->component-name]} (get-in state-map [::uism/asm-id id])
                                                          props     (reduce-kv
                                                                      (fn [result actor ident]
                                                                        (let [cname (actor->component-name actor)
                                                                              cls   (comp/registry-key->class cname)
                                                                              query (comp/get-query cls)
                                                                              base  (get-in state-map ident)]
                                                                          (assoc result actor (fdn/db->tree query (log/spy :info base) state-map))))
                                                                      {:active-state active-state}
                                                                      actor->ident)]
                                                      (set-uism-data! props))))
        (let [s        (app/current-state app)
              started? (get-in s [::uism/asm-id id])]
          (if started?
            (uism/trigger!! app id :event/remounted)
            (uism/begin! app state-machine-definition id initial-event-data))))
      (fn []
        (app/remove-render-listener! app listener-id)
        (uism/trigger!! app id :event/unmounted)))
    uism-data))




