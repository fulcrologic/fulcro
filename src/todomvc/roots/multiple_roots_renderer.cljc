(ns roots.multiple-roots-renderer
  "Like kf2, but supports free-floating roots."
  #?(:cljs (:require-macros [roots.multiple-roots-renderer]))
  (:require
    [com.fulcrologic.fulcro.rendering.keyframe-render :as kr]
    [com.fulcrologic.fulcro.rendering.ident-optimized-render :as ior]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.application :as app]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]
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
   (defmacro defroot [sym UIRootClass]
     `(comp/defsc ~sym [this# _#]
        {:shouldComponentUpdate (fn [] false)}
        (let [query#     (comp/get-query ~UIRootClass)
              state-map# (app/current-state this#)
              props#     (fdn/db->tree query# state-map# state-map#)]
          ((comp/factory ~UIRootClass) props#)))))

(defn floating-root-factory [UIClass]
  (let [constructor     (fn [])
        ui-factory      (comp/factory UIClass)
        render          (fn []
                          (this-as this
                            (let [state-map (app/current-state this)
                                  query     (comp/get-query UIClass state-map)
                                  props     (fdn/db->tree query state-map state-map)]
                              ((comp/factory UIClass) props))))
        wrapper-class   (comp/configure-component! constructor ::wrapper
                          {:shouldComponentUpdate (fn [] false)
                           :render                render})
        wrapper-factory (comp/factory wrapper-class)]
    wrapper-factory))
