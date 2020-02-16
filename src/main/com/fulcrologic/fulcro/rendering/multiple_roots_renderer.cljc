(ns com.fulcrologic.fulcro.rendering.multiple-roots-renderer
  "Like keyframe-render2, but also supports free-floating roots.

  WARNING: THIS RENDERER IS ALPHA. Lightly tested, but not heavily used yet.

  General usage:

  1. Set this nses `render!` as your application's optimized render function.
  2. Create a class that follows all of the normal rules for a Fulcro root (no ident, has initial state,
  composes children queries/initial-state, etc.
     a. Add mount/unmount register/deregister calls
  2. Use floating-root-factory to generate a Fulcro factory, or floating-root-react-class to generate
  a vanilla React wrapper class that renders the new root.
     a. Use the factory in normal Fuclro rendering, but don't pass it props, or
     b. Use `(dom/create-element ReactClass)` to render the vanilla wrapper, or
     c. Use the vanilla wrapper class when a js library controls rendering (like routing).

  Example:

  ```
  (defonce app (app/fulcro-app {:optimized-render! mroot/render!}))

  (defsc AltRoot [this {:keys [alt-child]}]
    ;; query is from ROOT of the db, just like normal root.
    {:query                 [{:alt-child (comp/get-query OtherChild)}]
     :componentDidMount     (fn [this] (mroot/register-root! this))
     :componentWillUnmount  (fn [this] (mroot/deregister-root! this))
     :shouldComponentUpdate (fn [] true)
     :initial-state         {:alt-child [{:id 1 :n 22}
                                         {:id 2 :n 44}]}}
    (dom/div
      (mapv ui-other-child alt-child)))

  ;; For use in the body of normal defsc components.
  (def ui-alt-root (mroot/floating-root-factory AltRoot))

  ;; For use as plain React class
  (def PlainAltRoot (mroot/floating-root-react-class AltRoot app))

  ...

  (some-js-library #js {:thing PlainAltRoot})

  (defsc NormalFulcroClass [this props]
    {:query [:stuff]
     :ident (fn [] [:x 1])
     ...}
    (dom/div
      ;; ok to use within defsc components:
      (ui-alt-root)
      ;; how to use the plain react class, which is how js libs would use it:
      (dom/create-element PlainAltRoot)))

  ```
  "
  #?(:cljs (:require-macros [com.fulcrologic.fulcro.rendering.multiple-roots-renderer :refer [with-app-context]]))
  (:require
    [com.fulcrologic.fulcro.rendering.keyframe-render :as kr]
    [com.fulcrologic.fulcro.rendering.ident-optimized-render :as ior]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.application :as app]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]
    #?(:cljs [goog.object :as gobj])
    [com.fulcrologic.fulcro.application :as app]))

