(ns solutions.putting-together.soln-ex-2
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [fulcro.client.core :as fc]
            [fulcro.client.data-fetch :as df]
            [fulcro.client.mutations :as m :refer [defmutation]]))

(defmutation toggle-done [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:items/by-id id :item/done] not)))

(defmutation delete-item [{:keys [list id]}]
  (action [{:keys [state]}]
    (let [current-items   (get-in @state [:lists/by-title list :list/items])
          ident-to-remove [:items/by-id id]
          result-items    (vec (filter #(not= ident-to-remove %) current-items))]
      (swap! state (fn [m]
                     (-> m
                       (assoc-in [:lists/by-title list :list/items] result-items)
                       (update-in [:items/by-id] dissoc id)))))))

(defmutation add-item [{:keys [id label list]}]
  (action [{:keys [state]}]
    (let [new-ident [:items/by-id id]
          new-item  {:item/id id :item/label label :item/done false}]
      (swap! state assoc-in [:items/by-id id] new-item)
      (fc/integrate-ident! state new-ident :append [:lists/by-title list :list/items]))))

(defui ^:once TodoItem
  static fc/InitialAppState
  (initial-state [this {:keys [id label complete]}] {:item/id id :item/label label :item/done complete})
  static om/IQuery
  (query [this] [:item/id :item/label :item/done])
  static om/Ident
  (ident [this props] [:items/by-id (:item/id props)])
  Object
  (render [this]
    (let [{:keys [item/id item/label item/done] :or {done false}} (om/props this)
          delete (om/get-computed this :onDelete)]
      (dom/li nil
        (dom/input #js {:type "checkbox" :onChange #(om/transact! this `[(toggle-done ~{:id id})]) :checked (boolean done)})
        label
        (dom/button #js {:onClick #(when delete (delete id))} "X")))))

(def ui-item (om/factory TodoItem))

(defui ^:once ItemList
  static fc/InitialAppState
  (initial-state [this params] {:list/title "My List" :list/items [(fc/initial-state TodoItem {:id 1 :label "A" :complete false})
                                                                   (fc/initial-state TodoItem {:id 2 :label "B" :complete false})
                                                                   (fc/initial-state TodoItem {:id 3 :label "C" :complete true})
                                                                   (fc/initial-state TodoItem {:id 4 :label "D" :complete false})]})
  static om/IQuery
  (query [this] [:ui/new-item-text :list/title {:list/items (om/get-query TodoItem)}])
  static om/Ident
  (ident [this props] [:lists/by-title (:list/title props)])
  Object
  (render [this]
    (let [{:keys [ui/new-item-text list/title list/items] :or {ui/new-item-text ""}} (om/props this)
          delete-item (fn [item-id] (om/transact! this `[(delete-item {:list ~title :id ~item-id})]))]
      (dom/div nil
        (dom/h4 nil title)
        (dom/input #js {:value (or new-item-text "") :onChange (fn [evt] (m/set-string! this :ui/new-item-text :event evt))})
        (dom/button #js {:onClick #(om/transact! this `[(add-item {:id    ~(om/tempid)
                                                                   :label ~new-item-text
                                                                   :list  ~title})])} "Add")
        (dom/ol nil (map (fn [item] (ui-item (om/computed item {:onDelete delete-item}))) items))))))

(def ui-item-list (om/factory ItemList))

(defui ^:once TodoList
  static fc/InitialAppState
  (initial-state [this params] {:ui/react-key "A" :item-list (fc/initial-state ItemList nil)})
  static om/IQuery
  (query [this] [:ui/react-key {:item-list (om/get-query ItemList)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key item-list]} (om/props this)]
      (dom/div #js {:key react-key}
        (ui-item-list item-list)))))
