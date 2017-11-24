(ns fulcro.client.initial-app-state-card
  (:require [devcards.core :as dc]
            [fulcro.client.dom :as dom]
            [fulcro.client.core :as fc :refer [InitialAppState initial-state]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [goog.object]
            [fulcro.client.cards :refer [fulcro-app]]
            [fulcro.client.primitives :as prim :refer [defui]]
            [fulcro.client.mutations :as m]))

(defui ^:once ActiveUsersTab
  static InitialAppState
  (initial-state [clz params] {:which-tab :active-users})

  static prim/IQuery
  (query [this] [:which-tab])

  static prim/Ident
  (ident [this props]
    [(:which-tab props) :tab])

  Object
  (render [this]))

(def ui-active-users-tab (prim/factory ActiveUsersTab))

(declare ui-leaf)

(defui ^:once Leaf
  static InitialAppState
  (initial-state [clz params] {:x 1 :y 42})
  static prim/IQuery
  (query [this] [:x])
  static prim/Ident
  (ident [this props] [:LEAF :ID])
  Object
  (render [this]
    (let [{:keys [x y]} (prim/props this)]
      (dom/div nil
        (dom/button #js {:onClick (fn [] (prim/set-query! this ui-leaf {:query [:x]}))} "Set query to :x")
        (dom/button #js {:onClick (fn [] (prim/set-query! this ui-leaf {:query [:y]}))} "Set query to :y")
        (dom/button #js {:onClick (fn [e] (if x
                                            (m/set-value! this :x (inc x))
                                            (m/set-value! this :y (inc y))))}
          (str "Count: " (or x y)))
        " Leaf"))))

(def ui-leaf (prim/factory Leaf))

(defui ^:once HighScoreTab
  static InitialAppState
  (initial-state [clz params] {:which-tab :high-score :leaf (fc/get-initial-state Leaf {})})
  static prim/IQuery
  (query [this] [:which-tab {:leaf (prim/get-query ui-leaf)}])

  static prim/Ident
  (ident [this props]
    [(:which-tab props) :tab])
  Object
  (render [this]
    (let [{:keys [leaf]} (prim/props this)]
      (dom/div nil
        (dom/button #js {:onClick (fn [e] (prim/update-state! this update :count inc))}
          (str "Count: " (prim/get-state this :count)))
        "HST"
        (dom/hr nil)
        (ui-leaf leaf)))))

(def ui-high-score-tab (prim/factory HighScoreTab))

(defui ^:once Union
  static InitialAppState
  (initial-state [clz params] (initial-state HighScoreTab nil))
  static prim/IQuery
  (query [this] {:active-users (prim/get-query ui-active-users-tab) :high-score (prim/get-query ui-high-score-tab)})
  static prim/Ident
  (ident [this props] [(:which-tab props) :tab])
  Object
  (render [this]
    (let [props (prim/props this)]
      (case (:which-tab props)
        :active-users (ui-active-users-tab props)
        (ui-high-score-tab props)))))

(def ui-settings-viewer (prim/factory Union))

(defui ^:once Root
  static InitialAppState
  (initial-state [clz params] {:ui/react-key "A"
                               :current-tab  (fc/get-initial-state Union nil)})
  static prim/IQuery
  (query [this] [{:current-tab (prim/get-query ui-settings-viewer)}
                 :ui/react-key])
  Object
  (render [this]
    (let [{:keys [ui/react-key current-tab] :as props} (prim/props this)]
      (dom/div #js {:key (or react-key)}
        (ui-settings-viewer current-tab)))))

(defcard-fulcro union-initial-app-state
  Root
  {}
  {:inspect-data true})

(comment
  (def q (prim/get-query Root))

  (meta q)

  )
