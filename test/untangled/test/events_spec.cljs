(ns untangled.test.events-spec
  (:require-macros [cljs.test :refer (is deftest run-tests testing)]
                   cljs.user)
  (:require
    [cljs.test :as t]
    [goog.dom :as gd]
    [quiescent.dom :as d]
    [quiescent.core :include-macros true]
    [untangled.state :as state]
    [untangled.test.dom :as td]
    [untangled.test.events :as ev]
    [untangled.test.fixtures :as f]
    ))


;; React events ref: https://facebook.github.io/react/docs/events.html


(deftest clicks (let [root-context (state/root-scope (atom {:button {:last-event nil}}))
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
        input (td/as-dom (d/input {:onKeyDown  (fn [evt] (swap! seqnc #(conj % (.-keyCode evt))))
                                   :onKeyPress (fn [evt] (swap! seqnc #(conj % (.-keyCode evt))))
                                   :onKeyUp    (fn [evt] (swap! seqnc #(conj % (.-keyCode evt))))}))]
    [seqnc input]))

(deftest key-sent
  (testing "send-key"
    (let [[seqnc input] (setup-input)]
      (ev/send-key input "a")
      (is (= [97] @seqnc)))))

(deftest keys-sent
  (testing "send-keys"
    (let [[seqnc input] (setup-input)]
      (ev/send-keys input "aA1!")
      (is (= [97 65 49 33] @seqnc)))))

;(def root-context (state/root-scope (atom {:button
;                                           {:data-count 0}})))
;
;(c/defscomponent Button
;                 "A button"
;                 [data context]
;
;                 (let [op (state/op-builder context)
;                       plus-one (op #(inc %))]
;                   (d/button {:onClick    (plus-one (:data-count data))

;                              :className  "test-button"
;                              :data-count (:data-count data)})))
;
;(cljs.pprint/pprint "a random string")
;
;
;(tu/click (q/find-element :class "test-button" (Button :button root-context)))

; Call our button with:
;
;(q/dom-frag (Button :button root-context))


















;(spec "blag"
;      (provided "async stuff does blah"
;                (js/setTimeout (capture f) 200) => (async 200 (f [1 2 3]))
;                (ajax/get (capture url) (capture gc) anything) => (async 150 (gc [4 5 6]))
;
;        (let [state (my-root)                               ; set to 6/11/2014
;              rendering (fn [] (Calendar :cal state))
;              ]
;          (behavior ""
;                    (click-button "Next Month" (rendering)) => anything
;                    (-> @state :cal :month) => 7
;                    (rendering) => (contains-string ".label" "7/1/2014")
;                    clock-ticks => 201
;                    (-> @state :cal :data) => [1 2 3]
;                    clock-ticks => 150
;                    (-> @state :cal :ajax-data) => [4 5 6]
;
;                    )
;
;          )))
