(ns todomvc-pure.app
  "TodoMVC Pure - Entry point for CLJS with Replicant integration.

   This demonstrates how to use pure Fulcro components with Replicant
   for DOM rendering, completely bypassing React and NPM dependencies.

   Usage:
   1. Include Replicant in your dependencies
   2. Call (init) to start the application
   3. The app will render to the #app element in your HTML

   Note: This example shows the integration pattern. In production,
   you would typically use a build tool like shadow-cljs to compile
   and bundle the application."
  (:require
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.networking.http-remote :as http]
    [com.fulcrologic.fulcro.pure.replicant :refer [fulcro-replicant-app mount!]]
    [fulcro.inspect.tool :as it]
    [goog.dom :as gdom]
    [todomvc-pure.ui :as ui]))

;; =============================================================================
;; Application State
;; =============================================================================

(defonce app-atom (atom nil))

(defn create-app
  "Create and configure the pure Fulcro application."
  []
  (fulcro-replicant-app {:remotes {:remote (http/fulcro-http-remote {})}}))

(defn ^:export init
  "Initialize the TodoMVC Pure application.
   Call this from your HTML page or shadow-cljs init hook."
  []
  (let [app (create-app)]
    (reset! app-atom app)
    (it/add-fulcro-inspect! app)
    ;; Force initial render
    (mount! app ui/Root (gdom/getElement "app"))
    ;(df/load! app [:list/id 1] ui/TodoList)
    (js/console.log "TodoMVC Pure initialized!")
    app))

(defn ^:dev/after-load refresh
  "Called after hot code reload. Re-renders the app."
  []
  (js/console.log "Hot reload - refreshing...")
  (when-let [app @app-atom]
    (mount! app ui/Root (gdom/getElement "app"))))
