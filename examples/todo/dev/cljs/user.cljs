(ns ^:figwheel-always cljs.user
  (:require todo.core
            [untangled.repl :refer [fpp focus-in focus-out vdiff auto-trigger! evolution]]
            )
  )


(untangled.repl/follow-application! todo.core/todo)

