(ns user
  (:require
    [clojure.java.io :as io]
    [clojure.pprint :refer (pprint)]
    [clojure.stacktrace :refer (print-stack-trace)]
    [clojure.tools.namespace.repl :refer [disable-reload! refresh clear]]
    [clojure.repl :refer [doc source]]
    [clojure.test :refer [run-tests]]
    untangled.server.impl.components.config-spec
    ))

#_(defn run-all-tests []
  (report/with-untangled-output
    (run-tests
      'untangled.server.impl.components.config-spec)))
