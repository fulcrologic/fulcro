(ns recipes.lazy-loading-visual-indicators-server
  (:require [om.next.server :as om]
            [om.next.impl.parser :as op]
            [taoensso.timbre :as timbre]
            [fulcro.easy-server :as core]
            [fulcro.server :refer [defquery-root defquery-entity defmutation server-mutate]]
            [om.next.impl.parser :as op]))


(defquery-entity :lazy-load/ui
  (value [env id params]
    (Thread/sleep 1000)
    (case id
      :panel {:child {:db/id 5 :child/label "Child"}}
      :child {:items [{:db/id 1 :item/label "A"} {:db/id 2 :item/label "B"}]}
      nil)))

(defquery-entity :lazy-load.items/by-id
  (value [env id params]
    (timbre/info "Item query for " id)
    (Thread/sleep 1000)
    {:db/id id :item/label (str "Refreshed Label " (rand-int 100))}))
