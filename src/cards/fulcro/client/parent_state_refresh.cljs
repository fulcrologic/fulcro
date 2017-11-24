(ns fulcro.client.parent-state-refresh
  (:require [devcards.core :as dc]
            [fulcro.client.dom :as dom]
            [fulcro.client.core :as fc :refer [InitialAppState initial-state]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [goog.object]
            [fulcro.client.cards :refer [fulcro-app]]
            [fulcro.client.primitives :as prim :refer [defui]]
            [fulcro.client.mutations :as m]))

(declare ui-leaf)

(defui ^:once Leaf
  static InitialAppState
  (initial-state [clz {:keys [id]}] {:id id :x 1 :y 42})
  static prim/IQuery
  (query [this] [:id :x])
  static prim/Ident
  (ident [this props] [:LEAF (:id props)])
  Object
  (render [this]
    (let [{:keys [id x y]} (prim/props this)]
      (dom/div nil
        (dom/button #js {:onClick (fn [] (prim/set-query! this ui-leaf {:query [:id :x]}))} "Set query to :x")
        (dom/button #js {:onClick (fn [] (prim/set-query! this ui-leaf {:query [:id :y]}))} "Set query to :y")
        (dom/button #js {:onClick (fn [e] (if x
                                            (m/set-value! this :x (inc x))
                                            (m/set-value! this :y (inc y))))}
          (str "Count: " (or x y)))
        " Leaf"))))

(def ui-leaf (prim/factory Leaf))

(defui ^:once Parent
  static InitialAppState
  (initial-state [clz params] {:left  (fc/get-initial-state Leaf {:id :left})
                               :right (fc/get-initial-state Leaf {:id :right})})
  static prim/IQuery
  (query [this] [{:left (prim/get-query ui-leaf)} {:right (prim/get-query ui-leaf)}])

  static prim/Ident
  (ident [this props]
    [:PARENT/by-id :singleton])
  Object
  (initLocalState [this] {:value 2})
  (render [this]
    (let [{:keys [left right]} (prim/props this)
          {:keys [value]} (prim/get-state this)]
      (dom/div nil
        (dom/p nil value)
        (dom/button #js {:onClick #(prim/react-set-state! this {:value (inc value)})} "Modify local state only. A bug would cause children to go back in time!")
        (dom/h4 nil "Left")
        (ui-leaf left)
        (dom/h4 nil "Right")
        (ui-leaf right)))))

(def ui-parent (prim/factory Parent))

(defui ^:once Root
  static InitialAppState
  (initial-state [clz params] {:ui/react-key "A"
                               :parent       (fc/get-initial-state Parent nil)})
  static prim/IQuery
  (query [this] [{:parent (prim/get-query ui-parent)} :ui/react-key])
  Object
  (render [this]
    (let [{:keys [ui/react-key parent] :as props} (prim/props this)]
      (dom/div #js {:key (or react-key)}
        (dom/button #js {:onClick #(prim/transact! this `[:left])} "Run real transaction (no-op)")
        (ui-parent parent)))))

(defcard-fulcro parent-refresh-card
  Root
  {}
  {:inspect-data true})
