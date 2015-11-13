(ns ^:figwheel-always cljs.user
  (:require-macros [cljs.test :refer (is deftest run-tests testing)])
  (:require cljs.core
            [untangled.test.suite :as ts :include-macros true]
            untangled.i18n-spec
            untangled.services.local-storage-io-spec
            untangled.test.dom-spec
            untangled-spec.async
            untangled.test.events-spec
            untangled-spec.stub
            [cljs.test :as test :include-macros true :refer [report]]))


(ts/test-suite dom-report
               'untangled.test.dom-spec
               'untangled.test.events-spec
               'untangled.i18n-spec
               'untangled.services.local-storage-io-spec
               )

(defn on-load []
  (dom-report)
  )

(dom-report)
