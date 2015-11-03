(ns untangled.test.dom-spec
  (:require-macros [smooth-spec.core :refer (specification behavior provided assertions)]
                   [cljs.test :refer (is deftest run-tests testing)]
                   [untangled.test.suite :refer [test-suite]])
  (:require
    [cljs.test :refer [do-report]]
    [goog.dom :as gd]
    [om.dom :as dom]
    [untangled.test.assertions :refer [text-matches]]
    [cljs.test :refer [do-report]]
    [untangled.test.dom :refer [node-contains-text?
                                find-element
                                render-as-dom
                                is-rendered-element?]]
    ))

(def sample-doc
  (render-as-dom
    (dom/div nil
             (dom/div #js {:key "myid"} "by-key")
             (dom/div nil "by-text-value"
                      (dom/span nil "other-text")
                      (dom/button nil (dom/em nil "Click Me")))
             (dom/div #js {:className "test-button"} "by-classname")
             (dom/span #js {:className "some-span"}
                       (dom/h3 nil "h3")
                       (dom/section nil "by-selector")
                       (dom/h1 nil "h1"))
             (dom/div #js {:data-foo "test-foo-data"} "by-attribute")
             (dom/div #js {:className "bartok"} "wrong-multiple-classes")
             (dom/div #js {:className "foo bar bah"} "with-multiple-classes"))))

(specification "The node-contains-text function"
               (behavior "can do basic non-nested matching"
                         (assertions
                           (node-contains-text? "Hello World" (render-as-dom (dom/div {} "Hello World"))) => true
                           (node-contains-text? "Hello" (render-as-dom (dom/div {} "Hello World"))) => true
                           (node-contains-text? "o Wo" (render-as-dom (dom/div {} "Hello World"))) => true))
               (behavior "can find nested text"
                         (assertions
                           (node-contains-text? "Hello World" (render-as-dom (dom/div {} (dom/em {} "Hello World")))) => true
                           (node-contains-text? "xyz" (render-as-dom (dom/div {} (dom/em {} "Hello World")))) => false)))

(specification "The find-element function"
               (behavior "returns nil when nothing is found"
                         (behavior "by React key" (is (nil? (find-element :key "no-such-key" sample-doc))))
                         (behavior "by CSS class" (is (nil? (find-element :class "no-such-class" sample-doc))))
                         (behavior "by arbitrary attribute value" (is (nil? (find-element :no-such-attr "myid" sample-doc)))))
               (behavior "finds dom nodes"
                         (text-matches "^by-key$" (find-element :key "myid" sample-doc))
                         (text-matches "^by-classname$" (find-element :class "test-button" sample-doc))
                         (text-matches "^with-multiple-classes$" (find-element :class "bar" sample-doc))
                         (text-matches "^Click Me$" (find-element :button-text "Click Me" sample-doc))
                         (text-matches "^by-attribute$" (find-element :data-foo "test-foo-data" sample-doc))))


(specification "The is-rendered-element? function"
               (behavior "returns false for strings"
                         (assertions
                           (is-rendered-element? "<div></div>") => false))
               (behavior "returns false for non-rendered components"
                         (is (not (is-rendered-element? (dom/div {})))))
               (behavior "returns true for standard components"
                         (is (is-rendered-element? sample-doc)))
               )

