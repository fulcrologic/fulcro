(ns untangled.client.protocol-support-spec
  (:require
    [untangled-spec.core :refer-macros [specification behavior provided component assertions]]
    [untangled.client.protocol-support :as ps :refer-macros [with-methods]]
    [untangled.client.mutations :as mut]))

(specification "Client Protocol Testing"
  (behavior "with-methods macro runs body with extra multi methods"
    (do (defmulti my-multi (fn [x] x))
        (defmethod my-multi 'minus [x] (dec x))
        (defmethod my-multi 'plus [x] (inc x))
        (with-methods my-multi {'plus (fn [x] :new-plus)}
          (assertions
            ((get-method my-multi 'plus) 0) => :new-plus))
        (assertions
          "resetting the multimethod when its done"
          ((get-method my-multi 'plus) 0) => 1)))

  (let [silly-protocol {:initial-ui-state {:thing [0]
                                           :foo   5}
                        :ui-tx            '[(inc-thing)]
                        :optimistic-delta {[:thing] (ps/with-behavior "it appends the last thing +1" [0 1])
                                           [:foo]   5}}
        inc-thing-fn (fn [{:keys [state]} _ _]
                       (swap! state update :thing #(conj % (inc (last %)))))]
    (behavior "check-optimistic-update"
      (with-methods mut/mutate {'inc-thing inc-thing-fn}
        (ps/check-optimistic-update silly-protocol))))

  (let [silly-protocol {:initial-ui-state {:fake "fake"}
                        :ui-tx            '[(do/thing {}) :not-sent]
                        :server-tx        '[(do/thing)]}
        do-thing-fn (fn [_ _ _] {:remote true})]
    (behavior "check-server-tx"
      (with-methods mut/mutate {'do/thing do-thing-fn}
        (ps/check-server-tx silly-protocol))))

  (let [silly-protocol {:response           {:thing/by-id {0 {:thing 1}}}
                        :pre-response-state {:thing/by-id {}}
                        :merge-delta        {[:thing/by-id 0] {:thing 1}}}]
    (behavior "check-response"
      (ps/check-response-from-server silly-protocol)))

  (let [silly-protocol {:initial-ui-state   {:thing [0]
                                             :foo   5}
                        :ui-tx              '[(dummy-string)]
                        :optimistic-delta   {[:thing] #"^[A-Za-z]+$"
                                             [:foo]   5}
                        :response           {:thing/by-id {0 {:thing "foobarbaz"}}}
                        :pre-response-state {:thing/by-id {}}
                        :merge-delta        {[:thing/by-id 0 :thing] #".*bar.*"}}
        dummy-string-fn (fn [{:keys [state]} _ _]
                          (swap! state assoc :thing "FooBarbaz"))]

    (behavior "handles regular expressions as value in deltas"
      (with-methods mut/mutate {'dummy-string dummy-string-fn}
        (ps/check-optimistic-update silly-protocol)
        (ps/check-response-from-server silly-protocol))))

  (let [silly-protocol {:initial-ui-state {:x/by-id {13 {:val 0}}}
                        :ui-tx            '[(inc-it)]
                        :optimistic-delta {[:x/by-id 13 :val] 1}}
        inc-it-fn (fn [{:keys [state ref]} _ _]
                    (swap! state update-in (conj ref :val) inc))]
    (behavior "can pass an optional env to the parser, eg: to mock :ref"
      (with-methods mut/mutate {'inc-it inc-it-fn}
        (ps/check-optimistic-update silly-protocol :env {:ref [:x/by-id 13]}))
      (assertions ":state in the env is not allowed, as it should come from the protocol"
        (ps/check-optimistic-update nil :env {:state :should-not-allowed})
        =throws=> (js/Error #"state not allowed in the env")))))
