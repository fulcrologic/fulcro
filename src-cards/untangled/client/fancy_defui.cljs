(ns untangled.client.fancy-defui
  (:require
    [devcards.core :as dc :include-macros true]
    [om.next :as om]
    [om.dom :as dom]
    [untangled.client.core :as uc]
    [untangled.client.ui :as ui :include-macros true]))

(ui/defui ThingB
  Object
  (render [this]
    (dom/div nil
      (dom/button #js {:onClick #(js/alert "Try pressing ?")}
        "Click me!"))))

(ui/defui ThingA
  Object
  (render [this]
    (let [{:keys [ui/react-key]} (om/props this)]
      (dom/div #js {:key react-key}
        "Hello world!"
        (@ThingB)))))

(defonce client (atom (uc/new-untangled-test-client)))

(dc/defcard fancy-defui
  "##untangled.client.ui/defui"
  (dc/dom-node
    (fn [_ node]
      (reset! client (uc/mount @client ThingA node)))))
