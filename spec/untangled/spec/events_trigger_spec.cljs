(ns untangled.spec.events-trigger-spec
  (:require-macros [cljs.test :refer (is deftest testing)]
                   [smooth-spec.core :refer (specification behavior provided assertions)]
                   )
  (:require [untangled.events :as evts]
            smooth-spec.stub
            [cljs.test :refer [do-report]]
            [untangled.test.events :as evt]
            [untangled.logging :as logging])
  )

(specification "An event trigger -- 'trigger' function"
               (let [detector (evt/event-detector)
                     detector2 (evt/event-detector)
                     context-single-listener {:event-listeners [{:a detector
                                                                 :b detector
                                                                 :c detector}]}
                     context-multi-listeners {:event-listeners (conj (:event-listeners context-single-listener)
                                                                     {:a detector2
                                                                      :b detector2})}
                     context-invalid-listener {:event-listeners [{:a 5}]}
                     single-event :a
                     multi-events [:a :b :c]
                     ]
                 (behavior "includes the event name when it:"
                           (behavior "accepts a single event"
                                     (evt/clear detector)

                                     (evts/trigger context-single-listener single-event)

                                     (is (-> detector (evt/saw? :a))))
                           (behavior "accepts a list of events"
                                     (evt/clear detector)
                                     
                                     (evts/trigger context-single-listener multi-events)

                                     (is (every? #(evt/saw? detector %) [:a :b :c]))
                                     )
                           (behavior "invokes each event function that is keyed to that event"
                                     (evt/clear detector)
                                     (evt/clear detector2)
                                     (evts/trigger context-multi-listeners single-event)

                                     (is (-> detector (evt/saw? :a)))
                                     (is (-> detector2 (evt/saw? :a)))
                                     ))
                 (behavior "works if no event handler is present for the given event"
                           (evt/clear detector)
                           (evt/clear detector2)
                           (evts/trigger context-multi-listeners multi-events)

                           (is (-> detector (evt/saw? :c)))
                           (is (not (-> detector2 (evt/saw? :c))))

                           )
                 (behavior "logs an error for debugging"
                           (provided "if handler is not a function"
                                     (logging/log msg) =1x=> (is (= "ERROR: TRIGGERED EVENT HANDLER MUST BE A FUNCTION" msg))

                                     (evts/trigger context-invalid-listener single-event)))))


