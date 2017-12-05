(ns solutions.putting-together.soln-ex-3
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
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

(defsc TodoItem [this {:keys [item/id item/label item/done]} {:keys [onDelete onToggle]}]
  {:initial-state (fn [{:keys [id label complete]}] {:item/id id :item/label label :item/done complete})
   :query         [:item/id :item/label :item/done]
   :ident         [:items/by-id :item/id]}
  (dom/li nil
    (dom/input #js {:type "checkbox" :onChange #(when onToggle (onToggle id)) :checked (boolean done)})
    label
    (dom/button #js {:onClick #(when onDelete (onDelete id))} "X")))

(def ui-item (prim/factory TodoItem))

(defsc ItemList [this {:keys [ui/new-item-text list/title list/items] :or {ui/new-item-text ""}}]
  {:initial-state (fn [params] {:list/title "My List" :list/items []})
   :query         [:ui/new-item-text :list/title {:list/items (prim/get-query TodoItem)}]
   :ident         [:lists/by-title :list/title]}
  (let [delete-item (fn [item-id] (prim/transact! this `[(todo/delete-item {:list ~title :id ~item-id})]))
        toggle-item (fn [item-id] (prim/transact! this `[(todo/toggle-done {:list ~title :id ~item-id})]))]
    (dom/div nil
      (dom/h4 nil title)
      (dom/input #js {:value (or new-item-text "") :onChange (fn [evt] (m/set-string! this :ui/new-item-text :event evt))})
      (dom/button #js {:onClick (fn [evt]
                                  (when new-item-text
                                    (prim/transact! this `[(todo/add-item {:id    ~(prim/tempid)
                                                                           :label ~new-item-text
                                                                           :list  ~title})])
                                    (m/set-string! this :ui/new-item-text :value "")))} "Add")
      (dom/ol nil (map (fn [item] (ui-item (prim/computed item {:onDelete delete-item
                                                                :onToggle toggle-item}))) items)))))

(def ui-item-list (prim/factory ItemList))

(defsc TodoList [this {:keys [ui/react-key item-list]}]
  {:initial-state (fn [params] {:ui/react-key "A" :item-list (prim/get-initial-state ItemList nil)})
   :query         [:ui/react-key {:item-list (prim/get-query ItemList)}]}
  (dom/div #js {:key react-key}
    (ui-item-list item-list)))
