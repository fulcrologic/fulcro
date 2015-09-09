(ns untangled.application)

(defprotocol Application
  (render [this] "Render the current application state")
  (force-refresh [this] "Force a re-render of the current application state")
  (state-changed [this old new] "Internal use. Triggered on state changes.")
  )
