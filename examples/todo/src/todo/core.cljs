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

(declare undo)

;; top-level application state. creates nested scopes of local reasoning
(defonce app-state
         (atom {
                :todo-list         (make-todolist [(new-item "Go to store") (new-item "Eat stuff")])
                :__figwheel_counter 0
                }))

; Top-level renderer
(q/defcomponent Root [data context]
                (d/div {}
                       (d/div {:className "todoapp"}
                              (d/button {:className "undo" :onClick todo.core/undo} "Undo")
                              (Todo :todo-list context)
                              )))

; The top-level render call: Renders Root into id="app" component in HTML
(defn render [data app-state]
  (q/render (Root data (qms/root-scope app-state))
            (.getElementById js/document "app")))

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

; Cause re-render when app state changes. Also a quick hack for undo history...these two can be separated (2 watches)
(add-watch app-state ::render
           (fn [_ _ old-state new-state]
             (if (not @is-undo)
               (swap! undo-history #(cons old-state %)))
             (reset! is-undo false)
             (render new-state app-state)))

(defn on-js-reload [] ; Figwheel...on hot code reload, force a re-render
  ;; touch app-state to force rerendering
  (swap! app-state update-in [:__figwheel_counter] inc)
  (render @app-state app-state)
  )

(render @app-state app-state) ; figwheel...render on initial load
