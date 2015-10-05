(ns clj.user
  (:require [clojure.test :refer (is deftest run-tests testing do-report)]
            [smooth-spec.report :as report]
            leiningen.i18n-spec
            leiningen.i18n.code-gen-spec
            leiningen.i18n.util-spec
            leiningen.i18n.parse-po-spec))

(defn run-all-tests []
  (report/with-smooth-output (run-tests 'leiningen.i18n-spec))
  (report/with-smooth-output (run-tests 'leiningen.i18n.util-spec))
  (report/with-smooth-output (run-tests 'leiningen.i18n.code-gen-spec))
  (report/with-smooth-output (run-tests 'leiningen.i18n.parse-po-spec))
  )

(run-all-tests)