(ns recipes.lists-client
  (:require
    [fulcro.client.mutations :as m]
    [fulcro.client.core :as fc]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer-macros [defui]]))

; Not using an atom, so use a tree for app state (will auto-normalize via ident functions)
(def initial-state {:ui/react-key "abc"
                    :main-list    {:list/id    1
                                   :list/name  "My List"
                                   :list/items [{:item/id 1 :item/label "A"}
                                                {:item/id 2 :item/label "B"}]}})

(defonce app (atom (fc/new-fulcro-client :initial-state initial-state)))

(m/defmutation delete-item
  "Om Mutation: Delete an item from a list"
  [{:keys [id]}]
  (action [{:keys [state]}]
    (letfn [(filter-item [list] (filterv #(not= (second %) id) list))]
      (swap! state
        (fn [s]
          (-> s
            (update :items dissoc id)
            (update-in [:lists 1 :list/items] filter-item)))))))

(defui ^:once Item
  static fc/InitialAppState
  (initial-state [c {:keys [id label]}] {:item/id id :item/label label})
  static prim/IQuery
  (query [this] [:item/id :item/label])
  static prim/Ident
  (ident [this props] [:items (:item/id props)])
  Object
  (render [this]
    (let [{:keys [on-delete]} (prim/get-computed this)
          {:keys [item/id item/label]} (prim/props this)]
      (dom/li nil
        label
        (dom/button #js {:onClick #(on-delete id)} "X")))))

(def ui-list-item (prim/factory Item {:keyfn :item/id}))

(defui ^:once ItemList
  static fc/InitialAppState
  (initial-state [c p] {:list/id    1
                        :list/name  "List 1"
                        :list/items [(fc/get-initial-state Item {:id 1 :label "A"})
                                     (fc/get-initial-state Item {:id 2 :label "B"})]})
  static prim/IQuery
  (query [this] [:list/id :list/name {:list/items (prim/get-query Item)}])
  static prim/Ident
  (ident [this props] [:lists (:list/id props)])
  Object
  (render [this]
    (let [{:keys [list/name list/items]} (prim/props this)
          ; pass the operation through computed so that it is executed in the context of the parent.
          item-props (fn [i] (prim/computed i {:on-delete #(prim/transact! this `[(delete-item {:id ~(:item/id i)})])}))]
      (dom/div nil
        (dom/h4 nil name)
        (dom/ul nil
          (map #(ui-list-item (item-props %)) items))))))

(def ui-list (prim/factory ItemList))

(defui ^:once Root
  static fc/InitialAppState
  (initial-state [c p] {:main-list (fc/get-initial-state ItemList {})})
  static prim/IQuery
  (query [this] [:ui/react-key {:main-list (prim/get-query ItemList)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key main-list] :or {ui/react-key "ROOT"}} (prim/props this)]
      (dom/div #js {:key react-key} (ui-list main-list)))))
