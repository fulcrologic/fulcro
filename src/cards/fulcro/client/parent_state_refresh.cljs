(ns fulcro.client.parent-state-refresh
  (:require [devcards.core :as dc]
            [fulcro.client.dom :as dom]
            [fulcro.client.core :as fc :refer [InitialAppState initial-state]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [goog.object]
            [fulcro.client.cards :refer [fulcro-app]]
            [fulcro.client.primitives :as prim :refer [defui]]
            [fulcro.client.mutations :as m]))

(m/defmutation set-query [{:keys [factory args]}]
  (action [{:keys [state]}]
    (swap! state prim/set-query* factory args)))

(declare ui-leaf)

(defui ^:once Leaf
  static InitialAppState
  (initial-state [clz {:keys [id]}] {:id id :x 1 :y 42})
  static prim/IDynamicQuery
  (dynamic-query [this state] [:id :x])
  static prim/Ident
  (ident [this props] [:LEAF (:id props)])
  Object
  (render [this]
    (let [{:keys [id x y]} (prim/props this)]
      (js/console.log :LEAF-RENDER id)
      (dom/div nil
        (dom/button #js {:onClick (fn [] (prim/transact! this `[(set-query {:factory ~ui-leaf :args {:query [:id :x]}})]))} "Set query to :x")
        (dom/button #js {:onClick (fn [] (prim/transact! this `[(set-query {:factory ~ui-leaf :args {:query [:id :y]}})]))} "Set query to :y")
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
  static prim/IDynamicQuery
  (dynamic-query [this state] [{:left (prim/get-query ui-leaf state)} {:right (prim/get-query ui-leaf state)}])

  static prim/Ident
  (ident [this props]
    [:PARENT/by-id :singleton])
  Object
  (initLocalState [this] {:value 2})
  (render [this]
    (let [{:keys [left right]} (prim/props this)
          {:keys [value]} (prim/get-state this)]
      (js/console.log :PARENT_RENDER :left left :right right)
      (dom/div nil
        (dom/p nil value)
        (dom/button #js {:onClick #(prim/set-state! this {:value (inc value)})} "Bump!")
        (dom/h4 nil "Left")
        (ui-leaf left)
        (dom/h4 nil "Right")
        (ui-leaf right)))))

(def ui-parent (prim/factory Parent))

(defui ^:once Root
  static InitialAppState
  (initial-state [clz params] {:ui/react-key "A"
                               :parent       (fc/get-initial-state Parent nil)})
  static prim/IDynamicQuery
  (dynamic-query [this state] [{:parent (prim/get-query ui-parent state)} :ui/react-key])
  Object
  (render [this]
    (let [{:keys [ui/react-key parent] :as props} (prim/props this)]
      (dom/div #js {:key (or react-key)}
        (ui-parent parent)))))

(defcard-fulcro parent-refresh-card
  Root
  {}
  {:inspect-data true})
