(ns ^:figwheel-always todo.core
  (:require
    [untangled.core :as core]
    [untangled.application :as app]
    [todo.components.todo :refer [Todo make-todolist]]
    [todo.components.todo-item :refer [new-item]]
    )
  (:require-macros [untangled.component :as c]))

(enable-console-print!)

(defonce todo (core/new-application #(Todo %1 %2)
                                    (make-todolist [(new-item "Go to store") (new-item "Eat stuff")])
                                    ))

; Figwheel...on hot code reload, force a re-render
(defn on-js-reload [] 
  (app/force-refresh todo)
  )

(app/render todo)
