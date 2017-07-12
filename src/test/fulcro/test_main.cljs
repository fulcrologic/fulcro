(ns ^:figwheel-always fulcro.test-main
  (:require
    fulcro.tests-to-run
    [fulcro-spec.selectors :as sel]
    [fulcro-spec.suite :refer [def-test-suite]]))

(def-test-suite spec-report {:ns-regex #"fulcro.*-spec"}
  {:default   #{::sel/none :focused}
   :available #{:focused}})
