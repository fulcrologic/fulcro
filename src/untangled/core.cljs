(ns untangled.core
  (:require [untangled.history :as h]
            [untangled.application]
            [untangled.state :as qms]
            [quiescent.core :as q :include-macros true]
            ))

(q/defcomponent Root
                "The root renderer for Untangled. Not for direct use."
                [state application]
                (let [ui-render (:renderer application)
                      context (qms/root-context application)
                      ]
                  (ui-render :top context)
                  ))

(defrecord UntangledApplication
  [app-state dom-target history renderer]
  untangled.application/Application
  (render [this] (q/render (Root @app-state this) (.getElementById js/document dom-target)))
  (force-refresh [this]
    (swap! app-state #(assoc % :time (js/Date.)))
    (untangled.application/render this)
    )
  (state-changed [this old-state new-state] (untangled.application/render this)))

(defn new-application
  "Create a new Untangled application with:
  
  - `ui-render` : A top-level untangled component/renderer
  - `initial-state` : The state that goes with the top-level renderer
  
  Additional optional parameters by name:
  
  - `:target DOM_ID`: Specifies the target DOM element. The default is 'app'
  - `:history n` : Set the history size. The default is 100.
  "
  [ui-render initial-state & {:keys [target history] :or {target "app" history 100}}]
  (map->UntangledApplication {:app-state  (atom {:top initial-state :time (js/Date.)})
                              :renderer   ui-render
                              :dom-target target
                              :history    (atom (h/empty-history history))
                              }))
