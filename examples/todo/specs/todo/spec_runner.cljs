(ns ^:figwheel-always todo.spec-runner
  (:require
    [cljs.test :as test :include-macros true :refer [report]]
    [todo.core]
    [figwheel.client :as fw]))

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
             (change-favicon-to-color "#0d0"))) ;;<<-- change color

(defn runner []
      (test/run-tests
        'todo.todo-spec))
