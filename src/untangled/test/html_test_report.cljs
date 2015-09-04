(ns untangled.test.html-test-report
  )

(defonce app-state
         (atom {
                ;:todo-list         (make-todolist [(new-item "Go to store") (new-item "Eat stuff")])
                
                :__figwheel_counter 0
                }))

(def ^:dynamic *test-level* (atom 0))

(defmethod cljs.test/report :console [m]
  )

(defmethod cljs.test/report [::console :pass ] [m]
  (cljs.test/inc-report-counter! :pass)
  )

(defmethod cljs.test/report [::console :error] [m]
  (cljs.test/inc-report-counter! :error)
  (println "\nERROR in" (cljs.test/testing-vars-str m))
  (when-let [message (:message m)] (println message))
  (println "expected:" (pr-str (:expected m)))
  (println "  actual:" (pr-str (:actual m)))
  (println)
  )

(defmethod cljs.test/report [::console :fail] [m]
    (cljs.test/inc-report-counter! :fail)
    (println "\nFAIL in" (cljs.test/testing-vars-str m))
    (when-let [message (:message m)] (println message))
    (println "expected:" (pr-str (:expected m)))
    (println "  actual:" (pr-str (:actual m)))
    (println)
    )

(defmethod cljs.test/report [::console :begin-test-ns ] [m]
  (name (:ns m))
  )

(defmethod cljs.test/report [::console :begin-specification ] [m]
    (reset! *test-level* 0)
  )

(defmethod cljs.test/report [::console :end-specification ] [m]
  (println)
  (reset! *test-level* 0)
  )

(defmethod cljs.test/report [::console :begin-behavior ] [m]
  (swap! *test-level* inc)
  )

(defmethod cljs.test/report [::console :end-behavior ] [m]
  (swap! *test-level* dec)
  )

(defmethod cljs.test/report [::console :begin-provided ] [m]
  (swap! *test-level* inc)
  )

(defmethod cljs.test/report [::console :end-provided ] [m]
  (swap! *test-level* dec)
  )


(defmethod cljs.test/report [::console :summary ] [m]
  (println "\nRan" (:test m) "tests containing"
                       (+ (:pass m) (:fail m) (:error m)) "assertions.")
              (println (:fail m) "failures," (:error m) "errors.")
  )
