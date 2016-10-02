(ns untangled.client.fancy-defui
  (:require
    [devcards.core :as dc :include-macros true]
    [om.next :as om]
    [om.dom :as dom]
    [untangled.client.ui :as ui :include-macros true]))

(ui/defui ThingB {}
  Object
  (render [this]
    (dom/div nil
      (dom/button #js
        {:onClick #(js/alert "Try pressing ?")}
        "Click me!"))))

(ui/defui ThingA {}
  Object
  (render [this]
    (dom/div nil
      "Hello World!"
      (@ThingB))))

(dc/defcard fancy-defui
  "##untangled.client.ui/defui"
  (@ThingA {}))
