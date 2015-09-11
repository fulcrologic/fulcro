(ns untangled.test.events-spec
  (:require-macros [cljs.test :refer (is deftest run-tests testing)]
                   cljs.user)
  (:require
    [quiescent.dom :as d]
    [quiescent.core :include-macros true]
    [untangled.state :as state]
    [untangled.test.dom :as td]
    [untangled.test.events :as ev]
    [untangled.test.fixtures :as f] ))


(deftest clicks
  (let [root-context (state/root-scope (atom {:button {:last-event nil}}))
        rendered-button (td/render-as-dom (f/Button :button root-context))
        get-state (fn [] (:button @(:app-state-atom root-context)))
        last-event (fn [] (:last-event (get-state)))]

    (testing "sends a click event"
      (is (nil? (last-event)))
      (ev/click rendered-button)
      (is (not (nil? (last-event)))))

    (testing "allows caller to set event data values"
      (ev/click rendered-button :clientX 20 :altKey true)
      (is (= true (.-altKey (last-event))))
      (is (= 20 (.-clientX (last-event)))))))


(defn setup-input []
  (let [seqnc (atom [])
        input (td/render-as-dom
                (d/input {:onKeyDown  (fn [evt] (swap! seqnc #(conj % (.-keyCode evt))))
                          :onKeyPress (fn [evt] (swap! seqnc #(conj % (.-keyCode evt))))
                          :onKeyUp    (fn [evt] (swap! seqnc #(conj % (.-keyCode evt))))}))]
    [seqnc input]))


(deftest keys-sent
  (testing "send-keys"

    (testing "sends a single keystroke"
      (let [[seqnc input] (setup-input)]
        (ev/send-keys input "a")
        (is (= [97] @seqnc))))

    (testing "sends a sequence of keystrokes"
      (let [[seqnc input] (setup-input)]
        (ev/send-keys input "aA1!")
        (is (= [97 65 49 33] @seqnc))))

    (testing "does nothing when an empty string is provided"
      (let [[seqnc input] (setup-input)]
        (ev/send-keys input "")
        (is (= [] @seqnc))))

    (testing "complains when the first argument is not a DOM element"
        (is (thrown? js/Error (ev/send-keys {} "a") "- using goog.dom/isElement")))))

