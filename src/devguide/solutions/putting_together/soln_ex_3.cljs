(ns solutions.putting-together.soln-ex-3
  (:require [fulcro.client.primitives :as om :refer [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client.core :as fc]
            [fulcro.client.data-fetch :as df]
            [fulcro.client.mutations :as m :refer [defmutation]]))

(defmutation todo/toggle-done [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:items/by-id id :item/done] not))
  ; extra credit: add the new state as a parameter
  (remote [{:keys [ast state]}]
    (update ast :params assoc :done? (get-in @state [:items/by-id id :item/done]))))

(defmutation todo/delete-item [{:keys [list id]}]
  (action [{:keys [state]}]
    (let [current-items   (get-in @state [:lists/by-title list :list/items])
          ident-to-remove [:items/by-id id]
          result-items    (vec (filter #(not= ident-to-remove %) current-items))]
      (swap! state (fn [m]
                     (-> m
                       (assoc-in [:lists/by-title list :list/items] result-items)
                       (update-in [:items/by-id] dissoc id))))))
  (remote [env] true))

(defmutation todo/add-item [{:keys [id label list]}]
  (action [{:keys [state]}]
    (let [new-ident [:items/by-id id]
          new-item  {:item/id id :item/label label :item/done false}]
      (swap! state assoc-in [:items/by-id id] new-item)
      (fc/integrate-ident! state new-ident :append [:lists/by-title list :list/items])))
  (remote [env] true))

(defui ^:once TodoItem
  static fc/InitialAppState
  (initial-state [this {:keys [id label complete]}] {:item/id id :item/label label :item/done complete})
  static om/IQuery
  (query [this] [:item/id :item/label :item/done])
  static om/Ident
  (ident [this props] [:items/by-id (:item/id props)])
  Object
  (render [this]
    (let [{:keys [item/id item/label item/done]} (om/props this)
          delete (om/get-computed this :onDelete)
          toggle (om/get-computed this :onToggle)]
      (dom/li nil
        (dom/input #js {:type "checkbox" :onChange #(when toggle (toggle id)) :checked (boolean done)})
        label
        (dom/button #js {:onClick #(when delete (delete id))} "X")))))

(def ui-item (om/factory TodoItem))

(defui ^:once ItemList
  static fc/InitialAppState
  (initial-state [this params] {:list/title "My List" :list/items []})
  static om/IQuery
  (query [this] [:ui/new-item-text :list/title {:list/items (om/get-query TodoItem)}])
  static om/Ident
  (ident [this props] [:lists/by-title (:list/title props)])
  Object
  (render [this]
    (let [{:keys [ui/new-item-text list/title list/items] :or {ui/new-item-text ""}} (om/props this)
          delete-item (fn [item-id] (om/transact! this `[(todo/delete-item {:list ~title :id ~item-id})]))
          toggle-item (fn [item-id] (om/transact! this `[(todo/toggle-done {:list ~title :id ~item-id})]))]
      (dom/div nil
        (dom/h4 nil title)
        (dom/input #js {:value (or new-item-text "") :onChange (fn [evt] (m/set-string! this :ui/new-item-text :event evt))})
        (dom/button #js {:onClick (fn [evt]
                                    (when new-item-text
                                      (om/transact! this `[(todo/add-item {:id    ~(om/tempid)
                                                                           :label ~new-item-text
                                                                           :list  ~title})])
                                      (m/set-string! this :ui/new-item-text :value "")))} "Add")
        (dom/ol nil (map (fn [item] (ui-item (om/computed item {:onDelete delete-item
                                                                :onToggle toggle-item}))) items))))))

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
