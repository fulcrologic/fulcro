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
    :id         (uuid/uuid-string (uuid/make-random-uuid))
    :summary    ""
    :namespaces []
    :passed     0
    :failed     0
    :error      0
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
   })


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
                 (d/li {}
                       (d/div {}
                              (if (:message test-result) (d/h3 {} (:message test-result)))
                              (d/table {} (d/tr {} (d/td {:className "test-result-title"} "In")
                                                (d/td {:className "test-result"} (d/code {} (:where test-result))))
                                       (d/tr {} (d/td {:className "test-result-title"} "Actual")
                                             (d/td {:className "test-result"} (d/code {} (:actual test-result))))
                                       (d/tr {} (d/td {:className "test-result-title"} "Expected")
                                             (d/td {:className "test-result"} (d/code {} (:expected test-result)))))
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
                       (d/div {:className "test-namespace"}
                              (d/h2 {:className (itemclass (:status tests-by-namespace))} "Testing " (:name tests-by-namespace))
                              (d/ul {:className "test-list"}
                                    (map #(TestItem (item-path %) context) (:test-items tests-by-namespace))
                                    )
                              )
                       )
                 )

(c/defscomponent TestReport
                 :keyfn :id
                 [test-report context]
                 (d/section {:className "test-report"}

                            (d/ul {:className "test-list"}
                                  (map #(TestNamespace [:namespaces :name (:name %)] context) (:namespaces test-report))
                                  )

                            (let [rollup-stats (reduce (fn [acc item]
                                                         (let [counts [(:passed item) (:failed item) (:error item)
                                                                       (+ (:passed item) (:failed item) (:error item))]]
                                                           (map + acc counts))
                                                         ) [0 0 0 0] (:namespaces test-report))]
                              (cljs.pprint/pprint rollup-stats)
                              (d/div {:className "test-count"}
                                     (d/h2 {}
                                             (str "Tested " (count (:namespaces test-report)) " namespaces containing "
                                                  (nth rollup-stats 3) " assertions. "
                                                  (nth rollup-stats 0) " passed " (nth rollup-stats 1) " failed " (nth rollup-stats 2) " errors")
                                             )
                                     ))
                            )
                 )

