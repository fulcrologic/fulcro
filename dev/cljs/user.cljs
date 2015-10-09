(ns ^:figwheel-always cljs.user
  (:require-macros [cljs.test :refer (is deftest run-tests testing)])
  (:require cljs.core
            [untangled.test.suite :as ts :include-macros true]
            untangled.core-spec
            untangled.events-spec
            untangled.history-spec
            untangled.state-spec
            untangled.i18n-spec
            untangled.component-spec
            untangled.test.dom-spec
            untangled.test.events-spec
            untangled.services.local-storage-io-spec
            smooth-spec.async
            smooth-spec.stub
            [cljs.test :as test :include-macros true :refer [report]]))


(ts/test-suite dom-report
            'untangled.core-spec
            'untangled.component-spec
            'untangled.events-spec
            'untangled.history-spec
            'untangled.state-spec
            'untangled.i18n-spec
            'untangled.test.dom-spec
            'untangled.test.events-spec
            'untangled.services.local-storage-io-spec
            )


(defn on-load []
  (dom-report)
  )

(dom-report)
