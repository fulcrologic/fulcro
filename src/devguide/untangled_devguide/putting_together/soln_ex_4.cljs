(ns untangled-devguide.putting-together.soln-ex-4
  (:require [untangled.client.mutations :as m]
            [om.next :as om]
            [untangled-devguide.putting-together.soln-ex-3 :as soln3]))

(defmethod m/mutate 'pit-soln/populate-list [{:keys [state] :as env} k {:keys [id] :as p}]
  {:action (fn []
             (let [current-list-name (get-in @state [:item-list 1])
                   items (->> (:items/by-id @state)
                              vals
                              (sort-by :item/id)
                              (map #(om/ident soln3/TodoItem %))
                              vec)]
               (swap! state assoc-in [:lists/by-title current-list-name :list/items] items)))})

