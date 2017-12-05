(ns solutions.putting-together.soln-ex-1
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
            [fulcro.client.data-fetch :as df]))

(defsc TodoItem [this {:keys [item/id item/label item/done]}]
  {
   :initial-state (fn [{:keys [id label complete]}] {:item/id id :item/label label :item/done complete})
   :query         [:item/id :item/label :item/done]
   :ident         [:items/by-id :item/id]}
  (dom/li nil
    (dom/input #js {:type "checkbox" :checked (boolean done)})
    label
    (dom/button #js {} "X")))

(def ui-item (prim/factory TodoItem))

(defsc ItemList [this {:keys [list/title list/items]}]
  {:initial-state (fn [params] {:list/title "My List" :list/items [(prim/get-initial-state TodoItem {:id 1 :label "A" :complete false})
                                                                   (prim/get-initial-state TodoItem {:id 2 :label "B" :complete false})
                                                                   (prim/get-initial-state TodoItem {:id 3 :label "C" :complete true})
                                                                   (prim/get-initial-state TodoItem {:id 4 :label "D" :complete false})]})
   :query         [:list/title {:list/items (prim/get-query TodoItem)}]
   :ident         [:lists/by-title :list/title]}
  (dom/div nil
    (dom/h4 nil title)
    (dom/input #js {}) (dom/button nil "Add")
    (dom/ol nil (map ui-item items))))

(def ui-item-list (prim/factory ItemList))

(defsc TodoList [this {:keys [ui/react-key item-list]}]
  {:initial-state (fn [params] {:ui/react-key "A" :item-list (prim/get-initial-state ItemList nil)})
   :query         [:ui/react-key {:item-list (prim/get-query ItemList)}]}
  (dom/div #js {:key react-key}
    (ui-item-list item-list)))
