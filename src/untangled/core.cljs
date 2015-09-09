(ns untangled.core
  (:require [untangled.history :as h]
            [untangled.application]
            [untangled.test.report-components :as rc]
            [untangled.state :as qms]
            [quiescent.core :as q :include-macros true]
            [cljs.test :as test]
            )
  (:require-macros [cljs.test :refer (run-tests)])
  )

(defprotocol ITest
  (begin-namespace [this name] "Tests are reporting the start of a namespace")
  )

(q/defcomponent Root
                "The root renderer for Untangled. Not for direct use."
                [state application]
                (let [ui-render (:renderer application)
                      context (qms/root-context application)
                      ]
                  (ui-render :top context)
                  ))


(defn run-all-tests [namespaces]
  (run-tests (cljs.test/empty-env :untangled.test.report-components/browser) 'untangled.test.dom-spec)
  )

(defrecord TestSuite
  [app-state dom-target history renderer is-undo namespaces]
  IApplication
  (render [this]
    (do
      (run-all-tests namespaces)
      (q/render (Root @app-state this)
                (.getElementById js/document dom-target))))

  (force-refresh [this] (swap! app-state #(assoc % :time (js/Date.))))

  (state-changed [this old-state new-state] (render this))
  ITest
  (begin-namespace [this name]
    (swap! app-state #(assoc % :current-namespace name))
    )
  )

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

(defn new-test-suite
  "Create a new Untangled application with:

  - `:target DOM_ID`: Specifies the target DOM element. The default is 'app'\n


    - `target` :
  - `initial-state` : The state that goes with the top-level renderer

  Additional optional parameters by name:

  - `:history n` : Set the history size. The default is 100.
  "
  [& { :keys [target namespaces] :or {target "app" namespaces []} }]
  (let [app (map->TestSuite {:app-state  (atom {:top (rc/make-testreport) :test-level 0 :current-namespace ""  :time (js/Date.)})
                               :renderer   rc/TestReport
                               :dom-target target
                               :namespaces namespaces
                               :history    (atom (h/empty-history 1))
                               :is-undo    (atom false)
                               })]
    (add-watch (:app-state app) ::render (fn [_ _ old-state new-state] (state-changed app old-state new-state)))
    app
    ))

