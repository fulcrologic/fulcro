(ns ^:figwheel-always untangled.test.suite
  (:require
    [untangled.component :as c :include-macros true]
    [untangled.state :as qms]
    [untangled.core :as core]
    [untangled.events :as evt]
    [cljs-uuid-utils.core :as uuid]
    [untangled.state :as state]
    [untangled.history :as h]
    [quiescent.core :as q])
  )

(defprotocol ITest
  (set-test-result [this status] "Set the pass/fail/error result of a test")
  (pass [this] "Tests are reporting that they passed")
  (fail [this detail] "Tests are reporting that they failed, with additional details")
  (error [this detail] "Tests are reporting that they error'ed, with additional details")
  (summary [this stats] "A summary of the test run, how many passed/failed/error'ed")
  (begin-manual [this behavior] "Manual test")
  (end-manual [this] "Manual test")
  (begin-behavior [this behavior] "Tests are reporting the start of a behavior")
  (end-behavior [this] "Tests are reporting the end of a behavior")
  (begin-provided [this behavior] "Tests are reporting the start of a provided")
  (end-provided [this] "Tests are reporting the end of a provided")
  (begin-specification [this spec] "Tests are reporting the start of a specification")
  (end-specification [this] "Tests are reporting the end of a specification")
  (begin-namespace [this name] "Tests are reporting the start of a namespace")
  (push-test-item-path [this test-item index] "Push a new test items onto the test item path")
  (pop-test-item-path [this] "Pops the last test item off of the test item path")
  )

(defn translate-item-path [app-state test-item-path]
  (loop [data (:top @app-state)
         path test-item-path
         result [:top]]
    (if (empty? path)
      result
      (let [resolved-path (qms/resolve-data-path data (vector (seq (take 4 path))))
            context-data (get-in data resolved-path)]
        (recur context-data (drop 4 path) (concat result resolved-path))))
    ))

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
    :id            (uuid/uuid-string (uuid/make-random-uuid))
    :report/filter :all
    :summary       ""
    :namespaces    []
    :passed        0
    :failed        0
    :error         0
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

(defn make-manual [name] (make-testitem (str name " (MANUAL TEST)")))

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
   :folded?    false
   :test-items []
   :status     :pending
   })

(defn item-path [item index] [:test-items :id (:id item) index])


(defn result-path [item index] [:test-results :id (:id item) index])
(defn itemclass [status]
  (cond
    (= status :pending) "test-pending"
    (= status :manual) "test-manually"
    (= status :passed) "test-passed"
    (= status :error) "test-error"
    (= status :failed) "test-failed")
  )

(declare TestResult)

(defn filter-class [test-item]
  (let [filter (:report/filter test-item)
        state (:status test-item)]
    (cond
      (and (= :failed filter) (not= :error state) (not= :failed state)) "hidden"
      (and (= :manual filter) (not= :manual state)) "hidden"
      (= :all filter) ""
      ))
  )

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
                 (c/li {:className "test-item "}
                       (c/div {:className (filter-class test-item)}
                              (c/span {:className (itemclass (:status test-item))} (:name test-item))
                              (let [element-id (partial state/list-element-id test-item :test-results :id)]
                                (c/ul {:className "test-list"}
                                      (map-indexed (fn [idx item] (TestResult (element-id idx) context)) (:test-results test-item))
                                      )
                                )
                              (let [element-id (partial state/list-element-id test-item :test-items :id)]
                                (c/ul {:className "test-list"}
                                      (map-indexed (fn [idx item] (TestItem (element-id idx) context)) (:test-items test-item))
                                      )
                                )
                              )
                       )
                 )

