; A CLJ script for running figwheel in an IntelliJ REPL
; Run this using clojure.main from a JVM launch 
; See https://github.com/bhauman/lein-figwheel/wiki/Running-figwheel-in-a-Cursive-Clojure-REPL

(use 'figwheel-sidecar.repl-api)
(start-figwheel! {:all-builds (figwheel-sidecar.repl/get-project-cljs-builds)})
(cljs-repl)
