(ns untangled.test.html-test-report
  (:require [untangled.test.report-components :as rc]
            [figwheel.client :as fw]
            [quiescent.core :as q :include-macros true]
            [quiescent.dom :as d]
            [untangled.state :as qms]
            [untangled.events :as evt]
            [untangled.test.dom-spec]
            [cljs.test :as test :include-macros true :refer [report]]
            [untangled.core :as core])
  (:require-macros  [untangled.test.suite :refer [test-suite]]
                               )
  )

(def jeff "")
;(test-suite test-report 'untangled.test.dom-spec)



;(defn calendar-tests-test-runner []
;  (cljs.test/run-tests (cljs.test/empty-env :untangled.test.html-test-report/test-report) 'untangled.test.dom-spec)
;  )
;
;
;(defonce name (core/new-test-suite name :test-runner calendar-tests-test-runner))
;
;(defn on-js-reload [] (core/run-tests calendar-tests-test-runner))
;
;
;(core/render calendar-tests)
;
;(defmethod cljs.test/report :calendar-tests [m]
;  )
;
;(defmethod cljs.test/report [::calendar-tests :pass] [m]
;  (cljs.test/inc-report-counter! :pass)
;  (core/pass calendar-tests)
;  )
;
;(defmethod cljs.test/report [::calendar-tests :error] [m]
;  (cljs.test/inc-report-counter! :error)
;  (let [detail {:where    (cljs.test/testing-vars-str m)
;                :message  (:message m)
;                :expected (str (:expected m))
;                :actual   (str (:actual m))}]
;    (core/fail calendar-tests detail))
;  )
;
;(defmethod cljs.test/report [::calendar-tests :fail] [m]
;  (cljs.test/inc-report-counter! :fail)
;  (let [detail {:where    (cljs.test/testing-vars-str m)
;                :message  (:message m)
;                :expected (str (:expected m))
;                :actual   (str (:actual m))}]
;    (core/fail calendar-tests detail))
;  )
;
;(defmethod cljs.test/report [::test-report :begin-test-ns] [m]
;  (cljs.pprint/pprint "Hey")
;  (core/begin-namespace test-report (name (:ns m)))
;  )
;
;
;(defmethod cljs.test/report [::calendar-tests :begin-specification] [m]
;  (core/begin-specification calendar-tests (:string m))
;  )
;
;(defmethod cljs.test/report [::calendar-tests :end-specification] [m]
;  (core/end-specification calendar-tests)
;
;  )
;
;(defmethod cljs.test/report [::calendar-tests :begin-behavior] [m]
;  (core/begin-behavior calendar-tests (:string m))
;
;  )
;
;(defmethod cljs.test/report [::calendar-tests :end-behavior] [m]
;  (core/end-behavior calendar-tests)
;  )
;
;(defmethod cljs.test/report [::calendar-tests :begin-provided] [m]
;  (core/begin-provided calendar-tests (:string m))
;  )
;
;(defmethod cljs.test/report [::calendar-tests :end-provided] [m]
;  (core/end-provided calendar-tests)
;  )
;
;(defmethod cljs.test/report [::calendar-tests :summary] [m]
;  (let [stats {:passed (:pass m) :failed (:fail m) :error (:error m)}]
;    (core/summary calendar-tests stats))
;  )
