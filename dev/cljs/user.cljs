(ns ^:figwheel-always cljs.user
  (:require cljs.core
            untangled.tests-to-run
            [untangled-spec.reporters.suite :refer-macros [deftest-all-suite]]
            [cljs.test :as test :include-macros true :refer [report]]))

(deftest-all-suite spec-report #"untangled.*-spec")

(def on-load spec-report)

(spec-report)
