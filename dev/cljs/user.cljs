(ns ^:figwheel-always cljs.user
  (:require
    cljs.core
    [clojure.pprint :refer [pprint]]
    [clojure.repl :refer [doc source]]
    [clojure.test :as test :include-macros true :refer [report]]
    [clojure.spec :as s]
    [devtools.core :as devtools]
    untangled.tests-to-run
    [untangled-spec.reporters.suite :refer-macros [deftest-all-suite]]))

(devtools/enable-feature! :sanity-hints)
(devtools/install!)

(deftest-all-suite spec-report #"untangled.*-spec")

(def on-load spec-report)

(spec-report)
