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

(def dumb-butt (d/button {:className "test-button" :data-foo "test-foo-data"} "derp."))
(def nested-button (d/div {} dumb-butt))

(deftest get-dom-element-gets-dom-node-from-component
  (let [dom-frag (q/get-dom-element dumb-butt)]
    (is (gd/isElement dom-frag))))

(deftest find-element-gets-child-element-when-given-attr-and-value-and-parent-node
  (let [component (js/React.addons.TestUtils.renderIntoDocument nested-button)
        parent-elem (.getDOMNode component)
        child-elem (q/find-element :class "test-button" parent-elem)]
    (is (= "derp." (.-innerHTML child-elem)))))
