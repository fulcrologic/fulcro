(ns todo.components.input
  (:require
    [quiescent.dom :as d]
    [todo.events :refer [enter-key? text-value]]
    )
  )

(defn text-input [value-to-render enter-key-callback value-setter callback-builder]
  (d/input {:type      "text"
            :value     value-to-render
            :onKeyDown (fn [evt] (if (enter-key? evt) (enter-key-callback)))
            :onChange  (fn [evt] ((callback-builder (partial value-setter (text-value evt)))))
            })
  )

