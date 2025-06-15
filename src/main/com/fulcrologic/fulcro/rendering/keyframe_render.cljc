(ns com.fulcrologic.fulcro.rendering.keyframe-render
  "The keyframe optimized render."
  (:require
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.components :as comp]))

(defn render-state!
  "This function renders given state map over top of the current app. This allows you to render previews of state **without
  actually changing the app state**. Used by Inspect for DOM preview. Forces a root-based render with no props diff optimization.
  The app must already be mounted. Returns the result of render."
  [app state-map]
  (comp/enable-forced-refresh! 1000)
  (binding [comp/*app*    app
            comp/*shared* (comp/shared app)]
    (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app
          {:com.fulcrologic.fulcro.application/keys [root-factory root-class mount-node]} @runtime-atom
          r!        (ah/app-algorithm app :render-root!)
          query     (comp/get-query root-class state-map)
          data-tree (if query
                      (fdn/db->tree query state-map state-map)
                      state-map)]
      (when (and r! root-factory)
        (r! (root-factory data-tree) mount-node)))))

(defn render!
  "Render the UI. The keyframe render runs a full UI query and then asks React to render the root component.
  The optimizations for this kind of render are purely those provided by `defsc`'s default
  shouldComponentUpdate, which causes component to act like React PureComponent (though the props compare in cljs
  is often faster).

  If `:hydrate?` is true it will use the React hydrate functionality (on browsers) to render over
  server-rendered content in the DOM.

  If `:force-root? true` is included in the options map then not only will this do a keyframe update, it will also
  force all components to return `true` from `shouldComponentUpdate`."
  [app {:keys [force-root? hydrate?]}]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom state-atom]} app
        {:com.fulcrologic.fulcro.application/keys [root-factory root-class mount-node]} @runtime-atom
        r!               (if hydrate?
                           (ah/app-algorithm app :hydrate-root!)
                           (ah/app-algorithm app :render-root!))
        state-map        @state-atom
        query            (comp/get-query root-class state-map)
        data-tree        (if query
                           (fdn/db->tree query state-map state-map)
                           state-map)
        app-root #?(:clj {}
                    :cljs (when root-factory
                            (when force-root? (comp/enable-forced-refresh! 1000))
                            (binding [comp/*app*    app
                                      comp/*parent* nil
                                      comp/*shared* (comp/shared app)]
                              (r! (root-factory data-tree) mount-node))))]
    (swap! runtime-atom assoc :com.fulcrologic.fulcro.application/app-root app-root)
    #?(:cljs app-root)))
