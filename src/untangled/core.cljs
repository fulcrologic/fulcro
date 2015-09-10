(ns untangled.core
  (:require [untangled.history :as h]
            [untangled.application]
            [untangled.test.report-components :as rc]
            [untangled.state :as qms]
            [quiescent.core :as q :include-macros true]
            )
  (:require-macros [cljs.test :refer (is deftest run-tests testing)]))

(defprotocol ITest
  (pass [this] "Tests are reporting that they passed")
  (begin-behavior [this behavior] "Tests are reporting the start of a behavior")
  (end-behavior [this] "Tests are reporting the end of a behavior")
  (begin-provided [this behavior] "Tests are reporting the start of a provided")
  (end-provided [this] "Tests are reporting the end of a provided")
  (begin-specification [this spec] "Tests are reporting the start of a specification")
  (end-specification [this] "Tests are reporting the end of a specification")
  (run-tests [this] "Run the tests for this app")
  (begin-namespace [this name] "Tests are reporting the start of a namespace")
  (push-test-item-path [this test-item] "Push a new test items onto the test item path")
  (pop-test-item-path [this] "Pops the last test item off of the test item path")
  )

(q/defcomponent Root
                "The root renderer for Untangled. Not for direct use."
                [state application]
                (let [ui-render (:renderer application)
                      context (qms/root-context application)
                      ]
                  (ui-render :top context)
                  ))

(defn translate-item-path [app-state test-item-path]
  (loop [data (:top @app-state)
         path test-item-path
         result []]
    (if (empty? path) result
                      (let [resolved-path (qms/resolve-data-path data (vector (take 3 path)))
                            context-data (get-in data resolved-path)]
                        (recur context-data (drop 3 path) (concat result resolved-path))))
    ))

(deftest translate-item-path-spec
  (let [app-state (atom {:top
                               {:summary    "",
                                :namespaces [{:name "untangled.test.dom-spec", :test-items [{:id "xyz"}]}],
                                :pass       3,
                                :fail       2,
                                :error      0},
                         :time #inst "2015-09-09T22:31:48.759-00:00"})]
    (is (= [:namespaces 0] (translate-item-path app-state [:namespaces :name "untangled.test.dom-spec"])))
    (is (= [:namespaces 0 :test-items 0] (translate-item-path app-state [:namespaces :name "untangled.test.dom-spec" :test-items :id "xyz"]))))

  )



(defrecord TestSuite
  [app-state dom-target history renderer is-undo test-runner test-level test-item-path]
  IApplication
  (render [this]
    (q/render (Root @app-state this)
              (.getElementById js/document dom-target)))
  (force-refresh [this] (swap! app-state #(assoc % :time (js/Date.))))
  (state-changed [this old-state new-state] (render this))

  ITest
  (pass [this] (let [translated-item-path (translate-item-path app-state @test-item-path)
                     result-path (concat [:top] translated-item-path [:result])
                     ]
                 (swap! app-state #(assoc-in % result-path :passed))
                 ))
  (push-test-item-path [this test-item] (swap! test-item-path #(conj % :test-items :id (:id test-item)))
    )
  (pop-test-item-path [this] (swap! test-item-path #(-> % (pop) (pop) (pop)))
    )
  (begin-namespace [this name]
    (reset! test-item-path [:namespaces :name name])
    (swap! app-state #(assoc-in % [:top :namespaces (count (:namespaces @app-state))] {:name name :test-items []}))
    )
  (run-tests [this] (test-runner))
  (begin-specification [this spec]
    (let [test-item (rc/make-testitem spec)
          test-items-count (count (get-in @app-state (concat @test-item-path [:test-items])))]
      (swap! app-state #(assoc-in % (concat [:top] (translate-item-path app-state @test-item-path) [:test-items test-items-count]) test-item))
      (push-test-item-path this test-item)
      )
    )
  (end-specification [this] (pop-test-item-path this)
    )
  (begin-behavior [this behavior]
    (let [test-item (rc/make-testitem behavior)
          test-items-count (count (get-in @app-state (concat @test-item-path [:test-items])))]


      (swap! app-state #(assoc-in % (concat [:top] (translate-item-path app-state @test-item-path) [:test-items test-items-count]) test-item))
      (push-test-item-path this test-item)
      )
    )
  (end-behavior [this] (pop-test-item-path this)
    )
  (begin-provided [this provided]
    (let [test-item (rc/make-testitem provided)
          test-items-count (count (get-in @app-state (concat @test-item-path [:test-items])))]


      (swap! app-state #(assoc-in % (concat [:top] (translate-item-path app-state @test-item-path) [:test-items test-items-count]) test-item))
      (push-test-item-path this test-item)
      )
    )
  (end-provided [this] (pop-test-item-path this)
    )
  )

(defrecord UntangledApplication
  [app-state dom-target history renderer]
  untangled.application/Application
  (render [this] (q/render (Root @app-state this) (.getElementById js/document dom-target)))
  (force-refresh [this]
    (swap! app-state #(assoc % :time (js/Date.)))
    (untangled.application/render this)
    )
  (state-changed [this old-state new-state] (untangled.application/render this)))

(defn new-application
  "Create a new Untangled application with:
  
  - `ui-render` : A top-level untangled component/renderer
  - `initial-state` : The state that goes with the top-level renderer
  
  Additional optional parameters by name:
  
  - `:target DOM_ID`: Specifies the target DOM element. The default is 'app'
  - `:history n` : Set the history size. The default is 100.
  "
  [ui-render initial-state & {:keys [target history] :or {target "app" history 100}}]
  (map->UntangledApplication {:app-state  (atom {:top initial-state :time (js/Date.)})
                              :renderer   ui-render
                              :dom-target target
                              :history    (atom (h/empty-history history))
                              }))

(defn new-test-suite
  "Create a new Untangled application with:

  - `:target DOM_ID`: Specifies the target DOM element. The default is 'test'\n


    - `target` :
  - `initial-state` : The state that goes with the top-level renderer

  Additional optional parameters by name:

  - `:history n` : Set the history size. The default is 100.
  "
  [& {:keys [target test-runner] :or {target "test" test-runner #()}}]
  (let [app (map->TestSuite {:app-state      (atom {:top (rc/make-testreport) :time (js/Date.)})
                             :renderer       rc/TestReport
                             :dom-target     target
                             :test-runner    test-runner
                             :test-item-path (atom [])
                             :history        (atom (h/empty-history 1))
                             :is-undo        (atom false)
                             })]
    (add-watch (:app-state app) ::render (fn [_ _ old-state new-state] (state-changed app old-state new-state)))
    app
    ))

