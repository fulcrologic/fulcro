(ns untangled.test.html-test-report
  (:require [untangled.test.report-components :as rc]
            [figwheel.client :as fw]
            [quiescent.core :as q :include-macros true]
            [quiescent.dom :as d]
            [untangled.state :as qms]
            [untangled.events :as evt]
            [cljs.test :as test :include-macros true :refer [report]]
            )
  )

(defonce app-state
         (atom {
                :test-report        (rc/make-testreport [(rc/make-testitem "item1"
                                                                           [(rc/make-testitem "sub-item1"
                                                                                              [(rc/make-testitem "sub-sub-item1")])
                                                                            (rc/make-testitem "sub-item2")])
                                                         (rc/make-testitem "item2")])
                :__figwheel_counter 0
                }))

(q/defcomponent Root [data context]
                (d/div {}
                       (d/div {:className "testreportapp"}
                              (rc/TestReport :test-report context)
                              )))

(defn render [data app-state]
  (q/render (Root data (qms/root-scope app-state))
            (.getElementById js/document "app")))


(add-watch app-state ::render
           (fn [_ _ _ data]
             (render data app-state)))

(defn on-js-reload []
  ;; touch app-state to force rerendering
  (swap! app-state update-in [:__figwheel_counter] inc)
  (render @app-state app-state)
  )

(render @app-state app-state)





(defn begin-test-namespace [name]
  (swap! app-state #(assoc-in % [:namespaces name :test-items] []))
  (reset! *current-namespace* name)
  )


(defmethod cljs.test/report :browser [m]
  )

(defmethod cljs.test/report [::browser :pass ] [m]
  (cljs.test/inc-report-counter! :pass)
  )

(defmethod cljs.test/report [::browser :error] [m]
  (cljs.test/inc-report-counter! :error)
  (println "\nERROR in" (cljs.test/testing-vars-str m))
  (when-let [message (:message m)] (println message))
  (println "expected:" (pr-str (:expected m)))
  (println "  actual:" (pr-str (:actual m)))
  (println)
  )

(defmethod cljs.test/report [::browser :fail] [m]
  (cljs.test/inc-report-counter! :fail)
  (println "\nFAIL in" (cljs.test/testing-vars-str m))
  (when-let [message (:message m)] (println message))
  (println "expected:" (pr-str (:expected m)))
  (println "  actual:" (pr-str (:actual m)))
  (println)
  )

(defmethod cljs.test/report [::browser :begin-test-ns ] [m]
  (begin-test-namespace (name (:ns m)))
  )

(defmethod cljs.test/report [::browser :begin-specification ] [m]
  (reset! *test-level* 0)
  )

(defmethod cljs.test/report [::browser :end-specification ] [m]
  (println)
  (reset! *test-level* 0)
  )

(defmethod cljs.test/report [::browser :begin-behavior ] [m]
  (swap! *test-level* inc)
  )

(defmethod cljs.test/report [::browser :end-behavior ] [m]
  (swap! *test-level* dec)
  )

(defmethod cljs.test/report [::browser :begin-provided ] [m]
  (swap! *test-level* inc)
  )

(defmethod cljs.test/report [::browser :end-provided ] [m]
  (swap! *test-level* dec)
  )


(defmethod cljs.test/report [::browser :summary ] [m]
  (println "\nRan" (:test m) "tests containing"
           (+ (:pass m) (:fail m) (:error m)) "assertions.")
  (println (:fail m) "failures," (:error m) "errors.")
  )
