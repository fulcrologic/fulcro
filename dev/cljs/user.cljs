(ns ^:figwheel-always cljs.user
  (:require cljs.core
            untangled.tests-to-run
            [untangled-spec.reporters.suite :refer-macros [deftest-all-suite]]
            [devtools.core :as devtools]
            [cljs.test :as test :include-macros true :refer [report]]))

(devtools/enable-feature! :sanity-hints)
(devtools/install!)

(deftest-all-suite spec-report #"untangled.*-spec")

(def on-load spec-report)

(spec-report)
