(ns recipes.dynamic-ui-main
  (:require [fulcro.client.primitives :as prim :refer [defui]]
            [fulcro.client.core :as fc]
            [fulcro.client.routing :as r]
            cljs.loader
            [fulcro.client.dom :as dom]))

; This is a "screen" that we want to load with code-splitting modules. See the "demos" build in project.clj. The name
; of the module needs to match the first element of the ident, as that's how the dynamic router figures out what module
; to load.
(defui ^:once Main
  static fc/InitialAppState
  (initial-state [clz params] {r/dynamic-route-key :main :label "MAIN" :main-prop "main page data"})
  static prim/Ident
  (ident [this props] [:main :singleton])
  static prim/IQuery
  (query [this] [r/dynamic-route-key :label :main-prop])
  Object
  (render [this]
    (let [{:keys [label main-prop]} (prim/props this)]
      (dom/div #js {:style #js {:backgroundColor "red"}}
        (str label " " main-prop)))))

(defmethod r/get-dynamic-router-target :main [k] Main)
(cljs.loader/set-loaded! :main)
