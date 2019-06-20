(ns com.fulcrologic.fulcro.rendering.keyframe-render
  (:require
    #?(:cljs ["react-dom" :as react-dom])
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.components :as comp]))

(defn render!
  "Render the root of the Fulcro app.  Full query/refresh optimized only by shouldComponentUpdate."
  [app options]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom state-atom]} app
        {:com.fulcrologic.fulcro.application/keys [root-factory root-class mount-node]} @runtime-atom
        state-map        @state-atom
        query            (comp/get-query root-class state-map)
        data-tree        (if query
                           (fdn/db->tree query state-map state-map)
                           state-map)
        app-root #?(:clj {}                                 ; TODO
                    :cljs (react-dom/render (root-factory data-tree) mount-node))]
    (swap! runtime-atom assoc :com.fulcrologic.fulcro.application/app-root app-root)
    #?(:cljs app-root)))
