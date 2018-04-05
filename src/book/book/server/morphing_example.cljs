(ns book.server.morphing-example
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [book.macros :refer [defexample]]
            [fulcro.client.cards :refer [defcard-fulcro]]
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
  (dom/li name))

(def ui-toolbar-item (prim/factory ToolbarItem {:keyfn :db/id}))

(defsc ToolbarCategory [this {:keys [category/name category/items]}]
  {:query [:db/id :category/name {:category/items (prim/get-query ToolbarItem)}]
   :ident [:categories/by-id :db/id]}
  (dom/li
    name
    (dom/ul
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
  (dom/div
    (dom/button {:onClick #(prim/transact! this `[(group-items {})])} "Trigger Post Mutation")
    (dom/button {:onClick #(prim/transact! this `[(group-items-reset {})])} "Reset")
    (dom/ul
      (map ui-toolbar-category categories))))

(defexample "Morphing Data" Toolbar "morphing-example" :initial-state (atom (prim/tree->db component-query sample-server-response true)))

