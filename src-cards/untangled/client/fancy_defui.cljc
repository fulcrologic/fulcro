(ns untangled.client.fancy-defui
  (:require
    #?@(:cljs
         ([devcards.core :as dc :include-macros true]
          [untangled.client.core :as uc]))
    [om.next :as om]
    [om.dom :as dom]
    [untangled.client.ui :as ui #?@(:cljs (:include-macros true))]
    [untangled.client.xforms :as xf]))

(ui/defui ListItem [ui/DevTools ui/DerefFactory]
  static Defui (factory-opts [] {:keyfn :value})
  Object
  (render [this]
    (dom/li nil
      (:value (om/props this)))))

(ui/defui ThingB [ui/DevTools ui/DerefFactory]
  Object
  (render [this]
    (dom/div nil
      (dom/ul nil
        (map @ListItem (map hash-map (repeat :value) (range 5)))))))

(ui/defui ThingA [ui/DevTools ui/DerefFactory xf/with-exclamation]
  Object
  (render [this]
    (let [{:keys [ui/react-key]} (om/props this)]
      (dom/div #js {:key react-key}
        "Hello World!"
        (@ThingB)))))

#?(:cljs (defonce client (atom (uc/new-untangled-test-client))))

#?(:cljs
    (dc/defcard fancy-defui
      "##untangled.client.ui/defui"
      (dc/dom-node
        (fn [_ node]
          (reset! client (uc/mount @client ThingA node))))))
