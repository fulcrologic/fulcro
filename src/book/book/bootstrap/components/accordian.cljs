(ns book.bootstrap.components.accordian
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [fulcro.client.routing :as routing :refer [defrouter]]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.ui.bootstrap3 :as b]))

(defn accordian-section [this all-ids collapse]
  (letfn [(toggle [] (prim/transact! this `[(b/toggle-collapse-group-item {:item-id      ~(:db/id collapse)
                                                                           :all-item-ids ~all-ids})]))]
    (b/panel {:key (str "section-" (:db/id collapse))}
      (b/panel-heading {:key (str "heading-" (:db/id collapse))}
        (b/panel-title nil
          (dom/a #js {:onClick toggle} "Section Heading")))
      (b/ui-collapse collapse
        (b/panel-body nil
          "This is some content that can be collapsed.")))))

(defsc CollapseGroupRoot [this {:keys [ui/react-key collapses]}]
  {; Create a to-many list of collapse items in app state (or you could do them one by one)
   :initial-state (fn [p] {:collapses [(prim/get-initial-state b/Collapse {:id 1 :start-open false})
                                       (prim/get-initial-state b/Collapse {:id 2 :start-open false})
                                       (prim/get-initial-state b/Collapse {:id 3 :start-open false})
                                       (prim/get-initial-state b/Collapse {:id 4 :start-open false})]})
   ; join it into the query
   :query         [:ui/react-key {:collapses (prim/get-query b/Collapse)}]}
  (let [all-ids [1 2 3 4]]                                  ; convenience for all ids
    (render-example "100%" "300px"
      ; map over our helper function
      (b/panel-group {:key react-key}
        (mapv (fn [c] (accordian-section this all-ids c)) collapses)))))
