(ns untangled.test.events-spec
  (:require-macros [cljs.test :refer (is run-tests testing deftest)]
                   [smooth-test.core :refer (specification behavior provided assertions)]
                   cljs.user)
  (:require
    [quiescent.dom :as d]
    [quiescent.core :include-macros true]
    [untangled.state :as state]
    [cljs.test :refer [do-report]]
    [untangled.test.assertions :refer [text-matches]]
    [untangled.test.dom :as td]
    [untangled.test.events :as ev]
    [untangled.test.fixtures :as f]
    [untangled.core :as core]))


(defn evt-tracker-input [& {:keys [type prop]
                            :or   {type "text"
                                   prop (fn [evt] (.-keyCode evt))}}]
  (let [seqnc (atom [])
        input (td/render-as-dom
                (d/input {:type type
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
;                           (ev/click rendered-button)
;                           (is (not (nil? (last-event)))))
;
;                 (behavior "allows caller to set event data values"
;                           (ev/click rendered-button :clientX 20 :altKey true)
;                           (is (= true (.-altKey (last-event))))
;                           (is (= 20 (.-clientX (last-event)))))))


(specification "The double-click function"
               (behavior "is sent to the DOM"
                         (let [[seqnc input] (evt-tracker-input)]
                           (ev/doubleClick input "")
                           (is (= [] @seqnc)))))


(specification "The send-keys function"
               (behavior "sends a single keystroke"
                         (let [[seqnc input] (evt-tracker-input)]
                           (ev/send-keys input "a")
                           (is (= [97] @seqnc))))

               (behavior "sends a sequence of keystrokes"
                         (let [[seqnc input] (evt-tracker-input)]
                           (ev/send-keys input "aA1!")
                           (is (= [97 65 49 33] @seqnc))))

               (behavior "does nothing when an empty string is provided"
                         (let [[seqnc input] (evt-tracker-input)]
                           (ev/send-keys input "")
                           (is (= [] @seqnc))))

               (behavior "complains when the first argument is not a DOM element"
                         (is (thrown? js/Error (ev/send-keys {} "a") "- using goog.dom/isElement"))))

