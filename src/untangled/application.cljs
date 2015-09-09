(ns untangled.application
  (:require [untangled.history :as h]
            [untangled.state :as qms]
            [quiescent.core :as q :include-macros true]
            untangled.core
            ))


(defprotocol Application
  (render [this] "Render the current application state")
  (force-refresh [this] "Force a re-render of the current application state")
  (state-changed [this old new] "Internal use. Triggered on state changes.")
  )

