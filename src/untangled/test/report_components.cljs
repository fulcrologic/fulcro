(ns ^:figwheel-always untangled.test.report-components
  (:require [figwheel.client :as fw]
            [quiescent.core :as q :include-macros true]
            [quiescent.dom :as d]
            [untangled.state :as qms]
            [untangled.events :as evt]
            [cljs-uuid-utils.core :as uuid]
            )
  (:require-macros [untangled.component :as c]))

(declare TestItem)

(defn make-testreport
  ([] (make-testreport []))
  ([initial-items]
   {
    :id           (uuid/uuid-string (uuid/make-random-uuid))
    :summary    ""
    :namespaces []
    }
    )
  )

(defn make-testitem
  [name]
  {
   :id           (uuid/uuid-string (uuid/make-random-uuid))
   :name         name
   :status       :pending
   :test-items   []
   :test-results []
   }
  )

(defn make-test-result
  [result result-detail]
  {:id       (uuid/uuid-string (uuid/make-random-uuid))
   :status   result
   :message  (:message result-detail)
   :where    (:where result-detail)
   :expected (:expected result-detail)
   :actual   (:actual result-detail)}
  )

(defn make-tests-by-namespace
  [name]
  {:id         (uuid/uuid-string (uuid/make-random-uuid))
   :name       name
   :test-items []
   :status     :pending
   :passed     0
   :failed     0
   :error      0})


(defn item-path [item] [:test-items :id (:id item)])
(defn result-path [item] [:test-results :id (:id item)])

(defn itemclass [status]
  (cond
    (= status :pending) "test-pending"
    (= status :passed) "test-passed"
    (= status :error) "test-error"
    (= status :failed) "test-failed")
  )

(c/defscomponent TestResult
                 :keyfn :id
                 [test-result context]
                 (d/li {:className "test-result"}
                       (d/div {:className "test-result"}
                              (if (:message test-result) (d/h3 {} (:message test-result)))
                              (d/div {} (d/span {:className "test-result-title"} "Where: ") (d/span {} (:where test-result)))
                              (d/div {} (d/span {:className "test-result-title"} "Actual: ") (d/span {} (:actual test-result)))
                              (d/div {} (d/span {:className "test-result-title"} "Expected: ") (d/span {} (:expected test-result)))
                              )
                       )
                 )

(c/defscomponent TestItem
                 :keyfn :id
                 [test-item context]
                 (d/li {:className "test-item"}
                       (d/div {}
                              (d/span {:className (itemclass (:status test-item))} (:name test-item))
                              (d/ul {:className "test-list"}
                                    (map #(TestResult (result-path %) context) (:test-results test-item))
                                    )
                              (d/ul {:className "test-list"}
                                    (map #(TestItem (item-path %) context) (:test-items test-item))
                                    )
                              )
                       )
                 )

(c/defscomponent TestNamespace
                 :keyfn :name
                 [tests-by-namespace context]
                 (d/li {:className "test-item"}
                       (d/div {:className "test-header"}
                              (d/span {:className (itemclass (:status tests-by-namespace))} (:name tests-by-namespace))
                              (d/ul {:className "test-list"}
                                    (map #(TestItem (item-path %) context) (:test-items tests-by-namespace))
                                    )
                              )
                       (d/div {:className "test-count"}
                              (d/span {}
                                      (str "Ran " (count (:test-items tests-by-namespace)) " tests containing "
                                           (+ (:passed tests-by-namespace) (:failed tests-by-namespace) (:error tests-by-namespace)) " assertions. "
                                           (:passed tests-by-namespace) " passed " (:failed tests-by-namespace) " failed " (:error tests-by-namespace) " errors")
                                      )
                              ))
                 )

(c/defscomponent TestReport
                 :keyfn :id
                 [test-report context]
                 (d/section {:className "test-report"}
                            (d/div {:className "test-header"}
                                   (d/span {} "Test")
                                   )
                            (d/section {:className "main"}
                                       (d/ul {:className "test-list"}
                                             (map #(TestNamespace [:namespaces :name (:name %)] context) (:namespaces test-report))
                                             )
                                       )

                            )
                 )

