(ns cards.parent-child-ownership-relations
  (:require
    [devcards.core :as dc :include-macros true]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.dom :as dom]
    [fulcro.client.mutations :as m]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
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


(dc/defcard-doc
  "# Parent-Child Relationships

  UIs often have parent-child relationships. In these cases, the parent can be seen as a UI component in control of the
  children (it is responsible for telling them to render). In such cases it is usually better to reason about
  the *management* logic (i.e. deleting, reordering, etc) from the parent; however, it is commonly the case the
  you want the *child* to render some controls, such as a delete button.

  The most basic example of this is the list. Lists are very simple.
  In a normalized database there will be a table of items, and other parts
  of the UI will refer to the list items by ident.

  In order to accomplish this the parent must create some things. Perhaps it needs to ask a list item to highlight,
  or it wants to pass a callback that can be used by the child to indicate `onDelete`.

  In Fulcro you *must* pass such calculated values via `prim/computed`.

  This is because the underlying system can cause UI refreshes on targeted components (e.g. the items themselves). In
  those cases the way it works is that Fulcro runs the query on the item and forces an update. When it does so, *it
  only has the data from the query!*. Any computed values that were originally passed from the parent are *not* in
  the database! The `computed` function sends the parent-generated data to the component, and causes the component
  itself to cache those values behind the scenes (after all, the parent has not refresh, so those values should still
  be ok).

  So, in our list example let's talk about removing and item.
  Removing an item from a list requires at least one change to the database, possibly two:

  - You'll always want to remove the ident from the list itself. This will cause the item to disappear from the screen. This
  is sufficient if the item is being removed from the screen, but not from the db. Remember that you're working
  on a normalized database, so you have to remove an ident, not the item in this case.
  - If the item is provably being deleted from storage altogether, then you'll want to remove it from the database
  table as well. Remember that removal from a list is not necessarily deletion of the item.

  In our example, we'll assume both need to happen:

  ```
  (m/defmutation delete-item
    \"Mutation: Delete an item from a list\"
    [{:keys [id]}]
    (action [{:keys [state]}]
      (letfn [(filter-item [list] (filterv #(not= (second %) id) list))]
        (swap! state
          (fn [s]
            (-> s
              (update :items dissoc id) ; remove the item from the db (optional)
              (update-in [:lists 1 :list/items] filter-item))))))) ; remove the item from the list of idents
  ```

  The source of the components for this demo are below. Make note of the use of `computed`:

  "
  (dc/mkdn-pprint-source Item)
  (dc/mkdn-pprint-source ItemList)
  (dc/mkdn-pprint-source Root))

(defcard-fulcro modify-list-card
  "# Demo"
  Root
  {}
  {:inspect-data true})
