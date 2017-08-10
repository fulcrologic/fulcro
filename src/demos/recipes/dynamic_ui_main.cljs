(ns recipes.dynamic-ui-main
  (:require [om.next :as om]
            [fulcro.client.core :as fc]
            [fulcro.client.routing :as r]
            [om.dom :as dom]))

; This is a "screen" that we want to load with code-splitting modules. See the "demos" build in project.clj. The name
; of the module needs to match the first element of the ident, as that's how the dynamic router figures out what module
; to load.
(om/defui ^:once Main
   static fc/InitialAppState
   (initial-state [clz params] {r/dynamic-route-key :main :label "MAIN"})
   static om/Ident
   (ident [this props] [:main :singleton])
   static om/IQuery
   (query [this] [r/dynamic-route-key :label])
   Object
   (render [this]
     (let [{:keys [label]} (om/props this)]
       (dom/div #js {:style #js {:backgroundColor "red"}}
         label))))
