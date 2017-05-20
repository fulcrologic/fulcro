(ns cards.lists-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.lists-client :as client]
    [untangled.client.cards :refer [untangled-app]]
    [om.dom :as dom]
    [untangled.client.data-fetch :as df]
    [untangled.client.logging :as log]))


(dc/defcard-doc
  "# Lists

  For the most part lists are very simple. In a normalized database there will be a table of items, and other parts
  of the UI will refer to the list items by ident.

  Manipulations of the list should be done from the parent, since in general you will want the parent to re-render
  when the list changes. You use `om/computed` to pass along such locally generated data (the callback).

  Removing an item from a list requires at least one change to the database, possibly two:

  You'll always want to remove the ident from the list itself. This will cause the item to disappear from the screen. This
  is sufficient if the item is being removed from the screen, but not from the db. Remember that you're working on
  a normalized database, so you have to remove an ident, not the item in this case.

  If the item is actually being deleted from storage altogether, then you'll want to remove it from the database
  table as well.

  ```
  (m/defmutation delete-item
    \"Om Mutation: Delete an item from a list\"
    [{:keys [id]}]
    (action [{:keys [state]}]
      (letfn [(filter-item [list] (filterv #(not= (second %) id) list))]
        (swap! state
          (fn [s]
            (-> s
              (update :items dissoc id) ; remove the item from the db (optional)
              (update-in [:lists 1 :list/items] filter-item))))))) ; remove the item from the list of idents
  ```

  The source of the components for this demo are below:

  "
  (dc/mkdn-pprint-source client/Item)
  (dc/mkdn-pprint-source client/ItemList)
  (dc/mkdn-pprint-source client/Root))

(dc/defcard lazy-loading-demo
  "
  # Demo

  Notice how the ident is removed from the vector at path `[:lists 1 :list/items]`.  This will cause it to disappear
  from the screen, since that is the data for the component displaying the list. Also note how the map of attributes
  at path `[:items ID]` is also removed.
  "
  (untangled-app client/Root)
  {}
  {:inspect-data true})
