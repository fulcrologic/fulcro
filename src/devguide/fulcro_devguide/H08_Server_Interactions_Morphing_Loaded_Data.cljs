(ns fulcro-devguide.H08-Server-Interactions-Morphing-Loaded-Data
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :refer [defmutation]]
            [fulcro.client :as fc]))

(defsc CategoryQuery [this props]
  {:query [:db/id :category/name]
   :ident [:categories/by-id :db/id]})

(defsc ItemQuery [this props]
  {:query [:db/id :item/name {:item/category (prim/get-query CategoryQuery)}]
   :ident [:items/by-id :db/id]})

(def sample-server-response
  {:all-items [{:db/id 5 :item/name "item-42" :item/category {:db/id 1 :category/name "A"}}
               {:db/id 6 :item/name "item-92" :item/category {:db/id 1 :category/name "A"}}
               {:db/id 7 :item/name "item-32" :item/category {:db/id 1 :category/name "A"}}
               {:db/id 8 :item/name "item-52" :item/category {:db/id 2 :category/name "B"}}]})

(def component-query [{:all-items (prim/get-query ItemQuery)}])

(def hand-written-query [{:all-items [:db/id :item/name
                                      {:item/category [:db/id :category/name]}]}])

(defsc ToolbarItem [this {:keys [item/name]}]
  {:query [:db/id :item/name]
   :ident [:items/by-id :db/id]}
  (dom/li nil name))

(def ui-toolbar-item (prim/factory ToolbarItem {:keyfn :db/id}))

(defsc ToolbarCategory [this {:keys [category/name category/items]}]
  {:query [:db/id :category/name {:category/items (prim/get-query ToolbarItem)}]
   :ident [:categories/by-id :db/id]}
  (dom/li nil
    name
    (dom/ul nil
      (map ui-toolbar-item items))))

(def ui-toolbar-category (prim/factory ToolbarCategory {:keyfn :db/id}))

(defmutation group-items-reset [params]
  (action [{:keys [state]}]
    (reset! state (prim/tree->db component-query sample-server-response true))))

(defn add-to-category
  "Returns a new db with the given item added into that item's category."
  [db item]
  (let [category-ident (:item/category item)
        item-location  (conj category-ident :category/items)]
    (update-in db item-location (fnil conj []) (prim/ident ItemQuery item))))

(defn group-items*
  "Returns a new db with all of the items sorted by name and grouped into their categories."
  [db]
  (let [sorted-items   (->> db :items/by-id vals (sort-by :item/name))
        category-ids   (-> db (:categories/by-id) keys)
        clear-items    (fn [db id] (assoc-in db [:categories/by-id id :category/items] []))
        db             (reduce clear-items db category-ids)
        db             (reduce add-to-category db sorted-items)
        all-categories (->> db :categories/by-id vals (mapv #(prim/ident CategoryQuery %)))]
    (assoc db :toolbar/categories all-categories)))

(defmutation ^:intern group-items [params]
  (action [{:keys [state]}]
    (swap! state group-items*)))

(defsc Toolbar [this {:keys [toolbar/categories]}]
  {:query [{:toolbar/categories (prim/get-query ToolbarCategory)}]}
  (dom/div nil
    (dom/button #js {:onClick #(prim/transact! this `[(group-items {})])} "Trigger Post Mutation")
    (dom/button #js {:onClick #(prim/transact! this `[(group-items-reset {})])} "Reset")
    (dom/ul nil
      (map ui-toolbar-category categories))))

(defcard-doc
  "
  # Morphing Loaded Data

  The targeting system that we discussed in the prior section is great for cases where your data-driven query gets you
  exactly what you need for the UI. In fact, since you can process the query on the server it is entirely possible that
  load with targeting is all you'll ever need; however, from a practical perspective it may turn out that you've got a
  server that can easily understand certain shapes of data-driven queries, but not others.

  For example, say you were pulling a list of items from a database. It might be trivial to pull that graph of data
  from the server from the perspective of a list of items, but let's say that each item had a category. Perhaps you'd like to
  group the items by category in the UI.

  The data-driven way to handle that is to make the server understand the UI query that has them grouped by category; however,
  that implies that you might end up embedding code on your server to handle a way of looking at data that is really
  specific to one kind of UI. That tends to push us back toward a proliferation of code on the server that was a nightmare
  in REST.

  Another way of handling this is to accept the fact that our data-driven queries have some natural limits: If the
  database on the server can easily produce the graph, then we should let it do so from the data-driven query; however,
  in some cases it may make more sense to let the UI morph the incoming data into a shape that makes more sense to that
  UI.

  ```text
  Simple Query Result from Server
              |
     auto merge/normalize
              |
             \\|/
      Items with Categories (natural shape from the server)
              |
         post mutation
              |
             \\|/
      Items by Category (shape we want in the UI)
  ```

  We all understand doing these kinds of transforms. It's just data manipulation. So, you may find this has some
  distinct advantages:

  - Simple query to the server (only have to write one query handler) that is a natural fit for the database there.
  - Simple layout in resulting UI database (normalized into tables and a graph)
  - Straightforward data transform into what we want to show

  ## Post Mutations

  Fulcro calls these kinds of post-load transforms *Post Mutations*. The reason for this name is that it requires
  you define the transform as a mutation, and then allows you to ask for that transform to trigger when the load is
  complete. The API is trivial:

  ```
  (df/load this :items Item {:post-mutation `group-items})
  ```

  and you can include parameters with `:post-mutation-params`.

  The post-mutation is defined as you might expect:

  "
  (dc/mkdn-pprint-source add-to-category)
  (dc/mkdn-pprint-source group-items*)
  (dc/mkdn-pprint-source group-items)
  "

  and the UI components are:

  "
  (dc/mkdn-pprint-source ToolbarItem)
  (dc/mkdn-pprint-source ToolbarCategory)
  (dc/mkdn-pprint-source Toolbar))

(defcard-fulcro toolbar-with-items-by-category
  "This card allows you to simulate the post-mutation defined above, and see the resulting UI and database change. The
  Reset button will restore the db to the pre-mutation state, so you can A/B compare the before and after picture."
  Toolbar
  (prim/tree->db component-query sample-server-response true)
  {:inspect-data true})


(defcard-doc
  "
  ## Using `defsc` For Server Queries

  It is perfectly legal to use `defsc` to define an graph query (and normalization) for something like this that doesn't exactly
  exist on your UI. This can be quite useful in the presence of post mutations that can re-shape the data.

  Simply code your (nested) queries using `defsc`, and skip writing the body:

  "
  (dc/mkdn-pprint-source ItemQuery)
  "

  ```
  (df/load this :all-items ItemQuery {:post-mutation `group-items})
  ```

  NOTE: We know that the name `defsc` seems a bit of a misnomer for this, so feel free to create an alias for it.

  ## What's Next?

  Now we can get the data into our client in whatever form we need it. Now let's [talk about full-stack changes!](#!/fulcro_devguide.H10_Server_Interactions_Mutations)
  ")
