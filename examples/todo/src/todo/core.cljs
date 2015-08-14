(ns ^:figwheel-always todo.core
  (:require [figwheel.client :as fw]
            [quiescent.core :as q :include-macros true]
            [quiescent.dom :as d]
            [quiescent-model.state :as qms]
            [quiescent-model.events :as evt]
            [todo.components.todo :refer [Todo make-todolist]]
            [todo.components.todo-item :refer [new-item]]
            )
  (:require-macros [quiescent-model.component :as c]))

(enable-console-print!)

(defonce app-state
         (atom {
                :scope/vis1         (make-todolist [(new-item "Go to store") (new-item "Eat stuff")])
                :__figwheel_counter 0
                }))

;; 12 lines of code for FULL undo!
(defonce undo-history (atom '()))
(defonce is-undo (atom false))
(defn undo []
  (if (not-empty @undo-history)
    (let [last-state (first @undo-history)]
      (reset! is-undo true)
      (swap! undo-history rest)
      (reset! app-state last-state)
      )))

(q/defcomponent Root [data context]
                (d/div {}
                       (d/div {:className "todoapp"}
                              (d/button {:className "undo" :onClick todo.core/undo} "Undo")
                              (Todo :scope/vis1 context)
                              )))

(defn render [data app-state]
  (q/render (Root data (qms/root-scope app-state))
            (.getElementById js/document "app")))

(add-watch app-state ::render
           (fn [_ _ old-state data]
             (if (not @is-undo)
               (swap! undo-history #(cons old-state %)))
             (reset! is-undo false)
             (render data app-state)))

(defn on-js-reload []
  ;; touch app-state to force rerendering
  (swap! app-state update-in [:__figwheel_counter] inc)
  (render @app-state app-state)
  )

(render @app-state app-state)
