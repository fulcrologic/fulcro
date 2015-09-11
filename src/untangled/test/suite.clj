(ns untangled.test.suite
  )


(defmacro test-suite [name & test-namespaces]
  (let [test-runner-name (symbol (str name "-test-runner"))
        test-report-keyword (keyword (str *ns* "/" name))
        target (str name)
        ]
    `(do (cljs.core/defn ~test-runner-name []
           (cljs.test/run-tests (cljs.test/empty-env ~test-report-keyword) ~@test-namespaces)
           )


         (cljs.core/defonce ~name (untangled.core/new-test-suite :test-runner ~test-runner-name))

         (cljs.core/defn ~'on-js-reload [] (untangled.core/run-tests ~name))
         (untangled.core/render ~name)

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
           (cljs.pprint/pprint "in begin-test-namespace")
           (untangled.core/begin-namespace ~name (cljs.core/name (:ns ~'m)))
           )
         (cljs.core/defmethod cljs.test/report [~test-report-keyword :begin-specification] [~'m]
           (cljs.pprint/pprint "in begin-test-specification")
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
  )


