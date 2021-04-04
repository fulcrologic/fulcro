(ns com.fulcrologic.fulcro.alpha.raw-components2
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
  #?(:cljs (:require-macros com.fulcrologic.fulcro.alpha.raw-components2))
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
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]))

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

#?(:cljs
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
             :qualifier qualifier})))))

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

(defn use-tree
  "Use a normalized query an manual initial state to interface with Fulcro. Supply a normalized query (see `nq`)
   and options. Returns the props from the Fulcro state for that query. You must supply either an ident of an
   existing node in the database, or an `initial-tree` that will cause the normalized query to figure out the ident
   to use.

   The arguments are:

   app - A Fulcro app
   normalizing-component - An anonymous component created with `nc`. Also works with a component.
   options - A map of options, containing:

   * :initial-tree - An initial tree of data to use if the data isn't already in the Fulcro database. You must supply
     this or the `:ident` of the data that is already there. This tree *will be normalized* according to the normalization
     needs detected or supplied on the query by `nc`.
   * :keep-existing? - A boolean. If true, then the `initial-tree` will not be written if the data is already in the
     Fulcro database.
   * :ident - Only needed if you are NOT initializing things with a tree.

   Returns the current tree from the Fulcro database. The component using this function will automatically refresh
   after Fulcro transactions run (Fulcro is not a watched-atom system. Updates happen at transaction boundaries). Use
   your `app` as the parameter to `transact!`. Remember to wrap any use of stock Fulcro components in `with-fulcro`.
  "
  [app normalizing-component {:keys [ident initial-tree keep-existing?]}]
  (let [[current-props-tree set-state!] (hooks/use-state
                                          (let [ident     (or
                                                            ident
                                                            (when initial-tree
                                                              (comp/get-ident normalizing-component initial-tree)))
                                                state-map (app/current-state app)
                                                exists?   (map? (get-in state-map ident))]
                                            (cond
                                              (and exists? keep-existing?) (pcs app normalizing-component (or ident {}))
                                              initial-tree initial-tree
                                              :else {})))]
    (use-db-lifecycle app normalizing-component current-props-tree set-state!)
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

(defn- id-key [children]
  (:key
    (first
      (filter (fn [{:keys [type key]}]
                (and
                  (= :prop type)
                  (= "id" (name key))))
        children))))

(defn- normalize* [{:keys [children] :as original-node}]
  (let [detected-id-key (id-key children)
        real-id-key     (or detected-id-key)
        component       (fn [& args])
        new-children    (mapv
                          (fn [{:keys [type] :as node}]
                            (if (= type :join)
                              (normalize* node)
                              node))
                          children)
        updated-node    (assoc original-node :children new-children :component component)
        query           (eql/ast->query updated-node)
        _               (comp/add-hook-options! component (cond-> (with-meta
                                                                    {:query         (fn [& args] query)
                                                                     "props"        {"fulcro$queryid" :anonymous}
                                                                     :initial-state (fn [& args] {})}
                                                                    {:query-id :anonymous})
                                                            real-id-key (assoc :ident (fn [_ props] [real-id-key (get props real-id-key)]))))]
    updated-node))

(defn nc
  "Create a normalizing query component. By default the normalization will be auto-detected based on there being a prop at each
   entity level that has (any) namespace, but with the name `id`. For example:

   ```
   [:list/id :list/name {:list/items [:item/id :item/complete? :item/label]}]
   ```

   will create a normalizing query that expects the top-level values to be normalized by `:list/id` and the nested
   items to be normalized by `:item/id`. If there is more than one ID in your props, make sure the *first* one is
   the one to use for normalization.
   "
  [query]
  (let [ast (eql/query->ast query)]
    (:component (normalize* ast))))

(comment

  (let [component (nc [:item/id])]
    (comp/get-query (log/spy :info component) {})
    )
  (let [component (nc [:list/id {:list/items [:item/id :item/name]}])]
    (merge/merge-component {} component {:list/id    1
                                         :list/items [{:item/id 1 :item/label "A"}
                                                      {:item/id 2 :item/label "B"}]}))

  (let [component (nc [:list/id {:list/items [:item/id :item/name]}])
        tree      {:list/id 42 :list/items [{:item/id 1 :item/name "A"} {:item/id 2 :item/name "B"}]}]
    (fnorm/tree->db (comp/get-query component) tree true)
    )

  )