(ns book.bootstrap.components.collapse
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [fulcro.client.routing :as routing :refer [defrouter]]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc CollapseRoot [this {:keys [collapse-1]}]
  {; Use the initial state of b/Collapse to make the proper state for one
   :initial-state (fn [p] {:collapse-1 (prim/get-initial-state b/Collapse {:id 1 :start-open false})})
   ; Join it into your query
   :query         [{:collapse-1 (prim/get-query b/Collapse)}]}
  (render-example "100%" "200px"
    (dom/div nil
      (b/button {:onClick (fn [] (prim/transact! this `[(b/toggle-collapse {:id 1})]))} "Toggle")
      ; Wrap the elements to be hidden as children
      ; NOTE: if the children need props they could be queried for above and passed in here.
      (b/ui-collapse collapse-1
        (dom/div #js {:className "well"} "This is some content that can be collapsed.")))))


