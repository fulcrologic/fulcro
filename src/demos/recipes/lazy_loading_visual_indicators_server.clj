(ns recipes.lazy-loading-visual-indicators-server
  (:require [om.next.server :as om]
            [om.next.impl.parser :as op]
            [taoensso.timbre :as timbre]
            [untangled.easy-server :as core]
            [cards.server-api :as api]
            [om.next.impl.parser :as op]))


(defmethod api/server-read :lazy-load/ui [{:keys [ast query] :as env} dispatch-key params]
  (let [component (second (:key ast))]
    (Thread/sleep 1000)
    (case component
      :panel {:value {:child {:db/id 5 :child/label "Child"}}}
      :child {:value {:items [{:db/id 1 :item/label "A"} {:db/id 2 :item/label "B"}]}}
      nil)))

(defmethod api/server-read :lazy-load.items/by-id [{:keys [query-root] :as env} _ params]
  (let [id (second query-root)]
    (timbre/info "Item query for " id)
    (Thread/sleep 1000)
    {:value {:db/id id :item/label (str "Refreshed Label " (rand-int 100))}}))
