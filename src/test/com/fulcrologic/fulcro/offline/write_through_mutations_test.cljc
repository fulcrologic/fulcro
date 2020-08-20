(ns com.fulcrologic.fulcro.offline.write-through-mutations-test
  (:require
    [com.fulcrologic.fulcro.offline.tempid-strategy :as t-strat]
    [fulcro-spec.core :refer [specification provided! behavior assertions]]
    #?(:clj  [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
       :cljs [com.fulcrologic.fulcro.algorithms.tempid :as tempid :refer [TempId]]))
  #?(:clj
     (:import
       (com.fulcrologic.fulcro.algorithms.tempid TempId))))

(declare =>)

(specification "TempIDisRealIDStrategy"
  (let [id1                 (tempid/tempid)
        id2                 (tempid/tempid)
        id3                 (tempid/tempid)
        id4                 (tempid/tempid)
        ids                 [id1 id2 id3 id4]
        real-id             (fn [^TempId id] (.-id id))
        txn                 `[(~'f ~{:id     id1
                                     :things [{:id id2 :child {:id id3}} {:id id4}]})]
        strategy            (t-strat/->TempIDisRealIDStrategy)
        expected-resolution (zipmap ids (map real-id ids))
        resolved-ids        (t-strat/-resolve-tempids strategy txn)]
    (assertions
      "Generates a tempid remapping that uses the IDs of the tempids themselves as real IDs"
      resolved-ids => expected-resolution)))
