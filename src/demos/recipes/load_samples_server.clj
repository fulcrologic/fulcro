(ns recipes.load-samples-server
  (:require [om.next.server :as om]
            [om.next.impl.parser :as op]
            [taoensso.timbre :as timbre]
            [fulcro.easy-server :as core]
            [fulcro.server :refer [defquery-root defquery-entity defmutation server-mutate]]
            [om.next.impl.parser :as op]))

(def all-users [{:db/id 1 :person/name "A" :kind :friend}
                {:db/id 2 :person/name "B" :kind :friend}
                {:db/id 3 :person/name "C" :kind :enemy}
                {:db/id 4 :person/name "D" :kind :friend}])

(defquery-entity :load-samples.person/by-id
  (value [{:keys [] :as env} id p]
    (let [person (first (filter #(= id (:db/id %)) all-users))]
      (assoc person :person/age-ms (System/currentTimeMillis)))))

(defquery-root :load-samples/people
  (value [env {:keys [kind]}]
    (Thread/sleep 400)
    (let [result (->> all-users
                   (filter (fn [p] (= kind (:kind p))))
                   (mapv (fn [p] (-> p
                                   (select-keys [:db/id :person/name])
                                   (assoc :person/age-ms (System/currentTimeMillis))))))]
      result)))
