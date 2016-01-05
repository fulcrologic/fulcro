#_(do (require 'midje.sweet) (alter-var-root #'midje.sweet/include-midje-checks (fn [_] false)))

(ns user
  (:require
    #_[midje.sweet]
    [clojure.java.io :as io]
    [clojure.pprint :refer (pprint)]
    [clojure.stacktrace :refer (print-stack-trace)]
    [clojure.tools.namespace.repl :refer [disable-reload! refresh clear]]
    [clojure.repl :refer [doc source]]
    [datomic.api :as d]
    [datomic-helpers :refer [to-transaction to-schema-transaction ext]]
    [untangled-spec.report :as report]
    [clojure.test :refer [run-tests]]
    untangled.components.config-spec
    )
  #_(:use
    midje.repl
    [untangled.datomic-schema.migration :only [dump-schema dump-entity] :rename {
                                                                                 dump-schema s
                                                                                 dump-entity e
                                                                                 }]
    )
  )

#_(defn enable-tests
  "Pass :none to disable all tests, :all to autotest everything, :unit to enable all but
  integration tests, and any other keyword to enable JUST that set of test markers
  (e.g. :focused)
  "
  [opt]
  (alter-var-root #'midje.sweet/include-midje-checks (fn [a] (-> opt (= :none) not)))
  (cond
    (= opt :none) (autotest :stop)
    (= opt :all) (autotest :filter (complement :nothing))
    (= opt :unit) (autotest :filter (complement :integration))
    (keyword? opt) (autotest :filter opt)
    )
  )

(defn run-all-tests []
  (report/with-untangled-output
    (run-tests
      'untangled.components.config-spec)))

