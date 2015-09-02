(ns ^:figwheel-always calendar-demo.core
  (:require [figwheel.client :as fw]
            [quiescent.core :as q :include-macros true]
            [quiescent.dom :as d]
            [untangled.state :as qms]
            [untangled.events :as evt]
            [calendar-demo.calendar :as cal]
            )
  (:require-macros [untangled.component :as c]))

(enable-console-print!)

(defn make-visualization []
  {
   :text       "Some Widget with Dates"
   :data       ["A" "B" "C" "D"]
   :start-date (cal/initial-calendar)
   :end-date   (cal/initial-calendar)
   }
  )

(defn random-numbers [] (repeatedly rand))

(defn set-data [visual]
  (assoc visual :data (take 5 (random-numbers)))
  )

(c/defscomponent Visualization
                 [data context]
                 (let [cbb (qms/op-builder context)
                       refresh-data (fn []
                                      (js/setTimeout (cbb set-data) 500))]
                   (d/div {}
                          (d/h2 {} (:text data))
                          ;; TODO: This isn't quite right, as the handler maps WILL have dupes, which will cause things to fail.
                          ;; need to encode the id of the component with the event, and also probably send that along with
                          ;; the notification...
                          (cal/Calendar :start-date context {:picked refresh-data})
                          (cal/Calendar :end-date context {:picked refresh-data})
                          (for [txt (:data data)]
                            (d/div {} txt)
                            )
                          ))
                 )


(defonce app-state
         (atom {
                :scope/vis1         (make-visualization)
                :__figwheel_counter 0
                }))

(q/defcomponent Root [data context]
                (d/div {}
                       (Visualization :scope/vis1 context)
                       )
                )

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
