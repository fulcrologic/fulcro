(ns solutions.putting-together
  (:require [app.api :refer [apimutate api-read]]
            [taoensso.timbre :as timbre]))

;; Database format is just a map of maps. The list of items is sent to the client sorted by item ID
(def lists (atom {"My List" {}}))
(def next-id (atom 0))

(defmethod apimutate 'pit-soln/add-item-ex3 [e k {:keys [id label list]}]
  {:action (fn []
             (let [new-id (swap! next-id inc)]
               (swap! lists update-in [list] assoc new-id {:item/id new-id :item/label label :item/done false})
               (timbre/info "Added item: " @lists)
               {:tempids {id new-id}}))})

(defmethod apimutate 'pit-soln/toggle-done-ex3 [e k {:keys [id list]}]
  {:action (fn []
             (swap! lists update-in [list id :item/done] not)
             (timbre/info "Toggled item: " id " in " list ": " @lists))})

(defmethod apimutate 'pit-soln/delete-item-ex3 [e k {:keys [id list]}]
  {:action (fn []
             (swap! lists update-in [list] dissoc id)
             (timbre/info "Deleted item: " id " in " list ": " @lists))})

(defmethod api-read :ex4/list [{:keys [ast query]} k {:keys [list] :as p}]
  (when (contains? @lists list)
    (let [items (vec (sort-by :item/id (vals (get @lists list))))]
      (timbre/info "Returning " items)
      {:value items})))

(defmethod api-read :lists/by-title [{:keys [ast] :as env} k p]
  (let [list-name (second (:key ast))
        items (vec (sort-by :item/id (vals (get @lists list-name))))]
    (timbre/info "List " env)
    {:value {:list/title list-name :list/items items}}))
