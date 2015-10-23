(ns untangled.test.dom-spec
  (:require-macros [smooth-spec.core :refer (specification behavior provided assertions)]
                   [cljs.test :refer (is deftest run-tests testing)]
                   [untangled.test.suite :refer [test-suite]])
  (:require
    [cljs.test :refer [do-report]]
    [goog.dom :as gd]
    [untangled.test.assertions :refer [text-matches]]
    [untangled.test.fixtures :as f]
    [cljs.test :refer [do-report]]
    [untangled.test.dom :refer [node-contains-text?
                                find-element
                                render-as-dom
                                is-rendered-element?]]
    ))

(comment
  (specification "The node-contains-text function"
                 (behavior "can do basic non-nested matching"
                           (assertions
                             (node-contains-text? "Hello World" (render-as-dom (c/div {} "Hello World"))) => true
                             (node-contains-text? "Hello" (render-as-dom (c/div {} "Hello World"))) => true
                             (node-contains-text? "o Wo" (render-as-dom (c/div {} "Hello World"))) => true))
                 (behavior "can find nested text"
                           (assertions
                             (node-contains-text? "Hello World" (render-as-dom (c/div {} (c/em {} "Hello World")))) => true
                             (node-contains-text? "xyz" (render-as-dom (c/div {} (c/em {} "Hello World")))) => false)))


  (specification "The render-as-dom function"
                 (behavior "renders dom for an untangled component"
                           (is (gd/isElement f/custom-button)))
                 (behavior "renders dom for a vanilla quiescent component"
                           (is (gd/isElement f/sample-doc))))


  (specification "The find-element function"
                 (behavior "returns nil when nothing is found"
                           (behavior "by React key" (is (nil? (find-element :key "no-such-key" f/sample-doc))))
                           (behavior "by CSS class" (is (nil? (find-element :class "no-such-class" f/sample-doc))))
                           (behavior "by arbitrary attribute value" (is (nil? (find-element :no-such-attr "myid" f/sample-doc)))))
                 (behavior "finds dom nodes"
                           (text-matches "^by-key$" (find-element :key "myid" f/sample-doc))
                           (text-matches "^by-classname$" (find-element :class "test-button" f/sample-doc))
                           (text-matches "^with-multiple-classes$" (find-element :class "bar" f/sample-doc))
                           (text-matches "^Click Me$" (find-element :button-text "Click Me" f/sample-doc))
                           (text-matches "^by-attribute$" (find-element :data-foo "test-foo-data" f/sample-doc))))


  (specification "The is-rendered-element? function"
                 (behavior "returns false for strings"
                           (assertions
                             (is-rendered-element? "<div></div>") => false))
                 (behavior "returns false for non-rendered components"
                           (is (not (is-rendered-element? (c/div {})))))
                 (behavior "returns true for standard components"
                           (is (is-rendered-element? f/sample-doc)))
                 (behavior "returns true for custom components"
                           (is (is-rendered-element? f/custom-button)))))

