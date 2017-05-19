(ns ^:figwheel-always untangled.test-main
  (:require
    untangled.tests-to-run
    [untangled-spec.selectors :as sel]
    [untangled-spec.suite :refer [def-test-suite]]))

(def-test-suite spec-report {:ns-regex #"untangled.*-spec"}
  {:default   #{::sel/none :focused}
   :available #{:focused}})
