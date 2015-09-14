(ns untangled.test.events-spec
  (:require-macros [cljs.test :refer (is deftest testing)]
                   [smooth-test.core :refer (specification behavior provided assertions)]
                   )
  (:require
    [quiescent.core :include-macros true]
    [cljs.test :refer [do-report]]
    [untangled.test.assertions :refer [text-matches]]
    [untangled.test.dom :as td]
    smooth-test.stub
    [cljs.test :refer [do-report]]
    [untangled.test.events :as evt]
    [untangled.component :as c])
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

(defn evt-tracker-input [& {:keys [type prop]
                            :or   {type "text"
                                   prop (fn [evt] (.-keyCode evt))}}]
  (let [seqnc (atom [])
        input (td/render-as-dom
                (c/input {:type type
                          :onKeyDown  (fn [evt] (swap! seqnc #(conj % (prop evt))))
                          :onKeyPress (fn [evt] (swap! seqnc #(conj % (prop evt))))
                          :onKeyUp    (fn [evt] (swap! seqnc #(conj % (prop evt))))}))]
    [seqnc input]))


; TODO: Update this section with Tony's new code.
;(specification "The click function"
;               (let [root-context (state/root-context (core/new-application nil {:button {:last-event nil}}))
;                     rendered-button (td/render-as-dom (f/Button :button root-context))
;                     get-state (fn [] (:button @(:app-state-atom root-context)))
;                     last-event (fn [] (:last-event (get-state)))]
;
;                 (behavior "sends a click event"
;                           (is (nil? (last-event)))
;                           (evt/click rendered-button)
;                           (is (not (nil? (last-event)))))
;
;                 (behavior "allows caller to set event data values"
;                           (evt/click rendered-button :clientX 20 :altKey true)
;                           (is (= true (.-altKey (last-event))))
;                           (is (= 20 (.-clientX (last-event)))))))


(specification "The double-click function"
               (behavior "is sent to the DOM"
                         (let [[seqnc input] (evt-tracker-input)]
                           (evt/doubleClick input "")
                           (is (= [] @seqnc)))))


(specification "The send-keys function"
               (behavior "sends a single keystroke"
                         (let [[seqnc input] (evt-tracker-input)]
                           (evt/send-keys input "a")
                           (is (= [97] @seqnc))))

               (behavior "sends a sequence of keystrokes"
                         (let [[seqnc input] (evt-tracker-input)]
                           (evt/send-keys input "aA1!")
                           (is (= [97 65 49 33] @seqnc))))

               (behavior "does nothing when an empty string is provided"
                         (let [[seqnc input] (evt-tracker-input)]
                           (evt/send-keys input "")
                           (is (= [] @seqnc))))

               (behavior "complains when the first argument is not a DOM element"
                         (is (thrown? js/Error (evt/send-keys {} "a") "- using goog.dom/isElement"))))

