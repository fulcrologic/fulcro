(ns ^:figwheel-always todo.core
  (:require
    [untangled.core :as core]
    [todo.components.todo :refer [Todo make-todolist]]
    [todo.components.todo-item :refer [new-item]]
    )
  (:require-macros [untangled.component :as c]))

(enable-console-print!)

(defonce todo (core/new-application Todo
                                    (make-todolist [(new-item "Go to store") (new-item "Eat stuff")])
                                    ))

;(defonce todo-tests (core/new-test-suite 
                                         ;:target "test"
                                         ;:namespaces []
                                         ;))

; Figwheel...on hot code reload, force a re-render
(defn on-js-reload [] (core/force-refresh todo))

(core/render todo)
