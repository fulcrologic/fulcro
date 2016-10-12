(ns untangled.client.fancy-defui
  (:require
    [devcards.core :as dc :include-macros true]
    [untangled.client.core :as uc]
    [om.next :as om]
    [om.dom :as dom]
    [untangled.client.ui :as ui :include-macros true]))

(ui/defui ListItem [::ui/DevTools (::ui/DerefFactory {:keyfn :value})]
  Object
  (render [this]
    (dom/li nil
      (:value (om/props this)))))

(ui/defui ThingB [(::ui/BuiltIns {::ui/WithExclamation {:excl "THING B OVERRIDE!"}})]
  Object
  (render [this]
    (dom/div nil
      (dom/ul nil
        (map @ListItem (map hash-map (repeat :value) (range 6)))))))

(ui/defui ThingA
  {:always [::ui/DevTools]
   :prod [(::ui/WithExclamation {:excl "IN PROD MODE"})]
   :dev  [(::ui/WithExclamation {:excl "IN DEV MODE"})]}
  Object
  (render [this]
    (let [{:keys [ui/react-key]} (om/props this)]
      (dom/div #js {:key react-key}
        "Hello World!"
        (@ThingB)))))

(defonce client (atom (uc/new-untangled-test-client)))

(dc/defcard fancy-defui
  "##untangled.client.ui/defui"
  (dc/dom-node
    (fn [_ node]
      (reset! client (uc/mount @client ThingA node)))))
