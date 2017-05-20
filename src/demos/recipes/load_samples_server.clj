(ns recipes.load-samples-server
  (:require [om.next.server :as om]
            [om.next.impl.parser :as op]
            [taoensso.timbre :as timbre]
            [untangled.easy-server :as core]
            [cards.server-api :as api]
            [om.next.impl.parser :as op]))

(def all-users [{:db/id 1 :person/name "A" :kind :friend}
                {:db/id 2 :person/name "B" :kind :friend}
                {:db/id 3 :person/name "C" :kind :enemy}
                {:db/id 4 :person/name "D" :kind :friend}])

(defmethod api/server-read :load-samples.person/by-id [{:keys [ast query-root] :as env} _ p]
  (let [id     (second (:key ast))
        person (first (filter #(= id (:db/id %)) all-users))]
    {:value (assoc person :person/age-ms (System/currentTimeMillis))}))

(defmethod api/server-read :load-samples/people [env _ {:keys [kind]}]
  (Thread/sleep 400)
  (let [result (->> all-users
                 (filter (fn [p] (= kind (:kind p))))
                 (mapv (fn [p] (-> p
                                 (select-keys [:db/id :person/name])
                                 (assoc :person/age-ms (System/currentTimeMillis))))))]
    {:value result}))
