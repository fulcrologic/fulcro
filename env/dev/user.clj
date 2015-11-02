(ns user
  (:require
    [clojure.java.io :as io]
    [clojure.pprint :refer (pprint)]
    [clojure.stacktrace :refer (print-stack-trace)]
    [clojure.tools.namespace.repl :refer [disable-reload! refresh clear]]
    [clojure.repl :refer [doc source]]
    [datomic.api :as d]
    [datomic-helpers :refer [to-transaction to-schema-transaction ext]]
    )
  (:use
    [datomic-schema.migration :only [dump-schema dump-entity] :rename {
                                                                     dump-schema s
                                                                     dump-entity e
                                                                     }]
    )
  )
