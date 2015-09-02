(ns dom-tools.fixtures
  (:require-macros [quiescent-model.component :as c])
  (:require
    [quiescent.dom :as d]
    [quiescent.core :include-macros true]
    [quiescent-model.state :as state]))

(c/defscomponent Button
                 "A button"
                 [data context]

                 (let [op (state/op-builder context)
                       store-last-event (fn [evt data] (assoc data :last-event evt))
                       ]
                   (d/button {:onClick    (fn [evt data] ((op (partial store-last-event (clj->js [evt data])))))
                              :className  "test-button"
                              :last-event (:last-event data)})))


;:onChange (fn [evt] ((op (partial set-and-validate evt))))



(def my-button-context (state/root-scope (atom {:my-button {:data-count 0}})))
(def custom-button (Button :my-button my-button-context))
