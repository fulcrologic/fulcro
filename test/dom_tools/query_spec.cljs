(ns dom-tools.query-spec
  (:require-macros [quiescent-model.component :as c]
                   [cljs.test :refer (is deftest run-tests)])
  (:require [dom-tools.query :as q]
            [cljs.test :as t]
            [goog.dom :as gd]
            [quiescent.dom :as d]
            [quiescent.core :include-macros true]
            [quiescent-model.state :as state]
            [dom-tools.test-utils :as tu]))

(def dumb-butt (d/button {:className "test-button" :data-foo "test-foo-data" :key "myid"} "derp."))
(def nested-button (d/div {} dumb-butt))
(def dumb-div (d/div {}))

(def root-context (state/root-scope (atom {:button
                                           {:data-count 0}})))

(c/defscomponent Button
                 "A button"
                 [data context]

                 (let [op (state/op-builder context)
                       plus-one (op (fn [data] (assoc data :data-count (inc (:data-count data)))))]
                   (d/button {:onClick    plus-one
                              :className  "test-button"
                              :data-count (:data-count data)})))

(deftest get-dom-element
  (let [from-quiescent-component (q/get-dom-element dumb-butt)
        from-qmodel-component (q/get-dom-element (Button :button root-context))]
    (is (gd/isElement from-qmodel-component) "gets the dom node from a quiescent-model component")
    (is (gd/isElement from-quiescent-component) "gets the dom node from a vanilla quiescent component")))

(deftest find-element
  (let [rendered-component (js/React.addons.TestUtils.renderIntoDocument nested-button)
        parent-elem (.getDOMNode rendered-component)
        wrong-key (q/find-element :key "cyid" parent-elem)
        child-elem-by-key (or (q/find-element :key "myid" parent-elem) dumb-div)
        no-such-value (q/find-element :class "active" parent-elem)
        no-such-attr (q/find-element :no-such-attr "myid" parent-elem)
        child-elem-by-selector (or (q/find-element :selector "div .test-button" parent-elem) dumb-div)
        child-elem-from-component (or (q/find-element :text "der" nested-button) dumb-div)
        child-elem-by-text (or (q/find-element :text "der" parent-elem) dumb-div)
        child-elem-by-attr (or (q/find-element :class "test-button" parent-elem) dumb-div)]
    (is (nil? wrong-key) "find-element returns nil when looking for the wrong key")
    (is (nil? no-such-value) "find-element returns nil when a valid value is not found on an existing attribute")
    (is (nil? no-such-attr) "find-element returns nil when an attribute is not found")
    (is (= "derp." (.-innerHTML child-elem-by-key)) "find-element by data-reactid key")
    (is (= "derp." (.-innerHTML child-elem-from-component)) "find-element when given a React component")
    (is (= "derp." (.-innerHTML child-elem-by-text)) "find-element by visible inner text")
    (is (= "derp." (.-innerHTML child-elem-by-selector)) "find-element by arbitrary CSS selector")
    (is (= "derp." (.-innerHTML child-elem-by-attr)) "find-element by attr and value")))

(deftest clickly (let [root-context (state/root-scope (atom {:button {:data-count 0}}))
                       button (q/find-element :class "test-button" (d/div {} (Button :button root-context)))
                       button-click (tu/click button)
                       click-count (:data-count (:button @(:app-state-atom root-context)))]
                   (is (= 1 click-count))))

; Call our button with:
;
;(q/dom-frag (Button :button root-context))
