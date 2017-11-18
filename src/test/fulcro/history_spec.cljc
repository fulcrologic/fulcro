(ns fulcro.history-spec
  (:require [fulcro-spec.core :refer [specification behavior provided assertions when-mocking]]
            [clojure.string :as str]
            [fulcro.history :as hist]
            [fulcro.client.primitives :as prim]))

(def empty-history {::hist/max-size 5 ::hist/history-steps {} ::hist/active-remotes {}})
(def mock-step {::hist/db-after {} ::hist/db-before {} ::hist/client-time #?(:cljs (js/Date.)
                                                                             :clj  (java.util.Date.))})
(def compressible-step {::hist/tx (hist/compressible-tx []) ::hist/db-after {} ::hist/db-before {} ::hist/client-time #?(:cljs (js/Date.)
                                                                                                                         :clj  (java.util.Date.))})

(specification "is-timestamp? can detect date/times" :focused
  #?(:cljs
     (assertions
       "in cljs"
       (hist/is-timestamp? (js/Date.)) => true)
     :clj
     (assertions
       "in clj"
       (hist/is-timestamp? (java.util.Date.)) => true)))

(specification "Oldest active network request" :focused
  (assertions
    "is the maximum long if there are none active"
    (hist/oldest-active-network-request empty-history) => hist/max-tx-time
    "is the smalled tx time from the active remotes"
    (hist/oldest-active-network-request (assoc empty-history ::hist/active-remotes {:a #{5 7} :b #{3 42}})) => 3))

(specification "Garbage collecting history" :focused
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

(specification "Last tx time" :focused
  (let [steps   {1 mock-step 2 mock-step 3 mock-step 4 mock-step 5 mock-step}
        history (assoc empty-history ::hist/history-steps steps)]
    (assertions
      "Is zero if there are no steps"
      (hist/last-tx-time empty-history) => 0
      "Is the largest of the recorded tx time in history"
      (hist/last-tx-time history) => 5)))

(specification "Recording history steps" :focused
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

(specification "Remote activity tracking" :focused
  (let [h1 (hist/remote-activity-started empty-history :a 4)
        h2 (hist/remote-activity-started h1 :b 6)
        h3 (hist/remote-activity-started h2 :a 9)
        h4 (hist/remote-activity-started h3 :b 44)
        h5 (hist/remote-activity-finished h4 :a 4)
        h6 (hist/remote-activity-finished h5 :a 9)
        h7 (hist/remote-activity-finished h6 :b 44)
        h8 (hist/remote-activity-finished h7 :b 6)]
    (assertions
      "records data such that the oldest active request is available"
      (hist/oldest-active-network-request h1) => 4
      (hist/oldest-active-network-request h2) => 4
      (hist/oldest-active-network-request h3) => 4
      (hist/oldest-active-network-request h4) => 4
      (hist/oldest-active-network-request h5) => 6
      (hist/oldest-active-network-request h6) => 6
      (hist/oldest-active-network-request h7) => 6
      "once all activity is finished, reflects that as max-tx-time"
      (hist/oldest-active-network-request h8) => hist/max-tx-time)))

(specification "History lookup (get-step)" :focused
  (let [time         14
        history-1    (hist/record-history-step (assoc empty-history ::hist/max-size 100) time (with-meta mock-step {:step 1}))
        time-missing (inc time)
        time-2       (+ 4 time)
        history-2    (hist/record-history-step history-1 time-2 (with-meta mock-step {:step 2}))
        time-3       (inc time-2)
        history-3    (hist/record-history-step history-2 time-3 (with-meta compressible-step {:step 3}))
        time-4       (inc time-3)
        history-4    (hist/record-history-step history-3 time-4 (with-meta compressible-step {:step 4}))
        step         (fn [s] (some-> s meta :step))]
    (assertions
      "finds history items by their tx-time"
      (step (hist/get-step history-4 time-2)) => 2
      "when steps are compressed away, returns the step that it was compressed into"
      (step (hist/get-step history-4 time-3)) => 4
      "returns nil when the time is outside of current history"
      (step (hist/get-step history-4 7)) => nil
      "returns the step just before the given time if no step exists for that exact time"
      (step (hist/get-step history-4 time-missing)) => 1)))

(specification "History Navigator" :focused
  (let [time         14
        history-1    (hist/record-history-step (assoc empty-history ::hist/max-size 100) time (with-meta mock-step {:step 1}))
        time-missing (inc time)
        time-2       (+ 4 time)
        history-2    (hist/record-history-step history-1 time-2 (with-meta mock-step {:step 2}))
        time-3       (inc time-2)
        history-3    (hist/record-history-step history-2 time-3 (with-meta compressible-step {:step 3}))
        time-4       (inc time-3)
        history-4    (hist/record-history-step history-3 time-4 (with-meta compressible-step {:step 4}))
        step         (fn [s] (some-> s meta :step))
        nav          (hist/history-navigator history-4)]
    (assertions
      "Starts out on the last step"
      (step (hist/current-step nav)) => 4
      "can walk backwards to the beginning"
      (step (-> nav (hist/focus-previous) (hist/current-step))) => 2
      (step (-> nav (hist/focus-previous) (hist/focus-previous) (hist/current-step))) => 1
      "will not walk off of the end of history"
      (step (-> nav (hist/focus-previous) (hist/focus-previous) (hist/focus-previous) (hist/current-step))) => 1
      (step (-> nav hist/focus-next hist/current-step)) => 4)))

(specification "nav-position" :focused
  (let [time         14
        history-1    (hist/record-history-step (assoc empty-history ::hist/max-size 100) time (with-meta mock-step {:step 1}))
        time-missing (inc time)
        time-2       (+ 4 time)
        history-2    (hist/record-history-step history-1 time-2 (with-meta mock-step {:step 2}))
        nav          (hist/history-navigator history-2)
        [index frames] (hist/nav-position nav)]
    (assertions
      "includes the index offset"
      index => 1
      "includes the number of frames in the history"
      frames => 2)))
