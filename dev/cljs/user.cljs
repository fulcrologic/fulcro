(ns ^:figwheel-always cljs.user
  (:require-macros [cljs.test
                    :refer (is deftest run-tests testing)]
                   [untangled.test.suite :refer [test-suite]])
  (:require untangled.test.dom-spec
            cljs.core
            untangled.history-spec
            untangled.state-spec
            untangled.events-spec
            untangled.test.events-spec
            smooth-test.report
            smooth-test.runner.browser
            [cljs.test :as test :include-macros true :refer [report]]))


(test-suite dom-report
            'untangled.test.dom-spec
            'untangled.state-spec
            'untangled.test.events-spec
            'untangled.events-spec
            'untangled.history-spec
            )

(defn on-load []
  (dom-report)
  )
