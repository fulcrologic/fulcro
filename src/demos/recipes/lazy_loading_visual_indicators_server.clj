(ns recipes.lazy-loading-visual-indicators-server
  (:require [fulcro.server :as om]
            [fulcro.client.impl.parser :as op]
            [taoensso.timbre :as timbre]
            [fulcro.easy-server :as core]
            [fulcro.server :refer [defquery-root defquery-entity defmutation server-mutate]]
            [fulcro.client.impl.parser :as op]))


(defquery-entity :lazy-load/ui
  (value [env id params]
    (Thread/sleep 2000)
    (case id
      :panel {:child {:db/id 5 :child/label "Child"}}
      :child {:items [{:db/id 1 :item/label "A"} {:db/id 2 :item/label "B"}]}
      nil)))

(defquery-entity :lazy-load.items/by-id
  (value [env id params]
    (timbre/info "Item query for " id)
    (Thread/sleep 4000)
    {:db/id id :item/label (str "Refreshed Label " (rand-int 100))}))
