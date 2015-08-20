(ns todo.components.input
  (:require
    [quiescent.dom :as d]
    [todo.events :refer [enter-key? escape-key? text-value]]
    )
  )

(defn text-input [attrs value-to-render enter-key-callback cancel-callback value-setter callback-builder]
  (d/input (merge {:type      "text"
                   :value     value-to-render
                   :onKeyDown (fn [evt] (cond
                                          (enter-key? evt) (enter-key-callback)
                                          (escape-key? evt) (do (cancel-callback)
                                                                (.blur (.-target evt)))
                                          )
                                )
                   :onChange  (fn [evt] ((callback-builder (partial value-setter (text-value evt)))))
                   } attrs))
  )

