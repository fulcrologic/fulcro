(ns ^:figwheel-always cljs.user
  (:require-macros [cljs.test :refer (is deftest run-tests testing)])
  (:require cljs.core
            untangled.i18n-spec
            untangled.services.local-storage-io-spec
            untangled-spec.async
            untangled-spec.stub
            [untangled-spec.dom.suite :as ts :include-macros true]
            [cljs.test :as test :include-macros true :refer [report]]))


(ts/test-suite dom-report
               'untangled.i18n-spec
               'untangled.services.local-storage-io-spec
               )

(defn on-load []
  (dom-report)
  )

(dom-report)