(defn register-root!
  "Register a mounted react component as a new root that should be managed."
  [react-instance]
  (if (map? comp/*app*)
    (let [class (comp/react-type react-instance)
          k     (comp/class->registry-key class)]
      (log/debug "Adding root of type " k)
      (swap! (::app/runtime-atom comp/*app*) update-in [::known-roots k] (fnil conj #{}) react-instance))
    (log/error "Register-root cannot find app in *app*. You need with-parent-context?")))

(defn deregister-root!
  "Deregister a mounted root that should no longer be managed."
  [react-instance]
  (if (map? comp/*app*)
    (let [class (comp/react-type react-instance)
          k     (comp/class->registry-key class)]
      (log/debug "Adding root of type " k)
      (swap! (::app/runtime-atom comp/*app*) update-in [::known-roots k] disj react-instance))
    (log/error "Deregister-root cannot find app in *app*. You need with-parent-context?")))

(defn render-roots! [app options]
  (let [state-map   (app/current-state app)
        known-roots (some-> app ::app/runtime-atom deref ::known-roots)]
    (kr/render! app options)
    (doseq [k (keys known-roots)
            :let [cls        (comp/registry-key->class k)
                  query      (comp/get-query cls state-map)
                  root-props (fdn/db->tree query state-map state-map)]]
      (doseq [root (get known-roots k)]
        (when (comp/mounted? root)
          (log/debug "Refreshing mounted root for " k)
          #?(:cljs (.setState ^js root (fn [s] #js {"fulcro$value" root-props}))))))))

(defn render-stale-components!
  "This function tracks the state of the app at the time of prior render in the app's runtime-atom. It
   uses that to do a comparison of old vs. current application state (bounded by the needs of on-screen components).
   When it finds data that has changed it renders all of the components that depend on that data."
  [app options]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app
        {:com.fulcrologic.fulcro.application/keys [only-refresh]} @runtime-atom
        limited-refresh? (seq only-refresh)]
    (if limited-refresh?
      (let [{limited-idents true} (group-by eql/ident? only-refresh)]
        (doseq [i limited-idents]
          (ior/render-components-with-ident! app i)))
      (render-roots! app options))))

(defn render!
  "The top-level call for using this optimized render in your application.

  If `:force-root? true` is passed in options, then it just forces a keyframe root render.

  This renderer always does a keyframe render *unless* an `:only-refresh` option is passed to the stack
  (usually as an option on `(transact! this [(f)] {:only-refresh [...idents...]})`. In that case the renderer
  will ignore *all* data diffing and will target refresh only to the on-screen components that have the listed
  ident(s). This allows you to get component-local state refresh rates on transactions that are responding to
  events that should really only affect a known set of components (like the input field).

  This option does *not* currently support using query keywords in the refresh set. Only idents."
  ([app]
   (render! app {}))
  ([app {:keys [force-root? root-props-changed?] :as options}]
   (let [state-map (app/current-state app)]
     (if (or force-root? root-props-changed?)
       (render-roots! app options)
       (try
         (render-stale-components! app options)
         (catch #?(:clj Exception :cljs :default) e
           (log/info "Optimized render failed. Falling back to root render.")
           (render-roots! app options)))))))

#?(:clj
   (defmacro with-app-context
     "Wraps the given body with the correct internal bindings of the given fulcro-app so that Fulcro internals
     will work when that body is embedded in unusual ways (e.g. as the body in a child-as-a-function
     React pattern).

     You should use this around the render body of any floating root that will be rendered outside of
     the synchronous fulcro render (e.g. you pass a floating root class to a React library).
     "
     [fulcro-app & body]
     (if-not (:ns &env)
       `(do ~@body)
       `(let [r# (or comp/*app* ~fulcro-app)
              d# (or comp/*depth* 0)
              s# (get (some-> ~fulcro-app ::app/runtime-atom deref) ::shared-props comp/*shared*)]
          (binding [comp/*app*    r#
                    comp/*depth*  d#
                    comp/*shared* s#]
            ~@body)))))

(defn floating-root-react-class
  "Generate a plain React class that can render a Fulcro UIRoot. NOTE: The UIRoot must register/deregister itself
  in the component lifecycle:

  ```
  (defsc UIRoot [this props]
    {:componentDidMount     (fn [this] (mroot/register-root! this))
     :componentWillUnmount  (fn [this] (mroot/deregister-root! this))
     :initial-state {}
     :query [root-like-query]}
    ...)
  ```

  The `fulcro-app` is the app under which this root will be rendered. Create different factories if you have more than
  one mounted app.
  "
  [UIRoot fulcro-app]
  (let [cls (fn [])]
    #?(:cljs
       (gobj/extend (.-prototype cls) js/React.Component.prototype
         (clj->js
           {:shouldComponentUpdate (fn [] false)
            :render                (fn []
                                     (with-app-context fulcro-app
                                       (let [query     (comp/get-query UIRoot)
                                             state-map (app/current-state fulcro-app)
                                             props     (fdn/db->tree query state-map state-map)]
                                         ((comp/factory UIRoot) props))))})))
    cls))

(defn floating-root-factory
  "Create a factory that renders a floating root in a normal Fulcro context (body of a Fulcro component). This factory
   has the same sync constraints as normal `component/factory` functions. See `components/with-parent-context`.

  `UIClass`: A class that will behave as a floating root. NOTE: that class MUST have a mount/unmount hook
  to regsiter/deregister itself as a root.

  `options`: An options map. Same as for `component/factory`. Note, however, that this factory will *not* receive
  props, so a `:keyfn` would have to be based on something else.

  You normally do not pass any props to this factory because it is controlling the component and feeding props from
  the database. Props sent to this factory are only used by the wrapper, however, `:react-key` is useful if you
  have a bunch of sibling roots and need to set the react key for each.
  "
  ([UIClass]
   (floating-root-factory UIClass {}))
  ([UIClass options]
   (let [constructor     (fn [])
         ui-factory      (comp/factory UIClass)
         render          (fn []
                           (let [state-map (app/current-state comp/*app*)
                                 query     (comp/get-query UIClass state-map)
                                 props     (fdn/db->tree query state-map state-map)]
                             (ui-factory props)))
         wrapper-class   (comp/configure-component! constructor ::wrapper
                           {:shouldComponentUpdate (fn [_ _ _] false)
                            :render                render})
         wrapper-factory (comp/factory wrapper-class options)]
     wrapper-factory)))
