(ns com.fulcrologic.fulcro.rendering.keyframe-render
  "The keyframe optimized render."
  (:require
    #?(:cljs [cljsjs.react.dom])
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.components :as comp]))

(defn render!
  "Render the UI. The keyframe render runs a full UI query and then asks React to render the root component.
  The optimizations for this kind of render are purely those provided by `defsc`'s default
  shouldComponentUpdate, which causes component to act like React PureComponent (though the props compare in cljs
  is often faster).

  If `:force-root? true` is included in the options map then not only will this do a keyframe update, it will also
  force all components to return `false` from `shouldComponentUpdate`."
  [app {:keys [force-root?] :as options}]
  (binding [comp/*blindly-render* force-root?]
    (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom state-atom]} app
          {:com.fulcrologic.fulcro.application/keys [root-factory root-class mount-node]} @runtime-atom
          state-map        @state-atom
          query            (comp/get-query root-class state-map)
          data-tree        (if query
                             (fdn/db->tree query state-map state-map)
                             state-map)
          app-root #?(:clj {}                               ; TODO
                      :cljs (js/ReactDOM.render (root-factory data-tree) mount-node))]
      (swap! runtime-atom assoc :com.fulcrologic.fulcro.application/app-root app-root)
      #?(:cljs app-root))))
