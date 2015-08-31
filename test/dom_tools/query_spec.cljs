(ns dom-tools.query-spec
  (:require-macros [quiescent-model.component :as c]
                   [cljs.test :refer (is deftest run-tests)])
  (:require [dom-tools.query :as q]
            [cljs.test :as t]
            [goog.dom :as gd]
            [quiescent.dom :as d]
            [quiescent-model.state :as state]))

; This is for stateful component testing... later...
;(def root-context (state/root (atom {:button {:label "LABEL!"}})))
;(c/defscomponent Button
;                 "A button"
;                 [data context]
;                 (let [op (state/op-builder context)] ; not necessary, because there are no event handlers
;                   (d/button {:className "test-button"
;                              :data-foo  "test-foo-data"} (:label data))))
;
; Call our button with:
;
;(q/dom-frag (Button :button root-context))

(def dumb-butt (d/button {:className "test-button" :data-foo "test-foo-data" :key "myid"} "derp."))
(def nested-button (d/div {} dumb-butt))
(def dumb-div (d/div {}))

(deftest get-dom-element
  (let [dom-frag (q/get-dom-element dumb-butt)]
    (is (gd/isElement dom-frag) "gets the dom node from a component")))

(deftest find-element
  (let [component (js/React.addons.TestUtils.renderIntoDocument nested-button)
        parent-elem (.getDOMNode component)
        wrong-key (q/find-element :key "cyid" parent-elem)
        child-elem-by-key (or (q/find-element :key "myid" parent-elem) dumb-div)
        no-such-value (q/find-element :class "active" parent-elem)
        no-such-attr (q/find-element :no-such-attr "myid" parent-elem)
        child-elem-by-selector (or (q/find-element :selector "div .test-button" parent-elem) dumb-div)
        child-elem-by-text (or (q/find-element :text "der" parent-elem) dumb-div)
        child-elem-by-attr (or (q/find-element :class "test-button" parent-elem) dumb-div)]
    (is (nil? wrong-key) "find-element returns nil when looking for the wrong key")
    (is (nil? no-such-value) "find-element returns nil when a valid value is not found on an existing attribute")
    (is (nil? no-such-attr) "find-element returns nil when an attribute is not found")
    (is (= "derp." (.-innerHTML child-elem-by-key)) "find-element by data-reactid key")
    (is (= "derp." (.-innerHTML child-elem-by-text)) "find-element by visible inner text")
    (is (= "derp." (.-innerHTML child-elem-by-selector)) "find-element by arbitrary CSS selector")
    (is (= "derp." (.-innerHTML child-elem-by-attr)) "find-element by attr and value")))


