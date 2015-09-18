(ns clj.user
  (:require [clojure.test :refer (is deftest run-tests testing do-report)]
            [smooth-spec.report :as report]
            untangled.spec.i18n.util-spec))

(defn run-all-tests [] (report/with-smooth-output (run-tests 'untangled.spec.i18n.util-spec)))