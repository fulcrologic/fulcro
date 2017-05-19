(ns untangled.automated-test-main
  (:require untangled.tests-to-run [doo.runner :refer-macros [doo-all-tests]]))

(doo-all-tests #".*-spec")
