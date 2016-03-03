(ns untangled.client.protocol-support-spec
  (:require
    [untangled-spec.core :refer-macros [specification behavior provided component assertions]]
    [untangled.client.protocol-support :as ps
     :refer-macros [with-methods]]
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
                                           :foo 5}
                        :ui-tx '[(inc-thing)]
                        :optimistic-delta {[:thing] (ps/with-behavior "it appends the last thing +1" [0 1])
                                           [:foo] 5}}
        inc-thing-fn (fn [{:keys [state]} _ _]
                       (swap! state update :thing #(conj % (inc (last %)))))]
    (behavior "check-optimistic-update"
      (with-methods mut/mutate {'inc-thing inc-thing-fn}
                    (ps/check-optimistic-update silly-protocol))))

  (let [silly-protocol {:initial-ui-state {:fake "fake"}
                        :ui-tx     '[(do/thing {}) :not-sent]
                        :server-tx '[(do/thing)]}
        do-thing-fn (fn [_ _ _] {:remote true})]
    (behavior "check-server-tx"
      (with-methods mut/mutate {'do/thing do-thing-fn}
                    (ps/check-server-tx silly-protocol))))

  (let [silly-protocol {:response {:thing/by-id {0 {:thing 1}}}
                        :pre-response-state {:thing/by-id {}}
                        :merge-delta {[:thing/by-id 0] {:thing 1}}}]
    (behavior "check-response"
      (ps/check-response-from-server silly-protocol))))
