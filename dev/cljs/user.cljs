(ns ^:figwheel-always cljs.user
  (:require-macros [cljs.test
                    :refer (is deftest run-tests testing)]
                   [untangled.test.suite :refer [test-suite]])
  (:require cljs.core
            untangled.core-spec
            untangled.events-spec
            untangled.history-spec
            untangled.state-spec
            untangled.test.dom-spec
            untangled.test.events-spec
            untangled.services.local-storage-io-spec
            [cljs.test :as test :include-macros true :refer [report]]))


(test-suite dom-report
            'untangled.core-spec
            'untangled.events-spec
            'untangled.history-spec
            'untangled.state-spec
            'untangled.test.dom-spec
            'untangled.test.events-spec
            'untangled.services.local-storage-io-spec
            )


(defn on-load []
  (dom-report)
  )

(dom-report)
