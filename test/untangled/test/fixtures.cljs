(ns untangled.test.fixtures
  (:require-macros [untangled.component :as c])
  (:require
    [quiescent.dom :as d]
    [quiescent.core :include-macros true]
    [untangled.state :as state]))

(c/defscomponent Button
                 "A button"
                 [data context]

                 (let [op (state/op-builder context)
                       store-last-event (fn [evt input]
                                          (assoc input :last-event evt))]

                   (d/button {:onClick    (fn [evt] ((op (partial store-last-event (clj->js evt)))))
                              :className  "test-button"
                              :last-event (:last-event data)})))
(def my-button-context (state/root-context (atom {:top  {:my-button {:data-count 0}} :time (js/Date.)})))
(def custom-button (Button :my-button my-button-context))
