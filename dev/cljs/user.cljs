(ns ^:figwheel-always cljs.user
  (:require-macros [cljs.test
                    :refer (is deftest run-tests testing)])
  (:require untangled.test.dom-spec
            cljs.core
            untangled.test.events-spec
            untangled.history-spec
            [cljs.tagged-literals :refer [*cljs-data-readers*]]
            [untangled.test.html-test-report :as tr]
            untangled.state-spec
            smooth-test.report
            smooth-test.runner.browser
            [cljs.test :as test :include-macros true :refer [report]]))

; TODO: this will go away when smooth-test is finished
(enable-console-print!)

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

(defmethod report [::test/default :summary] [m]
  (println "\nRan" (:test m) "tests containing"
           (+ (:pass m) (:fail m) (:error m)) "assertions.")
  (println (:fail m) "failures," (:error m) "errors.")
  (if (< 0 (+ (:fail m) (:error m)))
    (change-favicon-to-color "#d00")
    (change-favicon-to-color "#0d0")))

(defn run-all-tests []
  (run-tests (cljs.test/empty-env :untangled.test.report-components/browser)  'untangled.test.dom-spec)
  ;(run-tests 'untangled.test.events-spec)
  ;(run-tests 'untangled.history-spec)
  ; (run-tests 'untangled.core-spec)
  )

(defn on-load []
  (tr/on-js-reload)
  (run-all-tests)
  )

;(defn pp [form] `(doto ~form (cljs.pprint/pprint)))
;(defn cl [form] `(doto ~form (js/console.log)))
;
;(alter-var-root #'*cljs-data-readers* assoc 'spy pp 'log cl)

