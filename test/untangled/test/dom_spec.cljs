(ns untangled.test.dom-spec
  (:require-macros [untangled.component :as c]
                   [smooth-test.core :as sm]
                   [cljs.test :refer (is deftest run-tests testing)]
                   [untangled.test.suite :refer [test-suite]])
  (:require
    [cljs.test :refer [do-report]]
    [goog.dom :as gd]
    [quiescent.dom :as d]
    [quiescent.core :include-macros true]
    [untangled.state :as state]
    [untangled.test.events :as ev]
    [untangled.test.fixtures :as f]
    [untangled.test.assertions :refer [text-matches]]
    [untangled.test.dom :refer [node-contains-text? find-element render-as-dom]]
    [untangled.core :as core]

    ))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures






(def dumb-butt (d/button {:className "test-button" :data-foo "test-foo-data" :key "myid"} "derp."))
(def sample-doc (d/div {}
                       (d/div {:key "myid"} "by-key")
                       (d/div {} "by-text-value"
                              (d/span {} "other-text")
                              (d/button {} (d/em {} "Click Me"))
                              )
                       (d/div {:className "test-button"} "by-classname")
                       (d/span {:className "some-span"}
                               (d/h3 {} "h3")
                               (d/section {} "by-selector")
                               (d/h1 {} "h1")
                               )
                       (d/div {:data-foo "test-foo-data"} "by-attribute")
                       (d/div {:className "bartok"} "wrong-multiple-classes")
                       (d/div {:className "foo bar bah"} "with-multiple-classes")
                       ))
(def dumb-div (d/div {}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests

;; TODO: add assertions on argument types for get-dom-element and find-element

(sm/specification
  "testing nested specs"
  (sm/behavior
    "node-contains-text-spec"
    (sm/behavior "can do basic non-nested matching"
      (is (node-contains-text? "Hello World" (render-as-dom (d/div {} "Hello World"))))
      (is (node-contains-text? "Hello" (render-as-dom (d/div {} "Helkslo World"))))
      (is (node-contains-text? "o Wo" (render-as-dom (d/div {} "Hello World"))))
      )
    (sm/behavior "can find nested text"
      (is (node-contains-text? "Hello World" (render-as-dom (d/div {} (d/em {} "Hello World")))))
      (is (not (node-contains-text? "xyz" (render-as-dom (d/div {} (d/em {} "Hello World"))))))
      )
    ))



;(deftest render-as-dom-spec
;  (let [from-quiescent-component (render-as-dom dumb-butt)
;        from-our-component (render-as-dom f/custom-button)]
;    (is (gd/isElement from-our-component) "renders dom for an untangled component")
;    (is (gd/isElement from-quiescent-component) "renders dom for a vanilla quiescent component")))
;
;
;
;(deftest find-element-spec
;  (let [sample-dom (render-as-dom sample-doc)]
;    (testing "returns nil when nothing is found"
;      (is (nil? (find-element :key "no-such-key" sample-dom)) "by React key")
;      (is (nil? (find-element :class "no-such-class" sample-dom)) "by CSS class")
;      (is (nil? (find-element :no-such-attr "myid" sample-dom)) "by arbitrary attribute value"))
;    (testing "finds dom nodes"
;      (text-matches "^by-key$" (find-element :key "myid" sample-dom))
;      (text-matches "^by-classname$" (find-element :class "test-button" sample-dom))
;      (text-matches "^with-multiple-classes$" (find-element :class "bar" sample-dom))
;      (text-matches "^Click Me$" (find-element :button-text "Click Me" sample-dom))
;      (text-matches "^by-attribute$" (find-element :data-foo "test-foo-data" sample-dom))
;      )
;    ))
