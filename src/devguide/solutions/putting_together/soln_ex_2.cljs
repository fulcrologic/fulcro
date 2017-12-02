(ns solutions.putting-together.soln-ex-2
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
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
  static prim/InitialAppState
  (initial-state [this {:keys [id label complete]}] {:item/id id :item/label label :item/done complete})
  static prim/IQuery
  (query [this] [:item/id :item/label :item/done])
  static prim/Ident
  (ident [this props] [:items/by-id (:item/id props)])
  Object
  (render [this]
    (let [{:keys [item/id item/label item/done] :or {done false}} (prim/props this)
          delete (prim/get-computed this :onDelete)]
      (dom/li nil
        (dom/input #js {:type "checkbox" :onChange #(prim/transact! this `[(toggle-done ~{:id id})]) :checked (boolean done)})
        label
        (dom/button #js {:onClick #(when delete (delete id))} "X")))))

(def ui-item (prim/factory TodoItem))

(defui ^:once ItemList
  static prim/InitialAppState
  (initial-state [this params] {:list/title "My List" :list/items [(prim/get-initial-state TodoItem {:id 1 :label "A" :complete false})
                                                                   (prim/get-initial-state TodoItem {:id 2 :label "B" :complete false})
                                                                   (prim/get-initial-state TodoItem {:id 3 :label "C" :complete true})
                                                                   (prim/get-initial-state TodoItem {:id 4 :label "D" :complete false})]})
  static prim/IQuery
  (query [this] [:ui/new-item-text :list/title {:list/items (prim/get-query TodoItem)}])
  static prim/Ident
  (ident [this props] [:lists/by-title (:list/title props)])
  Object
  (render [this]
    (let [{:keys [ui/new-item-text list/title list/items] :or {ui/new-item-text ""}} (prim/props this)
          delete-item (fn [item-id] (prim/transact! this `[(delete-item {:list ~title :id ~item-id})]))]
      (dom/div nil
        (dom/h4 nil title)
        (dom/input #js {:value (or new-item-text "") :onChange (fn [evt] (m/set-string! this :ui/new-item-text :event evt))})
        (dom/button #js {:onClick #(prim/transact! this `[(add-item {:id    ~(prim/tempid)
                                                                   :label ~new-item-text
                                                                   :list  ~title})])} "Add")
        (dom/ol nil (map (fn [item] (ui-item (prim/computed item {:onDelete delete-item}))) items))))))

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
