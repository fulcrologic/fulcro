(ns com.fulcrologic.fulcro.react.hooks
  "React hooks wrappers and helpers. The wrappers are simple API simplifications that help when using hooks from
   Clojurescript, but this namespace also includes utilities for using Fulcro's data/network management from raw React
   via hooks.

   See `use-root`, `use-component`, and `use-uism`."
  #?(:cljs
     (:require-macros [com.fulcrologic.fulcro.react.hooks :refer [use-effect use-lifecycle]]))
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; WARNING TO MAINTAINERS: DO NOT REFERENCE DOM IN HERE. This has to work with native.
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (:require
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    #?(:cljs ["react" :as react])
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.fulcro.rendering.multiple-roots-renderer :as mrr]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.application :as app])
  #?(:clj (:import (cljs.tagged_literals JSValue))))

(defn useState
  "A simple CLJC wrapper around React/useState. Returns a JS vector for speed. You probably want use-state, which is more
  convenient.

  React docs: https://reactjs.org/docs/hooks-reference.html#usestate"
  [initial-value]
  #?(:cljs (react/useState initial-value)))

(defn use-state
  "A simple wrapper around React/useState. Returns a cljs vector for easy destructuring.

  React docs: https://reactjs.org/docs/hooks-reference.html#usestate"
  [initial-value]
  #?(:cljs (into-array (react/useState initial-value))))

(defn useEffect
  "A CLJC wrapper around js/React.useEffect that does NO conversion of
  dependencies. You probably want the macro use-effect instead.

  React docs: https://reactjs.org/docs/hooks-reference.html#useeffect"
  ([f]
   #?(:cljs (react/useEffect f)))
  ([f js-deps]
   #?(:cljs (react/useEffect f js-deps))))

#?(:clj
   (defmacro use-effect
     "A simple macro wrapper around React/useEffect that does compile-time conversion of `dependencies` to a js-array
     for convenience without affecting performance.

      React docs: https://reactjs.org/docs/hooks-reference.html#useeffect"
     ([f]
      `(useEffect ~f))
     ([f dependencies]
      (if (enc/compiling-cljs?)
        (let [deps (cond
                     (nil? dependencies) nil
                     (instance? JSValue dependencies) dependencies
                     :else (JSValue. dependencies))]
          `(useEffect ~f ~deps))
        `(useEffect ~f ~dependencies)))))

(defn use-context
  "A simple wrapper around React/useContext."
  [ctx]
  #?(:cljs (react/useContext ctx)))

(defn use-reducer
  "A simple wrapper around React/useReducer. Returns a cljs vector for easy destructuring

  React docs: https://reactjs.org/docs/hooks-reference.html#usecontext"
  ([reducer initial-arg]
   #?(:cljs (into-array (react/useReducer reducer initial-arg))))
  ([reducer initial-arg init]
   #?(:cljs (into-array (react/useReducer reducer initial-arg init)))))

(defn use-callback
  "A simple wrapper around React/useCallback. Converts args to js array before send.

  React docs: https://reactjs.org/docs/hooks-reference.html#usecallback"
  ([cb]
   #?(:cljs (react/useCallback cb)))
  ([cb args]
   #?(:cljs (react/useCallback cb (to-array args)))))

(defn use-memo
  "A simple wrapper around React/useMemo. Converts args to js array before send.

  React docs: https://reactjs.org/docs/hooks-reference.html#usememo"
  ([cb]
   #?(:cljs (react/useMemo cb)))
  ([cb args]
   #?(:cljs (react/useMemo cb (to-array args)))))

(defn use-ref
  "A simple wrapper around React/useRef.

  React docs: https://reactjs.org/docs/hooks-reference.html#useref"
  ([] #?(:cljs (react/useRef nil)))
  ([value] #?(:cljs (react/useRef value))))

(defn use-imperative-handle
  "A simple wrapper around React/useImperativeHandle.

  React docs: https://reactjs.org/docs/hooks-reference.html#useimperativehandle"
  [ref f]
  #?(:cljs (react/useImperativeHandle ref f)))

(defn use-layout-effect
  "A simple wrapper around React/useLayoutEffect.

  React docs: https://reactjs.org/docs/hooks-reference.html#uselayouteffect"
  ([f]
   #?(:cljs (react/useLayoutEffect f)))
  ([f args]
   #?(:cljs (react/useLayoutEffect f (to-array args)))))

(defn use-debug-value
  "A simple wrapper around React/useDebugValue.

  React docs: https://reactjs.org/docs/hooks-reference.html#uselayouteffect"
  ([value]
   #?(:cljs (react/useDebugValue value)))
  ([value formatter]
   #?(:cljs (react/useDebugValue value formatter))))

#?(:clj
   (defmacro use-lifecycle
     "A macro shorthand that evaluates to low-level js at compile time for
     `(use-effect (fn [] (when setup (setup)) (when teardown teardown)) [])`"
     ([setup] `(use-lifecycle ~setup nil))
     ([setup teardown]
      (cond
        (and setup teardown) `(use-effect (fn [] (~setup) ~teardown) [])
        setup `(use-effect (fn [] (~setup) ~(when (enc/compiling-cljs?) 'js/undefined)) [])
        teardown `(use-effect (fn [] ~teardown) [])))))

(let [id (fn [] (tempid/uuid))]
  (defn use-generated-id
    "Returns a constant ident with a generated ID component."
    []
    (aget (useState id) 0)))

(defn use-gc
  "Effect handler. Creates an effect that will garbage-collect the given ident from fulcro app state on cleanup, and
  will follow any `edges` (a set of keywords) and remove any things pointed through those keywords as well. See
  normalized-state's `remove-entity`.

  ```
  (defsc NewRoot [this props]
    {:use-hooks? true}
    (let [generated-id (hooks/use-generated-id)
          f (use-fulcro-mount this {:child-class SomeChild
                                    :initial-state-params {:id generated-id})]
      ;; will garbage-collect the floating root child on unmount
      (use-gc this [:child/id generated-id] #{})
      (f props)))
  ```
  "
  [this-or-app ident edges]
  (use-lifecycle
    nil
    (fn []
      (let [state (-> this-or-app comp/any->app :com.fulcrologic.fulcro.application/state-atom)]
        (swap! state fns/remove-entity ident edges)))))

(let [initial-mount-state (fn []
                            (let [componentName (keyword "com.fulcrologic.fulcro.floating-root" (gensym "generated-root"))]
                              #?(:clj [componentName nil] :cljs #js [componentName nil])))]
  (defn use-fulcro-mount
    "
    NOTE: In many cases you are better off using the other hooks support in this ns, such as `use-component`, since
    they do not have a render integration requirement.

    Generate a new sub-root that is controlled and rendered by Fulcro's multi-root-renderer.

    ```
    ;; important, you must use hooks (`defhc` or `:use-hooks? true`)
    (defsc NewRoot [this props]
      {:use-hooks? true}
      (let [f (use-fulcro-mount this {:child-class SomeChild})]
        ;; parent props will show up in SomeChild as computed props.
        (f props)))
    ```

    WARNING: Requires you use multi-root-renderer."
    [parent-this {:keys [child-class
                         initial-state-params]}]
    ;; factories are functions, and if you pass a function to setState it will run it, which is NOT what we want...
    (let [st                 (useState initial-mount-state)
          pass-through-props (atom {})
          key-and-root       (aget st 0)
          setRoot!           (aget st 1)
          _                  (use-lifecycle
                               (fn []
                                 (let [join-key      (aget key-and-root 0)
                                       child-factory (comp/computed-factory child-class)
                                       initial-state (comp/get-initial-state child-class (or initial-state-params {}))
                                       cls           (comp/configure-hooks-component!
                                                       (fn [this fulcro-props]
                                                         (use-lifecycle
                                                           (fn [] (mrr/register-root! this))
                                                           (fn [] (mrr/deregister-root! this)))
                                                         (comp/with-parent-context parent-this
                                                           (child-factory (get fulcro-props join-key initial-state) @pass-through-props)))
                                                       {:query         (fn [_] [{join-key (comp/get-query child-class)}])
                                                        :initial-state (fn [_] {join-key initial-state})
                                                        :componentName join-key})
                                       real-factory  (comp/factory cls {:keyfn (fn [_] join-key)})
                                       factory       (fn [props]
                                                       (reset! pass-through-props props)
                                                       (real-factory {}))]
                                   (setRoot! #?(:clj [join-key factory] :cljs #js [join-key factory]))))
                               (fn []
                                 (let [join-key (aget key-and-root 0)
                                       state    (-> parent-this comp/any->app :com.fulcrologic.fulcro.application/state-atom)]
                                   (swap! state dissoc join-key))))]
      (aget key-and-root 1))))

(defn- pcs [app component prior-props-tree-or-ident]
  (let [ident           (if (eql/ident? prior-props-tree-or-ident)
                          prior-props-tree-or-ident
                          (comp/get-ident component prior-props-tree-or-ident))
        state-map       (rapp/current-state app)
        starting-entity (get-in state-map ident)
        query           (comp/get-query component state-map)]
    (fdn/db->tree query starting-entity state-map)))

(defn- use-db-lifecycle [app component current-props-tree set-state!]
  (let [[id _] (use-state #?(:cljs (random-uuid) :clj (java.util.UUID/randomUUID)))]
    (use-lifecycle
      (fn []
        (let [state-map (rapp/current-state app)
              ident     (comp/get-ident component current-props-tree)
              exists?   (map? (get-in state-map ident))]
          (when-not exists?
            (merge/merge-component! app component current-props-tree))
          (rapp/add-render-listener! app id
            (fn [app _]
              (let [props (pcs app component ident)]
                (set-state! props))))))
      (fn [] (rapp/remove-render-listener! app id)))))

(defn use-component
  "Use Fulcro from raw React. This is a Hook effect/state combo that will connect you to the transaction/network/data
  processing of Fulcro, but will not rely on Fulcro's render. Thus, you can embed the use of the returned props in any
  stock React context. Technically, you do not have to use Fulcro components for rendering, but they are necessary to define the
  query/ident/initial-state for startup and normalization. You may also use this within normal (Fulcro)
  components to generate dynamic components on-the-fly (see `nc`).

  The arguments are:

  `app` - A Fulcro app
  `component` - A component with query/ident. Queries MUST have co-located normalization info. You
              can create this with normal `defsc` or as an anonymous component via `raw.components/nc`.
  `options` - A map of options, containing:
    * `:initial-params` - The parameters to use when getting the initial state of the component. See `comp/get-initial-state`.
      If no initial state exists on the top-level component, then an empty map will be used. This will mean your props will be
      empty to start.
    * `initialize?` - A boolean (default true). If true then the initial state of the component will be used to pre-populate the component's state
      in the app database.
    * `:keep-existing?` - A boolean. If true, then the state of the component will not be initialized if there
      is already data at the component's ident (which will be computed using the initial state params provided, if
      necessary).
    * `:ident` - Only needed if you are NOT initializing state, AND the component has a dynamic ident.

  Returns the props from the Fulcro database. The component using this function will automatically refresh after Fulcro
  transactions run (Fulcro is not a watched-atom system. Updates happen at transaction boundaries).

  MAY return nil if no data is at that component's ident.

  See also `use-root`.
  "
  [app component
   {:keys [initialize? initial-params keep-existing?]
    :or   {initial-params {}}
    :as   options}]
  #?(:cljs
     (let [prior-props-ref (use-ref nil)
           get-props       (fn [ident] (binding [fdn/*denormalize-time* (rapp/basis-t app)]
                                         (rc/get-traced-props (rapp/current-state app) component
                                           ident
                                           (.-current prior-props-ref))))
           [current-props
            set-props!] (use-state
                          (fn initialize-component-state []
                            (let [initial-entity (comp/get-initial-state component initial-params)
                                  initial-ident  (or (:ident options) (rc/get-ident component initial-entity))]
                              (rapp/maybe-merge-new-component! app component initial-entity options)
                              (let [initial-props (get-props initial-ident)]
                                (set! (.-current prior-props-ref) initial-props)
                                initial-props))))
           current-ident   (or (:ident options) (rc/get-ident component current-props))]
       (use-effect
         (fn [] (let [listener-id (random-uuid)]
                  (rapp/add-render-listener! app listener-id
                    (fn [app _]
                      (let [props (get-props current-ident)]
                        (when-not (identical? (.-current prior-props-ref) props)
                          (set! (.-current prior-props-ref) props)
                          (set-props! props)))))
                  (fn use-tree-remove-render-listener* []
                    (rapp/remove-render-listener! app listener-id)
                    (set! (.-current prior-props-ref) nil))))
         [(hash current-ident)])
       current-props)))

(defn use-root
  "Use a root key and component as a subtree managed by Fulcro from raw React. The `root-key` must be a unique
   (namespace recommended) key among all keys used within the application, since the root of the database is where it
   will live.

   The `component` should be a real Fulcro component or a generated normalizing component from `nc` (or similar).

   Returns the props (not including `root-key`) that satisfy the query of `component`. MAY return nil if no data is available.

   See also `use-component`.
  "
  [app root-key component {:keys [initialize? keep-existing? initial-params] :as options}]
  (let [prior-props-ref (use-ref nil)
        get-props       #(rapp/get-root-subtree-props app root-key component (.-current prior-props-ref))
        [current-props set-props!] (use-state (fn []
                                                (rapp/maybe-merge-new-root! app root-key component options)
                                                (let [initial-props (get-props)]
                                                  (set! (.-current prior-props-ref) initial-props)
                                                  initial-props)))]
    (use-lifecycle
      (fn [] (rapp/add-render-listener! app root-key (fn use-root-render-listener* [app _]
                                                       (let [props (get-props)]
                                                         (when-not (identical? (.-current prior-props-ref) props)
                                                           (set! (.-current prior-props-ref) props)
                                                           (set-props! props))))))
      (fn use-tree-remove-render-listener* [] (rapp/remove-root! app root-key)))
    (get current-props root-key)))

(defn use-uism
  "Use a UISM as an effect hook. This will set up the given state machine under the given ID, and start it (if not
   already started). Your initial state handler MUST set up actors and otherwise initialize based on options.

   If the machine is already started at the given ID then this effect will send it an `:event/remounted` event.

   You MUST include `:componentName` in each of your actor's normalizing component options (e.g. `(nc query {:componentName ::uniqueName})`)
   because UISM requires component appear in the component registry (components cannot be safely stored in app state, just their
   names).

   `options` is a map that can contain `::uism/actors` as an actor definition map (see `begin!`). Any other keys in options
   are sent as the initial event data when the machine is started.

   Returns a map that contains the actor props (by actor name) and the current state of the state machine as `:active-state`."
  [app state-machine-definition id options]
  (let [[uism-data set-uism-data!] (use-state (fn initialize-component-state []
                                                (uism/current-state-and-actors (app/current-state app) id)))]
    (use-lifecycle
      (fn []
        (uism/add-uism! app {:state-machine-definition state-machine-definition
                             :id                       id
                             :receive-props            set-uism-data!
                             :actors                   (::uism/actors options)
                             :initial-event-data       (dissoc options ::uism/actors)}))
      (fn [] (uism/remove-uism! app id)))
    uism-data))
