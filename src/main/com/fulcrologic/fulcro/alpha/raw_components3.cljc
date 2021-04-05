(ns com.fulcrologic.fulcro.alpha.raw-components3
  "
  ********************************************************************************
  ALPHA: This namespace will disappear once the API is stable and adopted. Until then, each release that changes the API
  will use a new namespace to allow you to rely on a particular version. The final API will evolve new features and may
  rename things, but should be trivial to port to.
  ********************************************************************************

  Support for using Fulcro as a pure transaction/networking engine in React apps.

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
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.alpha.raw :as raw]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.application :as app]
    [edn-query-language.core :as eql]
    #?@(:cljs
        [["react" :as react]
         [goog.object :as gobj]])
    [com.fulcrologic.fulcro.application :as app]
    [taoensso.timbre :as log]))

(defn factory
  "A Fulcro component factory for RAW React usage.

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
                   props            #js {:fulcro$value   (js->clj props :keywordize-keys true)
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
                      (gobj/set props "ref" ref))
                    (react/createElement class props children))))))
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

(defn use-root
  "Use a root key and component as a subtree managed by Fulcro. The `root-key` must be a unique
   (namespace recommended) key among all keys used within the application, since the root of the database is where it
   will live.

   The `component` should be a real Fulcro component or a generated normalizing component from `nc` (or similar).

   Returns the props (not including `root-key`) that satisfy the query of `component`.
  "
  [app root-key component {:keys [initialize? initial-params] :as options}]
  (let [[current-props set-props!] (hooks/use-state {})]
    (hooks/use-lifecycle
      (fn [] (raw/add-root! app root-key component (merge options {:receive-props set-props!})))
      (fn use-tree-remove-render-listener* [] (raw/remove-root! app root-key)))
    (get current-props root-key)))

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
  (let [[uism-data set-uism-data!] (hooks/use-state nil)]
    (hooks/use-lifecycle
      (fn []
        (raw/add-uism! app {:state-machine-definition state-machine-definition
                            :id                       id
                            :receive-props            set-uism-data!
                            :initial-event-data       initial-event-data}))
      (fn [] (raw/remove-uism! app id)))
    uism-data))




