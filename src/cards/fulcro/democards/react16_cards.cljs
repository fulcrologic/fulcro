(ns fulcro.democards.react16-cards
  (:require [devcards.core :as dc]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro make-root]]
            [fulcro.client.primitives :as prim :refer [defsc defui]]
            [goog.object :as gobj]
            [fulcro.client.mutations :as m]))

(defui LegacyThing
  static prim/IQuery
  (query [this] [:v])
  static prim/Ident
  (ident [this props] [:thing/by-id 1])
  static prim/InitialAppState
  (initial-state [this params] {:v 1})
  Object
  (initLocalState [this]
    ;; You can modify `this`:
    (gobj/set this "handleClick" (fn [] (m/set-integer! this :v :value (inc (:v (prim/props this))))))
    {})
  (render [this]
    (let [{:keys [v]} (prim/props this)]
      (dom/div
        (dom/button {:onClick #(prim/set-state! this {:a 1})} "Hi")
        (dom/button {:onClick (.-handleClick this)} (str "Go " v))))))

(defcard-fulcro card
  (make-root LegacyThing {})
  {}
  {:inspect-data true})
