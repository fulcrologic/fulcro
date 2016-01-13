(ns ^:figwheel-always cljs.user
  ;(:require-macros [cljs.test :refer (is deftest run-tests testing)])
  (:require cljs.core
            untangled.i18n-spec
            untangled.services.local-storage-io-spec
            untangled-spec.async
            untangled-spec.stub
            untangled.tests-to-run
            [untangled-spec.reporters.suite :refer-macros [deftest-all-suite]]
            [cljs.test :as test :include-macros true :refer [report]]))

(deftest-all-suite spec-report #"untangled.*-spec")

(def on-load spec-report)

(spec-report)
