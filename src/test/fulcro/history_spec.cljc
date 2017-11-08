(ns fulcro.history-spec
  (:require [fulcro-spec.core :refer [specification behavior provided assertions when-mocking]]
            [clojure.string :as str]
            [fulcro.history :as hist]
            [fulcro.client.primitives :as prim]))

(def empty-history {::hist/max-size 5 ::hist/history-steps {} ::hist/active-remotes {}})
(def mock-step {::hist/db-after {} ::hist/db-before {}})
(def compressible-step {::hist/tx (hist/compressible-tx []) ::hist/db-after {} ::hist/db-before {}})

(specification "Oldest active network request"
  (assertions
    "is the maximum long if there are none active"
    (hist/oldest-active-network-request empty-history) => Long/MAX_VALUE
    "is the smalled tx time from the active remotes"
    (hist/oldest-active-network-request (assoc empty-history ::hist/active-remotes {:a #{5 7} :b #{3 42}})) => 3))

(specification "Garbage collecting history"
  (let [steps                           {1 mock-step 2 mock-step 3 mock-step 4 mock-step 5 mock-step 6 mock-step}
        history                         (assoc empty-history ::hist/history-steps steps)
        history-with-active-remotes     (assoc history ::hist/max-size 3 ::hist/active-remotes {:a #{3 6}})
        new-history                     (hist/gc-history history)
        new-history-with-active-remotes (hist/gc-history history-with-active-remotes)]
    (assertions
      "trims the history to the max-size most recent items  "
      (-> new-history ::hist/history-steps keys set) => #{2 3 4 5 6}
      "does not trim history steps that are still needed by active remotes"
      (-> new-history-with-active-remotes ::hist/history-steps keys set) => #{3 4 5 6})))

(specification "Compressible transactions" :focused
  (assertions
    "can be marked with compressible-tx"
    (hist/compressible-tx? (hist/compressible-tx [])) => true
    (hist/compressible-tx? []) => false
    "compressible-tx? returns false for normal transactions "
    (hist/compressible-tx? []) => false)
  (when-mocking
    (prim/transact! r tx) => (assertions
                               "Are marked compressible by prim/compressible-transact!"
                               (hist/compressible-tx? tx) => true)

    (prim/compressible-transact! :mock-reconciler [])))

(specification "Last tx time"
  (let [steps   {1 mock-step 2 mock-step 3 mock-step 4 mock-step 5 mock-step}
        history (assoc empty-history ::hist/history-steps steps)]
    (assertions
      "Is zero if there are no steps"
      (hist/last-tx-time empty-history) => 0
      "Is the largest of the recorded tx time in history"
      (hist/last-tx-time history) => 5)))

(specification "Recording history steps"
  (let [time      14
        history   (hist/record-history-step empty-history time mock-step)
        history-1 (hist/record-history-step (assoc empty-history ::hist/max-size 100) time mock-step)
        time-2    (inc time)
        history-2 (hist/record-history-step history-1 time-2 mock-step)
        time-3    (inc time-2)
        history-3 (hist/record-history-step history-2 time-3 compressible-step)
        time-4    (inc time-3)
        history-4 (hist/record-history-step history-3 time-4 compressible-step)]
    (assertions
      "records the given step into the history under the current time"
      (-> history ::hist/history-steps (get time)) => mock-step
      "causes the removal of the most recent entry if both it and the new step are compressible"
      (-> history-4 ::hist/history-steps keys set) => #{time time-2 time-4})))
