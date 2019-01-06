(ns book.demos.pre-merge.countdown-many
  (:require
    [fulcro.client :as fc]
    [fulcro.client.data-fetch :as df]
    [book.demos.util :refer [now]]
    [fulcro.client.mutations :as m]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc InitialAppState initial-state]]
    [fulcro.client.data-fetch :as df]
    [fulcro.server :as server]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def all-counters
  [{::counter-id 1 ::counter-label "A"}
   {::counter-id 2 ::counter-label "B"}
   {::counter-id 3 ::counter-label "C"}
   {::counter-id 4 ::counter-label "D"}])

(server/defquery-root ::all-counters
  (value [_ _]
    all-counters))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defsc Countdown [this {::keys   [counter-label]
                        :ui/keys [count]}]
  {:ident     [::counter-id ::counter-id]
   :query     [::counter-id ::counter-label :ui/count]
   :pre-merge (fn [{:keys [current-normalized data-tree]}]
                ; <1>
                (merge
                  {:ui/count 5}
                  current-normalized
                  data-tree))}
  (dom/div
    (dom/h4 counter-label)
    (let [done? (zero? count)]
      (dom/button {:disabled done?
                   :onClick  #(m/set-value! this :ui/count (dec count))}
        (if done? "Done!" (str count))))))

(def ui-counter (prim/factory Countdown {:keyfn ::counter-id}))

(defsc Root [this {::keys [all-counters]}]
  {:initial-state (fn [_] {})
   :query         [{::all-counters (prim/get-query Countdown)}]}
  (dom/div
    (dom/h3 "Counters")
    (if (seq all-counters)
      (dom/div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"}}
        (mapv ui-counter all-counters))
      (dom/button {:onClick #(df/load this ::all-counters Countdown)}
        "Load many counters"))))

(defn initialize
  "To be used in :started-callback to pre-load things."
  [app])
