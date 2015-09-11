(ns untangled.events-spec
  (:require-macros [cljs.test :refer (is deftest testing)]
                   [smooth-test.core :refer (specification behavior provided assertions)]
                   )
  (:require [untangled.events :as evts]
            smooth-test.stub
            [cljs.test :refer [do-report]]
            [untangled.logging :as logging])
  )

(specification "An event trigger -- 'trigger' function"
               (let [triggered (atom {})
                     trigger-fn (fn [kw] (swap! triggered assoc kw true))
                     context-single-listener {:event-listeners [{:a (partial trigger-fn :a)
                                                                 :b (partial trigger-fn :b)
                                                                 :c (partial trigger-fn :c)}]}
                     context-multi-listeners {:event-listeners (conj (:event-listeners context-single-listener)
                                                                     {:a (partial trigger-fn :a1)
                                                                      :b (partial trigger-fn :b1)})}
                     context-invalid-listener {:event-listeners [{:a 5}]}
                     single-event :a
                     multi-events [:a :b :c]
                     ]
                 (behavior "accepts a single event"
                           (evts/trigger context-single-listener single-event)

                           (is (:a @triggered))

                           (swap! triggered {}))
                 (behavior "accepts a list of events"
                           (evts/trigger context-single-listener multi-events)

                           (is (and (:a @triggered) (:b @triggered) (:c @triggered)))

                           (swap! triggered {}))
                 (behavior "invokes each event function that is keyed to that event"
                           (evts/trigger context-multi-listeners single-event)

                           (is (and (:a @triggered) (:a1 @triggered)))

                           (swap! triggered {}))
                 (behavior "works if no event handler is present for the given event"
                           (evts/trigger context-multi-listeners multi-events)

                           (is (and (:a @triggered) (:b @triggered) (:c @triggered) (:a1 @triggered) (:b1 @triggered))))
                 (behavior "logs an error for debugging"
                           (provided "if handler is not a function"
                                     (logging/log msg) =1x=> (is (= "ERROR: TRIGGERED EVENT HANDLER MUST BE A FUNCTION" msg))

                                     (evts/trigger context-invalid-listener single-event)))))


