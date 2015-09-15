(ns ^:figwheel-always cljs.user
  (:require-macros [cljs.test
                    :refer (is deftest run-tests testing)]
                   [untangled.test.suite :refer [test-suite]])
  (:require cljs.core
            untangled.spec.core-spec
            untangled.spec.dom-spec
            untangled.spec.events-spec
            untangled.spec.events-trigger-spec
            untangled.spec.history-spec
            untangled.spec.state-spec
            [cljs.test :as test :include-macros true :refer [report]]))


(test-suite dom-report
            'untangled.spec.core-spec
            'untangled.spec.dom-spec
            'untangled.spec.events-spec
            'untangled.spec.events-trigger-spec
            'untangled.spec.history-spec
            'untangled.spec.state-spec
            )


(defn on-load []
  (dom-report)
  )

