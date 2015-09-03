(ns untangled.dom-tools.query-spec
  (:require-macros [untangled.component :as c]
                   [cljs.test :refer (is deftest run-tests)])
  (:require [untangled.dom-tools.query :as q]
            [cljs.test :as t]
            [goog.dom :as gd]
            [quiescent.dom :as d]
            [quiescent.core :include-macros true]
            [untangled.state :as state]
            [untangled.dom-tools.event-sim :as ev]
            [untangled.dom-tools.fixtures :as f]
            [untangled.dom-tools.test-utils :as tu]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures


(def dumb-butt (d/button {:className "test-button" :data-foo "test-foo-data" :key "myid"} "derp."))
(def nested-button (d/div {} dumb-butt))
(def dumb-div (d/div {}))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests

;; TODO: add assertions on argument types for get-dom-element and find-element

(deftest get-dom-element
  (let [from-quiescent-component (tu/render-as-dom dumb-butt)
        from-our-component (tu/render-as-dom f/custom-button)]
    (is (gd/isElement from-our-component) "gets the dom node from a custom component")
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


