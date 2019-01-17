(ns book.demos.pre-merge.countdown-mutation
  (:require
    [book.demos.util :refer [now]]
    [fulcro.client.mutations :as m]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc InitialAppState initial-state]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-count 5)

(defsc CountdownButton [this {:ui/keys [count]}]
  {:ident     [:ui/id :ui/id]
   :query     [:ui/id :ui/count]
   :pre-merge (fn [{:keys [current-normalized data-tree]}]
                (merge
                  {:ui/id    (random-uuid)
                   :ui/count default-count}
                  current-normalized
                  data-tree))}
  (let [done? (zero? count)]
    (dom/button {:disabled done?
                 :onClick  #(m/set-value! this :ui/count (dec count))}
      (if done? "Done!" (str count)))))

(def ui-countdown-button (prim/factory CountdownButton {:keyfn ::counter-id}))

(defsc Countdown [this {::keys   [counter-label counter-initial]
                        :ui/keys [counter]}]
  {:ident     [::counter-id ::counter-id]
   :query     [::counter-id ::counter-label ::counter-initial
               {:ui/counter (prim/get-query CountdownButton)}]
   :pre-merge (fn [{:keys [current-normalized data-tree] :as x}]
                (let [initial (prim/nilify-not-found (::counter-initial data-tree))]
                  (merge
                    {:ui/counter (cond-> {} initial (assoc :ui/count initial))}
                    current-normalized
                    data-tree)))}
  (dom/div
    (dom/h4 (str counter-label " [" (or counter-initial default-count) "]"))
    (ui-countdown-button counter)))

(def ui-countdown (prim/factory Countdown {:keyfn ::counter-id}))

(m/defmutation create-countdown [countdown]
  (action [{:keys [state ref]}]
    (swap! state prim/merge-component Countdown countdown :append [::all-counters])
    (swap! state update-in ref assoc :ui/new-countdown-label "")))

(defsc Root [this {::keys [all-counters]}]
  {:initial-state (fn [_] {::all-counters
                           [{::counter-id    (prim/tempid)
                             ::counter-label "X"}
                            {::counter-id    (prim/tempid)
                             ::counter-label "Y"}
                            {::counter-id      (prim/tempid)
                             ::counter-label   "Z"
                             ::counter-initial 9}]})
   :query         [{::all-counters (prim/get-query Countdown)}
                   :ui/new-countdown-label]}
  (dom/div
    (dom/h3 "Counters")
    (dom/button {:onClick #(prim/transact! this [`(create-countdown ~{::counter-id    (prim/tempid)
                                                                      ::counter-label "New"})])}
      "Add counter")
    (dom/div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"}}
      (mapv ui-countdown all-counters))))

(defn initialize
  "To be used in :started-callback to pre-load things."
  [app])
