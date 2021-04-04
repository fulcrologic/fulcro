(ns com.fulcrologic.fulcro.alpha.raw-components
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
    [com.fulcrologic.fulcro.application :as app]))

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
  [app class {:keys [keyfn qualifier]}]
  (let [qid (comp/query-id class qualifier)]
    (with-meta
      (fn element-factory [props & children]
        (binding [comp/*app* app]
          (this-as this
            (let [key              (:react-key props)
                  key              (cond
                                     key key
                                     keyfn (keyfn props))
                  ref              (:ref props)
                  ref              (cond-> ref (keyword? ref) str)
                  props-middleware (some-> app (ah/app-algorithm :props-middleware))
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
              (create-element class props children)))))
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

(defn use-fulcro
  "Use Fulcro from raw React. This is a Hook effect/state combo that will connect you to the transaction/network/data
  processing of Fulcro, but will not rely on Fulcro's render. Thus, you can embed the use of the returned props in any
  stock React context. Technically, you do not have to use Fulcro components for rendering, but they are necessary to define the
  query/ident/initial-state for startup and normalization.

  The arguments are:

  app - A Fulcro app
  normalizing-component - A component with query/ident, or equivalent. Queries MUST have co-located normalization info. You
                      can create this with normal `defsc`, just don't bother making render bodies. An `sc` function will
                      likely be added soon for making this combo without having to use `defsc`.
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
                                              :else {})))
        [id _] (hooks/use-state #?(:clj  (java.util.UUID/randomUUID)
                                   :cljs (random-uuid)))]
    (hooks/use-lifecycle
      (fn []
        (let [state-map (app/current-state app)
              ident     (comp/get-ident component current-props-tree)
              exists?   (map? (get-in state-map ident))]
          (when-not exists?
            (merge/merge-component! app component current-props-tree)))
        (app/add-render-listener! app id
          (fn [app _]
            (let [props (pcs app component current-props-tree)]
              (set-state! props)))))
      (fn [] (app/remove-render-listener! app id)))
    current-props-tree))
