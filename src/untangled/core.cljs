(ns untangled.core
  (:require [untangled.history :as h]
            [untangled.application :refer [Application Transaction]]
            [untangled.test.report-components :as rc]
            [untangled.test.dom :refer [render-as-dom]]
            [untangled.state :as qms]
            [quiescent.core :as q :include-macros true]
            [untangled.i18n.core :as ic])
  (:require-macros [cljs.test :refer (is deftest run-tests testing)]))

(q/defcomponent Root
                "The root renderer for Untangled. Not for direct use."
                ;:on-mount (fn [... context] (add-watch ic/*loaded-translations* ::locale-loaded (.. do something to re-render ..)))
                ;:on-unmount (fn []  (remove-watch ic/*loaded-translations* ::locale-loaded))
                [state application]
                (let [ui-render (:renderer application)
                      context (qms/root-context application)
                      ]
                  (ui-render :top context)
                  ))

(defrecord UntangledApplication
  [app-state dom-target history renderer test-mode transaction-listeners]
  Application
  (render [this]
    (if test-mode
      (render-as-dom (Root @app-state this))
      (q/render (Root @app-state this) (.getElementById js/document dom-target))))
  (force-refresh [this]
    (swap! app-state #(assoc % :time (js/Date.)))
    (untangled.application/render this)
    )
  (top-context [this] (qms/new-sub-context (qms/root-context this) :top {}))
  (state-changed [this old-state new-state]
    (doseq [listener @transaction-listeners]
      (listener (Transaction. old-state new-state nil)))
    (untangled.application/render this))
  (current-state [this] (-> @app-state :top))
  (current-state [this subpath] (get-in (-> @app-state :top) subpath))
  (add-transaction-listener [this listener] (swap! transaction-listeners #(conj % listener))))

(defn new-application
  "Create a new Untangled application with:
  
  - `ui-render` : A top-level untangled component/renderer
  - `initial-state` : The state that goes with the top-level renderer
  
  Additional optional parameters by name:
  
  - `:target DOM_ID`: Specifies the target DOM element. The default is 'app'
  - `:history n` : Set the history size. The default is 100.
  - `:test-mode boolean`: Put the application in unit test mode. This causes render to return 
  a disconnected DOM fragment instead of actually rendering to visible DOM. Thus, render will 
  *return* the DOM fragment instead of side-effecting it onto the screen.
  - `:view-only boolean`: Put the application in view-only mode. Used by support state viewer. Disables processing of op-builder functions.
  "
  [ui-render initial-state & {:keys [target history test-mode view-only] :or {test-mode false target "app" history 100 view-only false}}]
  (map->UntangledApplication {:app-state             (atom {:top initial-state :time (js/Date.)})
                              :transaction-listeners (atom [])
                              :renderer              ui-render
                              :dom-target            target
                              :history               (atom (h/empty-history history))
                              :test-mode             test-mode
                              :view-only             view-only
                              }))


