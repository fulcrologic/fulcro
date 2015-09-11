(ns untangled.test.dom-spec
  (:require-macros [cljs.test :refer (is run-tests testing deftest)]
                   [smooth-test.core :refer (specification behavior provided assertions)])

  (:require
    [goog.dom :as gd]
    [quiescent.dom :as d]
    [quiescent.core :include-macros true]
    [untangled.test.assertions :refer [text-matches]]
    [untangled.test.fixtures :as f]
    [cljs.test :refer [do-report]]
    [untangled.test.dom :refer [node-contains-text?
                                find-element
                                render-as-dom
                                is-rendered-element?]]))

(specification "The node-contains-text function"
               (behavior "can do basic non-nested matching"
                         (assertions
                           (node-contains-text? "Hello World" (render-as-dom (d/div {} "Hello World"))) => true
                           (node-contains-text? "Hello" (render-as-dom (d/div {} "Hello World"))) => true
                           (node-contains-text? "o Wo" (render-as-dom (d/div {} "Hello World"))) => true))
               (behavior "can find nested text"
                         (assertions
                           (node-contains-text? "Hello World" (render-as-dom (d/div {} (d/em {} "Hello World")))) => true
                           (node-contains-text? "xyz" (render-as-dom (d/div {} (d/em {} "Hello World")))) => false)))


(specification "The render-as-dom function"
               (behavior "renders dom for an untangled component"
                         (is (gd/isElement f/custom-button)))
               (behavior "renders dom for a vanilla quiescent component"
                         (is (gd/isElement f/sample-doc))))


(specification "The find-element function"
               (behavior "returns nil when nothing is found"
                         (behavior "by React key" (is (nil? (find-element f/sample-doc :key "no-such-key"))))
                         (behavior "by CSS class" (is (nil? (find-element f/sample-doc :class "no-such-class"))))
                         (behavior "by arbitrary attribute value" (is (nil? (find-element f/sample-doc :no-such-attr "myid")))))
               (behavior "finds dom nodes"
                         (text-matches "^by-key$" (find-element f/sample-doc :key "myid"))
                         (text-matches "^by-classname$" (find-element f/sample-doc :class "test-button"))
                         (text-matches "^with-multiple-classes$" (find-element f/sample-doc :class "bar"))
                         (text-matches "^Click Me$" (find-element f/sample-doc :button-text "Click Me"))
                         (text-matches "^by-attribute$" (find-element f/sample-doc :data-foo "test-foo-data"))))


(specification "The is-rendered-element? function"
               (behavior "returns false for strings"
                         (assertions
                           (is-rendered-element? "<div></div>") => false))
               (behavior "returns false for non-rendered components"
                         (is (not (is-rendered-element? (d/div {})))))

               (behavior "returns true for standard components"
                         (is (is-rendered-element? f/sample-doc)))
               (behavior "returns true for custom components"
                         (is (is-rendered-element? f/custom-button))))

