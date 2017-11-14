(ns recipes.mutation-join-server
  (:require [fulcro.server :as prim]
            [fulcro.client.impl.parser :as op]
            [taoensso.timbre :as timbre]
            [fulcro.easy-server :as core]
            [fulcro.server :refer [defquery-root defquery-entity defmutation server-mutate]]
            [fulcro.client.impl.parser :as op]))

(defquery-root :mutation-join-list
  (value [env {:keys [kind]}]
    {:db/id      1
     :list/title "Mutation Join List Demo"
     :list/items [{:db/id      1
                   :item/value "A"}
                  {:db/id      2
                   :item/value "B"}]}))

(defmutation cards.mutation-join-cards/change-label [{:keys [db/id item/value]}]
  (action [env]
    {:db/id id :item/value value}))
