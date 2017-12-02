(ns solutions.putting-together.soln-ex-1
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
            [fulcro.client.data-fetch :as df]))

(defui ^:once TodoItem
  static prim/InitialAppState
  (initial-state [this {:keys [id label complete]}] {:item/id id :item/label label :item/done complete})
  static prim/IQuery
  (query [this] [:item/id :item/label :item/done])
  static prim/Ident
  (ident [this props] [:items/by-id (:item/id props)])
  Object
  (render [this]
    (let [{:keys [item/id item/label item/done]} (prim/props this)]
      (dom/li nil
        (dom/input #js {:type "checkbox" :checked (boolean done)})
        label
        (dom/button #js {} "X")))))

(def ui-item (prim/factory TodoItem))

(defui ^:once ItemList
  static prim/InitialAppState
  (initial-state [this params] {:list/title "My List" :list/items [(prim/get-initial-state TodoItem {:id 1 :label "A" :complete false})
                                                                   (prim/get-initial-state TodoItem {:id 2 :label "B" :complete false})
                                                                   (prim/get-initial-state TodoItem {:id 3 :label "C" :complete true})
                                                                   (prim/get-initial-state TodoItem {:id 4 :label "D" :complete false})]})
  static prim/IQuery
  (query [this] [:list/title {:list/items (prim/get-query TodoItem)}])
  static prim/Ident
  (ident [this props] [:lists/by-title (:list/title props)])
  Object
  (render [this]
    (let [{:keys [list/title list/items]} (prim/props this)]
      (dom/div nil
        (dom/h4 nil title)
        (dom/input #js {}) (dom/button nil "Add")
        (dom/ol nil (map ui-item items))))))

(def ui-item-list (prim/factory ItemList))

(defui ^:once TodoList
  static prim/InitialAppState
  (initial-state [this params] {:ui/react-key "A" :item-list (prim/get-initial-state ItemList nil)})
  static prim/IQuery
  (query [this] [:ui/react-key {:item-list (prim/get-query ItemList)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key item-list]} (prim/props this)]
      (dom/div #js {:key react-key}
        (ui-item-list item-list)))))
