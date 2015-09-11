(ns untangled.test.fixtures
  (:require-macros [untangled.component :as c])
  (:require
    [quiescent.dom :as d]
    [quiescent.core :include-macros true]
    [untangled.state :as state]
    [untangled.test.dom :refer [render-as-dom]]))


(def sample-doc
  (render-as-dom
    (d/div {}
           (d/div {:key "myid"} "by-key")
           (d/div {} "by-text-value"
                  (d/span {} "other-text")
                  (d/button {} (d/em {} "Click Me")))
           (d/div {:className "test-button"} "by-classname")
           (d/span {:className "some-span"}
                   (d/h3 {} "h3")
                   (d/section {} "by-selector")
                   (d/h1 {} "h1"))
           (d/div {:data-foo "test-foo-data"} "by-attribute")
           (d/div {:className "bartok"} "wrong-multiple-classes")
           (d/div {:className "foo bar bah"} "with-multiple-classes"))))


(c/defscomponent Button
                 "A button"
                 [data context]

                 (let [op (state/op-builder context)
                       store-last-event (fn [evt input]
                                          (assoc input :last-event evt))]

                   (d/button {:onClick    (fn [evt] ((op (partial store-last-event (clj->js evt)))))
                              :className  "test-button"
                              :last-event (:last-event data)})))

(def root-obj (state/root-scope (atom {:my-button {}})))
(def custom-button (render-as-dom (Button :my-button root-obj)))