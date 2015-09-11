(ns untangled.test.suite
  )


(defn define-test-methods [name test-report-keyword]
  `(
     (cljs.core/defmethod cljs.test/report ~(keyword name) [~'m])
     (cljs.core/defmethod cljs.test/report [~test-report-keyword :pass] [~'m]
       (cljs.test/inc-report-counter! :pass)
       (untangled.core/pass ~name)
       )
     (cljs.core/defmethod cljs.test/report [~test-report-keyword :error] [~'m]
       (cljs.test/inc-report-counter! :error)
       (let [~'detail {:where    (cljs.test/testing-vars-str ~'m)
                       :message  (:message ~'m)
                       :expected (str (:expected ~'m))
                       :actual   (str (:actual ~'m))}]
         (untangled.core/fail ~name ~'detail))
       )
     (cljs.core/defmethod cljs.test/report [~test-report-keyword :fail] [~'m]
       (cljs.test/inc-report-counter! :fail)
       (let [~'detail {:where    (cljs.test/testing-vars-str ~'m)
                       :message  (:message ~'m)
                       :expected (str (:expected ~'m))
                       :actual   (str (:actual ~'m))}]
         (untangled.core/fail ~name ~'detail))
       )
     (cljs.core/defmethod cljs.test/report [~test-report-keyword :begin-test-ns] [~'m]
       (untangled.core/begin-namespace ~name (cljs.core/name (:ns ~'m)))
       )
     (cljs.core/defmethod cljs.test/report [~test-report-keyword :begin-specification] [~'m]
       (untangled.core/begin-specification ~name (:string ~'m))
       )
     (cljs.core/defmethod cljs.test/report [~test-report-keyword :end-specification] [~'m]
       (untangled.core/end-specification ~name)
       )
     (cljs.core/defmethod cljs.test/report [~test-report-keyword :begin-behavior] [~'m]
       (untangled.core/begin-behavior ~name (:string ~'m))
       )
     (cljs.core/defmethod cljs.test/report [~test-report-keyword :end-behavior] [~'m]
       (untangled.core/end-behavior ~name)
       )
     (cljs.core/defmethod cljs.test/report [~test-report-keyword :begin-provided] [~'m]
       (untangled.core/begin-provided ~name (:string ~'m))
       )
     (cljs.core/defmethod cljs.test/report [~test-report-keyword :end-provided] [~'m]
       (untangled.core/end-provided ~name)
       )
     (cljs.core/defmethod cljs.test/report [~test-report-keyword :summary] [~'m]
       (let [~'stats {:passed (:pass ~'m) :failed (:fail ~'m) :error (:error ~'m)}]
         (untangled.core/summary ~name ~'stats))
       )
     )
  )

(defmacro test-suite [name & test-namespaces]
  (let [state-name (symbol (str name "-state"))
        test-report-keyword (keyword (str *ns* "/" name))
        target (str name)
        ]
    `(do (cljs.core/defn ~name []
           (cljs.test/run-tests (cljs.test/empty-env ~test-report-keyword) ~@test-namespaces)
           )
         (cljs.core/defonce ~state-name (untangled.core/new-test-suite ~target))
         (untangled.core/render ~state-name)
          ~@(define-test-methods state-name test-report-keyword)
         )
        )
    )


