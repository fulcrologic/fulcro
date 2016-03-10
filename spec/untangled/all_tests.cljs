(ns untangled.all-tests
  (:require untangled.tests-to-run [doo.runner :refer-macros [doo-all-tests]]))

(doo-all-tests #".*-spec")
