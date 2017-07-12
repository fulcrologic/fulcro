(ns fulcro.automated-test-main
  (:require fulcro.tests-to-run [doo.runner :refer-macros [doo-all-tests]]))

(doo-all-tests #".*-spec")
