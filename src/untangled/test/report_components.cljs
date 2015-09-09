(ns ^:figwheel-always untangled.test.report-components
  (:require [figwheel.client :as fw]
            [quiescent.core :as q :include-macros true]
            [quiescent.dom :as d]
            [untangled.state :as qms]
            [untangled.events :as evt]
            [cljs-uuid-utils.core :as uuid]
            )
  (:require-macros [untangled.component :as c]))

(defn make-testreport
  ([] (make-testreport []))
  ([initial-items]
   {
    :summary    ""
    :namespaces {"namespace1"     {:test-items initial-items}
                 "namespace2"     {:test-items initial-items}}
    :pass       3
    :fail       2
    :error      0
    }
    )
  )

(defn make-testitem
  ([name] (make-testitem name []))
  ([name initial-items]
   {
    :id            (uuid/uuid-string (uuid/make-random-uuid))
    :name          name
    :result        :pending
    :result-detail nil
    :test-items    initial-items
    }
    )
  )


(defn item-path [item] [:test-items :id (:id item)])

(c/defscomponent TestItem
                 :keyfn :id
                 [test-item context]
                 (let [itemclass (cond
                                   (= (:result test-item) :pending) "test-pending"
                                   (= (:result test-item) :passed) "test-passed"
                                   (= (:result test-item) :error) "test-error"
                                   (= (:result test-item) :failed) "test-failed")]
                   (d/li {:className "test-item"}
                         (d/div {:className "test-header"}
                                (d/h3 {:className itemclass} (:name test-item))
                                (if (:result-detail test-item)
                                  (d/h3 {:className itemclass} (:result-detail test-item)))
                                (d/ul {:className "test-list"}
                                      (map #(TestItem (item-path %) context) (:test-items test-item))
                                      )
                                )
                         ))
                 )

(c/defscomponent TestReport
                 [test-report context]
                 (let [cbb (qms/op-builder context)]
                   (d/section {:className "test-report"}
                              (d/header {:className "header"}
                                        (d/h1 {} "Tests")
                                        )
                              (d/section {:className "main"}
                                         (d/ul {:className "test-list"}
                                               (map #(TestItem (item-path %) context) (:test-items test-report))
                                               )
                                         )
                              (d/footer {:className "footer"}
                                        (d/span {:className "test-count"} (d/strong {}
                                                                                    (str "Ran " (count (:test-items test-report)) " tests containing "
                                                                                         (+ (:pass test-report) (:fail test-report) (:error test-report)) " assertions. "
                                                                                         (:pass test-report) " passed " (:fail test-report) " failed " (:error test-report) " errors")
                                                                                    ))
                                        )

                              ))
                 )

