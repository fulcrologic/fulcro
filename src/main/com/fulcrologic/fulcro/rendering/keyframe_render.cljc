(ns com.fulcrologic.fulcro.rendering.keyframe-render
  (:require
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.components :as comp]))

(defn render!
  "Render the root of the Fulcro app.  Full query/refresh optimized only by shouldComponentUpdate."
  [app options]
  (let [{:keys [:com.fulcrologic.fulcro.application/runtime-atom :com.fulcrologic.fulcro.application/state-atom]} app
        {:keys [:com.fulcrologic.fulcro.application/root-factory :com.fulcrologic.fulcro.application/root-class :com.fulcrologic.fulcro.application/mount-node]} @runtime-atom
        state-map @state-atom
        query     (comp/get-query root-class state-map)
        data-tree (fdn/db->tree query state-map state-map)]
    #?(:cljs (js/ReactDOM.render (root-factory data-tree) mount-node))))
