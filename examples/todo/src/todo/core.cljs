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

(q/defcomponent Root [data context] (d/div { :className "todoapp"}
                                           (Todo :scope/vis1 context)
                                           ))

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
