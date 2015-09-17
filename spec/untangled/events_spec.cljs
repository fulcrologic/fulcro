(ns untangled.events-spec
  (:require-macros [cljs.test :refer (is deftest testing)]
                   [smooth-spec.core :refer (specification behavior provided assertions)]
                   )
  (:require [untangled.events :as evts]
            smooth-spec.stub
            [cljs.test :refer [do-report]]
            [untangled.test.events :as evt]
            [untangled.logging :as logging]
            [untangled.state :as state]
            [untangled.core :as core])
  )

(specification "An event trigger -- 'trigger' function"
               (let [detector (evt/event-detector)
                     context-single-listener {:event-listeners [{:a detector
                                                                 :b detector
                                                                 :c detector}]}
                     context-invalid-listener {:event-listeners [{:a 5}]}
                     single-event :a
                     ]
                 (behavior "includes the event name when it:"
                           (behavior "accepts a single event"
                                     (evt/clear detector)

                                     (evts/trigger context-single-listener single-event)

                                     (is (-> detector (evt/saw? :a))))
                           (behavior "accepts a list of events"
                                     (evt/clear detector)

                                     (evts/trigger context-single-listener [:a :b :c])

                                     (is (every? #(evt/saw? detector %) [:a :b :c]))
                                     ))
                 (behavior "works if no event handler is present for the given event"
                           (evt/clear detector)
                           
                           (evts/trigger context-single-listener :d)

                           (is (not (-> detector (evt/saw? :d))))

                           )
                 (behavior "logs an error for debugging"
                           (provided "if handler is not a function"
                                     (logging/log msg) =1x=> (is (= "ERROR: TRIGGERED EVENT HANDLER MUST BE A FUNCTION" msg))

                                     (evts/trigger context-invalid-listener single-event))))
               (let [state {:a {:form/locale "en-US"
                                :b           [{:k 2 :v {:boo 22}} {:k 1}]}}
                     the-application (core/new-application nil state) ; NOTE: adds a :top key to the state
                     context-with-sublist-path (assoc (state/root-context the-application) :scope [:top :a [:b :k 1]])
                     detector (evt/event-detector)
                     leaf-detector (evt/event-detector)
                     root (state/root-context the-application)
                     top (state/new-sub-context root :top [])
                     context1 (state/new-sub-context top :a [{ :evt/boo detector }])
                     context2 (state/new-sub-context context1 [:b :k 2] [{ :evt/boo detector }])
                     context3 (state/new-sub-context context2 :v [{ :evt/boo leaf-detector }])
                     ]
                 (behavior "sends the event to the first parent only by default"
                           (evt/clear leaf-detector)
                           
                           (evts/trigger context3 :evt/boo)

                           (is (= 1 (evt/trigger-count leaf-detector :evt/boo)))
                           )
                 (behavior "propagates the event to interested parents if requested"
                           (evt/clear leaf-detector)
                           (evt/clear detector)

                           (evts/trigger context3 :evt/boo :bubble true)

                           (is (= 1 (evt/trigger-count leaf-detector :evt/boo)))
                           (is (= 2 (evt/trigger-count detector :evt/boo)))
                           )))

