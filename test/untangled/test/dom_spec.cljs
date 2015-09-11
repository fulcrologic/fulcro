(ns untangled.test.dom-spec
  (:require-macros [cljs.test :refer (is deftest run-tests testing)])
  (:require
    [goog.dom :as gd]
    [quiescent.dom :as d]
    [quiescent.core :include-macros true]
    [untangled.test.assertions :refer [text-matches]]
    [untangled.test.fixtures :as f]
    [untangled.test.dom :refer [node-contains-text?
                                find-element
                                render-as-dom
                                is-rendered-element?]]))

(deftest node-contains-text-spec
  (testing "can do basic non-nested matching"
    (is (node-contains-text? "Hello World" (render-as-dom (d/div {} "Hello World"))))
    (is (node-contains-text? "Hello" (render-as-dom (d/div {} "Hello World"))))
    (is (node-contains-text? "o Wo" (render-as-dom (d/div {} "Hello World")))))
  (testing "can find nested text"
    (is (node-contains-text? "Hello World" (render-as-dom (d/div {} (d/em {} "Hello World")))))
    (is (not (node-contains-text? "xyz" (render-as-dom (d/div {} (d/em {} "Hello World"))))))))


(deftest render-as-dom-spec
  (is (gd/isElement f/custom-button "renders dom for an untangled component"))
  (is (gd/isElement f/sample-doc "renders dom for a vanilla quiescent component")))


(deftest find-element-spec
  (testing "returns nil when nothing is found"
    (is (nil? (find-element f/sample-doc :key "no-such-key")) "by React key")
    (is (nil? (find-element f/sample-doc :class "no-such-class")) "by CSS class")
    (is (nil? (find-element f/sample-doc :no-such-attr "myid")) "by arbitrary attribute value"))
  (testing "finds dom nodes"
    (text-matches "^by-key$" (find-element f/sample-doc :key "myid"))
    (text-matches "^by-classname$" (find-element f/sample-doc :class "test-button"))
    (text-matches "^with-multiple-classes$" (find-element f/sample-doc :class "bar"))
    (text-matches "^Click Me$" (find-element f/sample-doc :button-text "Click Me"))
    (text-matches "^by-attribute$" (find-element f/sample-doc :data-foo "test-foo-data"))))


(deftest is-rendered-element?-spec
  (testing "is-rendered-element?"
    (is (not (is-rendered-element? "<div></div>")) "returns false for strings")
    (is (not (is-rendered-element? (d/div {}))) "returns false for non-rendered components")
    (is (is-rendered-element? f/sample-doc) "returns true for standard components")
    (is (is-rendered-element? f/custom-button) "returns true for custom components")))
