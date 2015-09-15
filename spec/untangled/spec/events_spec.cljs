(ns untangled.spec.events-spec
  (:require-macros [cljs.test :refer (is deftest testing)]
                   [smooth-spec.core :refer (specification behavior provided assertions)]
                   )
  (:require
    [quiescent.core :include-macros true]
    [cljs.test :refer [do-report]]
    [untangled.test.assertions :refer [text-matches]]
    [untangled.test.dom :as td]
    [untangled.spec.fixtures :as f]
    [untangled.test.events :as evt]
    [untangled.component :as c]
    [untangled.core :as core])
  )

(specification "Event Detector for detecting events"
               (let [detector (evt/event-detector)]
                 (behavior "can be used as a function" (is (fn? detector)))
                 (behavior "records that it saw an event"
                           (is (not (-> detector (evt/saw? :my-event))))
                           (detector :my-event)
                           (is (-> detector (evt/saw? :my-event)))
                           )
                 (behavior "records the number of times it has seen an event"
                           (is (= 0 (-> detector (evt/trigger-count :some-event))))
                           (detector :some-event)
                           (detector :some-event)
                           (detector :some-event)
                           (is (= 3 (-> detector (evt/trigger-count :some-event))))
                           )
                 (behavior "can be cleared"
                           (is (= 0 (-> detector (evt/trigger-count :other-event))))
                           (detector :other-event)
                           (is (= 1 (-> detector (evt/trigger-count :other-event))))
                           (evt/clear detector)
                           (is (= 0 (-> detector (evt/trigger-count :other-event))))
                           (is (= 0 (-> detector (evt/trigger-count :some-event))))
                           (is (= 0 (-> detector (evt/trigger-count :my-event))))
                           )
                 (behavior "can act as a test assertion mechanism"
                           ;; unfortunately we cannot mock testing functions, so this isn't a great test
                           (evt/clear detector)
                           (detector :boo)

                           ;; make these wrong to see the failure output...TODO: make do-report mockable
                           (evt/is-seen? detector :boo)
                           (evt/is-trigger-count detector :boo 1)
                           )
                 ))


(specification "The click function"
               (behavior "sends a click event"
                         (let [[seqnc input] (f/evt-tracker-input :type "button" :prop (fn [_] true))]
                           (evt/click input)
                           (is (= [true] @seqnc))) )

               (behavior "allows caller to set event data values"
                         (let [[seqnc input] (f/evt-tracker-input :type "button" :prop (fn [evt] evt))]
                           (evt/click input :clientX 20 :altKey true)
                           (is (= true (.-altKey (first @seqnc))))
                           (is (= 20 (.-clientX (first @seqnc)))))))


(specification "The double-click function"
               (behavior "sends a click event"
                         (let [[seqnc input] (f/evt-tracker-input :type "button" :prop (fn [_] true))]
                           (evt/doubleClick input)
                           (is (= [true] @seqnc))) )

               (behavior "allows caller to set event data values"
                         (let [[seqnc input] (f/evt-tracker-input :type "button" :prop (fn [evt] evt))]
                           (evt/doubleClick input :clientX 20 :altKey true)
                           (is (= true (.-altKey (first @seqnc))))
                           (is (= 20 (.-clientX (first @seqnc)))))))


(specification "The send-keys function"
               (behavior "sends a single keystroke"
                         (let [[seqnc input] (f/evt-tracker-input)]
                           (evt/send-keys input "a")
                           (is (= [97] @seqnc))))

               (behavior "sends a sequence of keystrokes"
                         (let [[seqnc input] (f/evt-tracker-input)]
                           (evt/send-keys input "aA1!")
                           (is (= [97 65 49 33] @seqnc))))

               (behavior "does nothing when an empty string is provided"
                         (let [[seqnc input] (f/evt-tracker-input)]
                           (evt/send-keys input "")
                           (is (= [] @seqnc))))

               (behavior "complains when the first argument is not a DOM element"
                         (is (thrown? js/Error (evt/send-keys {} "a") "- using goog.dom/isElement"))))