(c/defscomponent TestNamespace
                 :keyfn :name
                 [tests-by-namespace context]
                 (let [folded? (:folded? tests-by-namespace)]
                   (c/li {:className "test-item"}
                         (c/div {:className "test-namespace"
                                 :onClick   (fn [] (state/transact! context #(update % :folded? not)))
                                 }
                                (c/h2 {:className (itemclass (:status tests-by-namespace))} "Testing " (:name tests-by-namespace))
                                (let [element-id (partial state/list-element-id tests-by-namespace :test-items :id)]
                                  (c/ul {:className (if folded? "hidden" "test-list")}
                                        (map-indexed (fn [idx item] (TestItem (element-id idx) context)) (:test-items tests-by-namespace))
                                        ;(build-list TestItem :test-items :id tests-by-namespace)
                                        )
                                  )
                                )
                         ))
                 )

(c/defscomponent TestReport
                 :keyfn :id
                 :publish #{:report/filter}
                 [test-report context]
                 (c/section {:className "test-report"}
                            (c/div {:name "filters" :className "filter-controls"}
                                   (c/label {:htmlFor "filters"} "Filter: ")
                                   (c/a {:className (if (= (:report/filter test-report) :all) "selected" "") :onClick (fn [] (state/transact! context #(assoc % :report/filter :all)))} "All")
                                   (c/a {:className (if (= (:report/filter test-report) :manual) "selected" "") :onClick (fn [] (state/transact! context #(assoc % :report/filter :manual)))} "Manual")
                                   (c/a {:className (if (= (:report/filter test-report) :failed) "selected" "") :onClick (fn [] (state/transact! context #(assoc % :report/filter :failed)))} "Failed"))
                            (let [element-id (partial state/list-element-id test-report :namespaces :name)]
                              (c/ul {:className "test-list"}
                                    (map-indexed (fn [idx item] (TestNamespace (element-id idx) context)) (:namespaces test-report))
                                    )
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

(defrecord TestSuite
  [app-state dom-target history renderer test-item-path]
  untangled.application/Application
  (render [this] (q/render (core/Root @app-state this) (.getElementById js/document dom-target)))
  (force-refresh [this]
    (swap! app-state #(assoc % :time (js/Date.)))
    (untangled.application/render this)
    )
  (state-changed [this old-state new-state] (untangled.application/render this))

  ITest
  (set-test-result [this status] (let [translated-item-path (translate-item-path app-state @test-item-path)]
                                   (loop [current-test-result-path translated-item-path]
                                     (if (> (count current-test-result-path) 1)
                                       (let [target (get-in @app-state current-test-result-path)
                                             current-status (:status target)]
                                         (if (not (or (= current-status :manual) (= current-status :error) (= current-status :failed)))
                                           (swap! app-state #(assoc-in % (concat current-test-result-path [:status]) status)))
                                         (recur (drop-last 2 current-test-result-path)))))))

  (push-test-item-path [this test-item index] (swap! test-item-path #(conj % :test-items :id (:id test-item) index)))

  (pop-test-item-path [this] (swap! test-item-path #(-> % (pop) (pop) (pop) (pop))))

  (begin-namespace [this name]
    (let [namespaces (get-in @app-state [:top :namespaces])
          namespace-index (first (keep-indexed (fn [idx val] (when (= (:name val) name) idx)) namespaces))
          name-space-location (if namespace-index namespace-index (count namespaces))
          ]
      (reset! test-item-path [:namespaces :name name name-space-location])
      (swap! app-state #(assoc-in % [:top :namespaces name-space-location] (make-tests-by-namespace name))))
    )

  (begin-specification [this spec]
    (let [test-item (make-testitem spec)
          test-items-count (count (get-in @app-state (concat (translate-item-path app-state @test-item-path) [:test-items])))]
      (swap! app-state #(assoc-in % (concat (translate-item-path app-state @test-item-path) [:test-items test-items-count]) test-item))
      (push-test-item-path this test-item test-items-count)
      )
    )

  (end-specification [this] (pop-test-item-path this))

  (begin-behavior [this behavior]
    (let [test-item (make-testitem behavior)
          parent-test-item (get-in @app-state (translate-item-path app-state @test-item-path))
          test-items-count (count (:test-items parent-test-item))]
      (swap! app-state #(assoc-in % (concat (translate-item-path app-state @test-item-path) [:test-items test-items-count]) test-item))
      (push-test-item-path this test-item test-items-count)
      )
    )

  (end-behavior [this] (pop-test-item-path this))

  (begin-manual [this behavior]
    (let [test-item (make-manual behavior)
          parent-test-item (get-in @app-state (translate-item-path app-state @test-item-path))
          test-items-count (count (:test-items parent-test-item))]
      (swap! app-state #(assoc-in % (concat (translate-item-path app-state @test-item-path) [:test-items test-items-count]) test-item))
      (push-test-item-path this test-item test-items-count)
      )
    )

  (end-manual [this]
    (set-test-result this :manual)
    (pop-test-item-path this))


  (begin-provided [this provided]
    (let [test-item (make-testitem provided)
          test-items-count (count (get-in @app-state (concat (translate-item-path app-state @test-item-path) [:test-items])))]
      (swap! app-state #(assoc-in % (concat (translate-item-path app-state @test-item-path) [:test-items test-items-count]) test-item))
      (push-test-item-path this test-item test-items-count)
      )
    )

  (end-provided [this] (pop-test-item-path this))

  (pass [this] (set-test-result this :passed))

  (error [this detail] (let [translated-item-path (translate-item-path app-state @test-item-path)
                             current-test-item (get-in @app-state translated-item-path)
                             test-result (make-test-result :error detail)
                             test-result-path (concat translated-item-path
                                                      [:test-results (count (:test-results current-test-item))])]
                         (set-test-result this :error)
                         (swap! app-state #(assoc-in % test-result-path test-result))
                         ))

  (fail [this detail] (let [translated-item-path (translate-item-path app-state @test-item-path)
                            current-test-item (get-in @app-state translated-item-path)
                            test-result (make-test-result :failed detail)
                            test-result-path (concat translated-item-path
                                                     [:test-results (count (:test-results current-test-item))])]
                        (set-test-result this :failed)
                        (swap! app-state #(assoc-in % test-result-path test-result))
                        ))

  (summary [this stats]
    (let [translated-item-path (translate-item-path app-state @test-item-path)]
      (swap! app-state #(assoc-in % (concat translated-item-path [:passed]) (:passed stats)))
      (swap! app-state #(assoc-in % (concat translated-item-path [:failed]) (:failed stats)))
      (swap! app-state #(assoc-in % (concat translated-item-path [:error]) (:error stats)))
      ))
  )

(defn new-test-suite
  "Create a new Untangled application with:

  - `:target DOM_ID`: Specifies the target DOM element. The default is 'test'\n


    - `target` :
  - `initial-state` : The state that goes with the top-level renderer

  Additional optional parameters by name:

  - `:history n` : Set the history size. The default is 100.
  "
  [target]
  (map->TestSuite {:app-state      (atom {:top (make-testreport) :time (js/Date.)})
                   :renderer       #(TestReport %1 %2)
                   :dom-target     target
                   :test-item-path (atom [])
                   :history        (atom (h/empty-history 1))
                   }))
