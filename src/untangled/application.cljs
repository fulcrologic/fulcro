(ns untangled.application)

(defrecord Transaction [old-state new-state reason])

(defprotocol Application
  (render [this] "Render the application. In test mode this returns detached DOM. In normal mode it renders/updates the real DOM.")
  (force-refresh [this] "Force a re-render of the current application state (overriding data change optimizations)")
  (state-changed [this old new] "Internal use. Triggered on state changes.")
  (top-context [this] "Get the top-level rendering context for the application")
  (current-state [this] [this subpath]
                 "Get the current application state from the root (or at the given subpath (sequence of keywords/indices)).
                 
                 This method assumes you know where the data is, and the subpath should therefore be
                 something compatible with clojure's `get-in` method.
                 
                 For example:
                 
                 (current-state application [:todolist :items])
                 ")
  (add-transaction-listener [this listener]
                            "Add a function that will be notified on application state changes.
                            Listener is a function that accepts a transaction as an argument")
  )

(defonce active-applications (atom []))
(defonce rendering (atom false))

(defn render-all
  "Render all active applications. If rendering loop is active, will cause re-renders."
  []
  (doseq [a @active-applications]
    (render a))
  (when @rendering (js/requestAnimationFrame render-all))
  )

(defn add-application
  "Add the given application to the list of applications that will be rendered (when rendering is active). Returns the 
  application added."
  [app]
  (swap! active-applications conj app)
  app)

(defn remove-application
  "Remove the given application from the render loop. This does not remove it from the DOM, it just stops rendering updates."
  [app]
  (swap! active-applications
         (fn [apps]
           (into [] (remove #(= (:dom-target app) (:dom-target %)) apps))
           )))

(defn start-rendering
  "Start a rendering loop that will render all mounted applications at an animation frame rate."
  []
  (if-not @rendering
    (do
      (reset! rendering true)
      (render-all)
      )))

(defn stop-rendering
  "Stop the rendering loop for all mounted applications."
  [] (reset! rendering false))

