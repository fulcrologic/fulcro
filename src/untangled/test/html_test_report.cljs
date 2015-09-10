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
  )

(defn test-runner []
  (cljs.test/run-tests (cljs.test/empty-env :untangled.test.html-test-report/browser) 'untangled.test.dom-spec)
  )

(defonce test-report (core/new-test-suite :test-runner test-runner))

(defn on-js-reload [] (core/run-tests test-report))


(core/render test-report)

(defmethod cljs.test/report :browser [m]
  )

(defmethod cljs.test/report [::browser :pass] [m]
  (cljs.test/inc-report-counter! :pass)
  (core/pass test-report)
  )

(defmethod cljs.test/report [::browser :error] [m]
  (cljs.test/inc-report-counter! :error)
  (let [detail {:where    (cljs.test/testing-vars-str m)
                :message  (:message m)
                :expected (str (:expected m))
                :actual   (str (:actual m))}]
    (core/fail test-report detail))
  )

(defmethod cljs.test/report [::browser :fail] [m]
  (cljs.test/inc-report-counter! :fail)
  (let [detail {:where    (cljs.test/testing-vars-str m)
                :message  (:message m)
                :expected (str (:expected m))
                :actual   (str (:actual m))}]
    (core/fail test-report detail))
  )

(defmethod cljs.test/report [::browser :begin-test-ns] [m]
  (core/begin-namespace test-report (name (:ns m)))
  )


(defmethod cljs.test/report [::browser :begin-specification] [m]
  (core/begin-specification test-report (:string m))
  )

(defmethod cljs.test/report [::browser :end-specification] [m]
  (core/end-specification test-report)

  )

(defmethod cljs.test/report [::browser :begin-behavior] [m]
  (core/begin-behavior test-report (:string m))

  )

(defmethod cljs.test/report [::browser :end-behavior] [m]
  (core/end-behavior test-report)
  )

(defmethod cljs.test/report [::browser :begin-provided] [m]
  (core/begin-provided test-report (:string m))
  )

(defmethod cljs.test/report [::browser :end-provided] [m]
  (core/end-provided test-report)
  )

(defmethod cljs.test/report [::browser :summary] [m]
  (let [stats {:passed (:pass m) :failed (:fail m) :error (:error m)}]
    (core/summary test-report stats))
  )
