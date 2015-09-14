(ns untangled.core
  (:require [untangled.history :as h]
            [untangled.application :refer [Application]]
            [untangled.test.report-components :as rc]
            [untangled.test.dom :refer [render-as-dom]]
            [untangled.state :as qms]
            [quiescent.core :as q :include-macros true]
            )
  (:require-macros [cljs.test :refer (is deftest run-tests testing)]))

(defprotocol ITest
  (set-test-result [this status] "Set the pass/fail/error result of a test")
  (pass [this] "Tests are reporting that they passed")
  (fail [this detail] "Tests are reporting that they failed, with additional details")
  (error [this detail] "Tests are reporting that they error'ed, with additional details")
  (summary [this stats] "A summary of the test run, how many passed/failed/error'ed")
  (begin-behavior [this behavior] "Tests are reporting the start of a behavior")
  (end-behavior [this] "Tests are reporting the end of a behavior")
  (begin-provided [this behavior] "Tests are reporting the start of a provided")
  (end-provided [this] "Tests are reporting the end of a provided")
  (begin-specification [this spec] "Tests are reporting the start of a specification")
  (end-specification [this] "Tests are reporting the end of a specification")
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
         result [:top]]
    (if (empty? path) result
                      (let [resolved-path (qms/resolve-data-path data (vector (take 3 path)))
                            context-data (get-in data resolved-path)]
                        (recur context-data (drop 3 path) (concat result resolved-path))))
    ))

(defrecord TestSuite
  [app-state dom-target history renderer test-item-path]
  untangled.application/Application
  (render [this] (q/render (Root @app-state this) (.getElementById js/document dom-target)))
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
                                         (if (not (or (= current-status :error)(= current-status :failed)))
                                           (swap! app-state #(assoc-in % (concat current-test-result-path [:status]) status)))
                                         (recur (drop-last 2 current-test-result-path)))))))

  (push-test-item-path [this test-item] (swap! test-item-path #(conj % :test-items :id (:id test-item))))

  (pop-test-item-path [this] (swap! test-item-path #(-> % (pop) (pop) (pop))))

  (begin-namespace [this name]
    (let [namespaces (get-in @app-state [:top :namespaces])
          namespace-index (first (keep-indexed (fn [idx val] (when (= (:name val) name) idx)) namespaces))
          name-space-location (if namespace-index namespace-index (count namespaces))
          ]
      (reset! test-item-path [:namespaces :name name])
      (swap! app-state #(assoc-in % [:top :namespaces name-space-location] (rc/make-tests-by-namespace name))))
    )

  (begin-specification [this spec]
    (let [test-item (rc/make-testitem spec)
          test-items-count (count (get-in @app-state (concat (translate-item-path app-state @test-item-path) [:test-items])))]
      (swap! app-state #(assoc-in % (concat (translate-item-path app-state @test-item-path) [:test-items test-items-count]) test-item))
      (push-test-item-path this test-item)
      )
    )

  (end-specification [this] (pop-test-item-path this))

  (begin-behavior [this behavior]
    (let [test-item (rc/make-testitem behavior)
          parent-test-item (get-in @app-state (translate-item-path app-state @test-item-path))
          test-items-count (count (:test-items parent-test-item))]
      (swap! app-state #(assoc-in % (concat (translate-item-path app-state @test-item-path) [:test-items test-items-count]) test-item))
      (push-test-item-path this test-item)
      )
    )

  (end-behavior [this] (pop-test-item-path this))

  (begin-provided [this provided]
    (let [test-item (rc/make-testitem provided)
          test-items-count (count (get-in @app-state (concat (translate-item-path app-state @test-item-path) [:test-items])))]
    (swap! app-state #(assoc-in % (concat (translate-item-path app-state @test-item-path) [:test-items test-items-count]) test-item))
      (push-test-item-path this test-item)
      )
    )

  (end-provided [this] (pop-test-item-path this))

  (pass [this] (set-test-result this :passed))

  (error [this detail] (let [translated-item-path (translate-item-path app-state @test-item-path)
                             current-test-item (get-in @app-state translated-item-path)
                             test-result (rc/make-test-result :error detail)
                             test-result-path (concat translated-item-path
                                                      [:test-results (count (:test-results current-test-item))])]
                         (set-test-result this :error)
                         (swap! app-state #(assoc-in % test-result-path test-result))
                         ))

  (fail [this detail] (let [translated-item-path (translate-item-path app-state @test-item-path)
                            current-test-item (get-in @app-state translated-item-path)
                            test-result (rc/make-test-result :failed detail)
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

(defrecord UntangledApplication
  [app-state dom-target history renderer test-mode]
  Application
  (render [this]
    (if test-mode
      (render-as-dom (Root @app-state this))
      (q/render (Root @app-state this) (.getElementById js/document dom-target))))
  (force-refresh [this]
    (swap! app-state #(assoc % :time (js/Date.)))
    (untangled.application/render this)
    )
  (state-changed [this old-state new-state] (untangled.application/render this))
  (current-state [this] (-> @app-state :top))
  (current-state [this subpath] (get-in (-> @app-state :top) subpath))
  )

(defn new-application
  "Create a new Untangled application with:
  
  - `ui-render` : A top-level untangled component/renderer
  - `initial-state` : The state that goes with the top-level renderer
  
  Additional optional parameters by name:
  
  - `:target DOM_ID`: Specifies the target DOM element. The default is 'app'
  - `:history n` : Set the history size. The default is 100.
  - `:test-mode boolean`: Put the application in unit test mode. This causes render to return 
  a disconnected DOM fragment instead of actually rendering to visible DOM. Thus, render will 
  *return* the DOM fragment instead of side-effecting it onto the screen.
  "
  [ui-render initial-state & {:keys [target history test-mode] :or {test-mode false target "app" history 100}}]
  (map->UntangledApplication {:app-state  (atom {:top initial-state :time (js/Date.)})
                              :renderer   ui-render
                              :dom-target target
                              :history    (atom (h/empty-history history))
                              :test-mode test-mode
                              }))


(defn new-test-suite
  "Create a new Untangled application with:

  - `:target DOM_ID`: Specifies the target DOM element. The default is 'test'\n


    - `target` :
  - `initial-state` : The state that goes with the top-level renderer

  Additional optional parameters by name:

  - `:history n` : Set the history size. The default is 100.
  "
  [target]
  (map->TestSuite {:app-state      (atom {:top (rc/make-testreport) :time (js/Date.)})
                   :renderer       #(rc/TestReport %1 %2)
                   :dom-target     target
                   :test-item-path (atom [])
                   :history        (atom (h/empty-history 1))
                   }))

