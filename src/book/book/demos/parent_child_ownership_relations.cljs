(ns book.demos.parent-child-ownership-relations
  (:require
    [fulcro.client.dom :as dom]
    [fulcro.client.mutations :as m]
    [fulcro.client :as fc]
    [fulcro.client.primitives :as prim :refer [defsc]]))

; Not using an atom, so use a tree for app state (will auto-normalize via ident functions)
(def initial-state {:ui/react-key "abc"
                    :main-list    {:list/id    1
                                   :list/name  "My List"
                                   :list/items [{:item/id 1 :item/label "A"}
                                                {:item/id 2 :item/label "B"}]}})

(defonce app (atom (fc/new-fulcro-client :initial-state initial-state)))

(m/defmutation delete-item
  "Mutation: Delete an item from a list"
  [{:keys [id]}]
  (action [{:keys [state]}]
    (letfn [(filter-item [list] (filterv #(not= (second %) id) list))]
      (swap! state
        (fn [s]
          (-> s
            (update :items dissoc id)
            (update-in [:lists 1 :list/items] filter-item)))))))

(defsc Item [this
             {:keys [item/id item/label] :as props}
             {:keys [on-delete] :as computed}]
  {:initial-state (fn [{:keys [id label]}] {:item/id id :item/label label})
   :query         [:item/id :item/label]
   :ident         [:items :item/id]}
  (dom/li nil label (dom/button #js {:onClick #(on-delete id)} "X")))

(def ui-list-item (prim/factory Item {:keyfn :item/id}))

(defsc ItemList [this {:keys [list/name list/items]}]
  {:initial-state (fn [p] {:list/id    1
                           :list/name  "List 1"
                           :list/items [(prim/get-initial-state Item {:id 1 :label "A"})
                                        (prim/get-initial-state Item {:id 2 :label "B"})]})
   :query         [:list/id :list/name {:list/items (prim/get-query Item)}]
   :ident         [:lists :list/id]}
  (let [; pass the operation through computed so that it is executed in the context of the parent.
        item-props (fn [i] (prim/computed i {:on-delete #(prim/transact! this `[(delete-item {:id ~(:item/id i)})])}))]
    (dom/div nil
      (dom/h4 nil name)
      (dom/ul nil
        (map #(ui-list-item (item-props %)) items)))))

(def ui-list (prim/factory ItemList))

(defsc Root [this {:keys [ui/react-key main-list] :or {ui/react-key "ROOT"}}]
  {:initial-state (fn [p] {:main-list (prim/get-initial-state ItemList {})})
   :query         [:ui/react-key {:main-list (prim/get-query ItemList)}]}
  (dom/div #js {:key react-key} (ui-list main-list)))


