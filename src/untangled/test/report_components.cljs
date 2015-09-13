(ns ^:figwheel-always untangled.test.report-components
  (:require
    [untangled.component :as c :include-macros true]
    [untangled.state :as qms]
    [untangled.events :as evt]
    [cljs-uuid-utils.core :as uuid]
    )
  )

(declare TestItem)

(defn color-favicon-data-url [color]
  (let [cvs (.createElement js/document "canvas")]
    (set! (.-width cvs) 16)
    (set! (.-height cvs) 16)
    (let [ctx (.getContext cvs "2d")]
      (set! (.-fillStyle ctx) color)
      (.fillRect ctx 0 0 16 16))
    (.toDataURL cvs)))


(defn change-favicon-to-color [color]
  (let [icon (.getElementById js/document "favicon")]
    (set! (.-href icon) (color-favicon-data-url color))))


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

(declare TestResult)

(c/defscomponent TestResult
                 :keyfn :id
                 [test-result context]
                 (c/li {}
                       (c/div {}
                              (if (:message test-result) (c/h3 {} (:message test-result)))
                              (c/table {} 
                                       (c/tr {} (c/td {:className "test-result-title"} "Actual")
                                             (c/td {:className "test-result"} (c/code {} (:actual test-result))))
                                       (c/tr {} (c/td {:className "test-result-title"} "Expected")
                                             (c/td {:className "test-result"} (c/code {} (:expected test-result)))))
                              )
                       )
                 )

(c/defscomponent TestItem
                 :keyfn :id
                 [test-item context]
                 (c/li {:className "test-item"}
                       (c/div {}
                              (c/span {:className (itemclass (:status test-item))} (:name test-item))
                              (c/ul {:className "test-list"}
                                    (map #(TestResult (result-path %) context) (:test-results test-item))
                                    )
                              (c/ul {:className "test-list"}
                                    (map #(TestItem (item-path %) context) (:test-items test-item))
                                    )
                              )
                       )
                 )

(c/defscomponent TestNamespace
                 :keyfn :name
                 [tests-by-namespace context]
                 (c/li {:className "test-item"}
                       (c/div {:className "test-namespace"}
                              (c/h2 {:className (itemclass (:status tests-by-namespace))} "Testing " (:name tests-by-namespace))
                              (c/ul {:className "test-list"}
                                    (map #(TestItem (item-path %) context) (:test-items tests-by-namespace))
                                    )
                              )
                       )
                 )

(c/defscomponent TestReport
                 :keyfn :id
                 [test-report context]
                 (c/section {:className "test-report"}

                            (c/ul {:className "test-list"}
                                  (map #(TestNamespace [:namespaces :name (:name %)] context) (:namespaces test-report))
                                  )

                            (let [rollup-stats (reduce (fn [acc item]
                                                         (let [counts [(:passed item) (:failed item) (:error item)
                                                                       (+ (:passed item) (:failed item) (:error item))]]
                                                           (map + acc counts))
                                                         ) [0 0 0 0] (:namespaces test-report))]
                              (if (< 0 (+ (nth rollup-stats 1) (nth rollup-stats 2)))
                                (change-favicon-to-color "#d00")
                                (change-favicon-to-color "#0d0"))
                              (c/div {:className "test-count"}
                                     (c/h2 {}
                                           (str "Tested " (count (:namespaces test-report)) " namespaces containing "
                                                (nth rollup-stats 3) " assertions. "
                                                (nth rollup-stats 0) " passed " (nth rollup-stats 1) " failed " (nth rollup-stats 2) " errors")
                                           )
                                     ))
                            )
                 )

