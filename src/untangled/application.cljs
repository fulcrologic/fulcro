(ns untangled.application)

(defprotocol Application
  (render [this] "Render the current application state")
  (force-refresh [this] "Force a re-render of the current application state")
  (state-changed [this old new] "Internal use. Triggered on state changes.")
  (top-context [this] "Get the top-level rendering context for the application")
  (current-state [this] [this subpath] 
                 "Get the current application state from the root (or at the given subpath (sequence of keywords/indices)).
                 
                 This method assumes you know where the data is, and the subpath should therefore be
                 something compatible with clojure's `get-in` method.
                 
                 For example:
                 
                 (current-state application [:todolist :items])
                 ")
  )
