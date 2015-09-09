(ns untangled.core
  (:require [untangled.history :as h]
            [untangled.state :as qms]
            [quiescent.core :as q :include-macros true]
            ))

(defprotocol IApplication
  (render [this] "Render the current application state")
  (force-refresh [this] "Force a re-render of the current application state")
  (state-changed [this old new] "Internal use. Triggered on state changes.")
  )

(q/defcomponent Root
                "The root renderer for Untangled. Not for direct use."
                [state application]
                (let [ui-render (:renderer application)
                      state-atom (:app-state application)
                      context (qms/root-scope state-atom)
                      ]
                  (ui-render :top context)
                  ))

;; Represents an Untangle application (rendered to some part of a DOM)
(defrecord Application
  [app-state dom-target history renderer is-undo]
  IApplication
  (render [this]
    (q/render (Root @app-state this)
              (.getElementById js/document dom-target)))

  (force-refresh [this] (swap! app-state #(assoc % :time (js/Date.))))

  (state-changed [this old-state new-state]
    (swap! history #(h/record % (h/new-point-in-time old-state)))
    (render this)))

(defn new-application
  "Create a new Untangled application with:
  
  - `ui-render` : A top-level untangled component/renderer
  - `initial-state` : The state that goes with the top-level renderer
  
  Additional optional parameters by name:
  
  - `:target DOM_ID`: Specifies the target DOM element. The default is 'app'
  - `:history n` : Set the history size. The default is 100.
  "
  [ui-render initial-state & { :keys [target history] :or {target "app" history 100} }]
  (let [app (map->Application {:app-state  (atom {:top initial-state :time (js/Date.)})
                               :renderer   ui-render
                               :dom-target target
                               :history    (atom (h/empty-history history))
                               :is-undo    (atom false)
                               })]
    (add-watch (:app-state app) ::render (fn [_ _ old-state new-state] (state-changed app old-state new-state)))
    app
    ))
