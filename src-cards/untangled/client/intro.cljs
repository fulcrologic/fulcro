(ns untangled.client.intro
  (:require
    [devcards.core :as rc :refer-macros [defcard]]
    [om.dom :as dom]))

(defcard intro-card
  "#Intro to Devcards!"
  (dom/div nil "Hello from devcards & om!"))
