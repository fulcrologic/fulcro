(ns recipes.mutation-join-server
  (:require [fulcro.client.primitives :as prim]
            [fulcro.server :refer [defquery-root defquery-entity defmutation server-mutate]]
            [fulcro.util :as util]))

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
    {:db/id id :item/value (str (util/unique-key))}))

(def ids (atom 999))

(defmutation cards.mutation-join-cards/add-item [{:keys [id value]}]
  (action [env]
    (Thread/sleep 4000)
    (let [new-id (swap! ids inc)]
      (merge
        {::prim/tempids {id new-id}}
        {:db/id id :item/value value}))))
