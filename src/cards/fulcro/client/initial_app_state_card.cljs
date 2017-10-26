(ns fulcro.client.initial-app-state-card
  (:require [devcards.core :as dc]
            [fulcro.client.dom :as dom]
            [fulcro.client.core :as fc :refer [InitialAppState initial-state]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [goog.object]
            [fulcro.client.cards :refer [fulcro-app]]
            [fulcro.client.primitives :as om :refer [defui]]
            [fulcro.client.mutations :as m]))

(defui ^:once ActiveUsersTab
  static InitialAppState
  (initial-state [clz params] {:which-tab :active-users})

  static om/IDynamicQuery
  (dynamic-query [this state] [:which-tab])

  static om/Ident
  (ident [this props]
    [(:which-tab props) :tab])

  Object
  (render [this]))

(def ui-active-users-tab (om/factory ActiveUsersTab))

(m/defmutation set-query [{:keys [factory args]}]
  (action [{:keys [state]}]
    (swap! state om/set-query! factory args)))

(declare ui-leaf)

(defui ^:once Leaf
  static InitialAppState
  (initial-state [clz params] {:x 1 :y 42})
  static om/IDynamicQuery
  (dynamic-query [this state] [:x])
  static om/Ident
  (ident [this props] [:LEAF :ID])
  Object
  (render [this]
    (let [{:keys [x y]} (om/props this)]
      (dom/div nil
        (dom/button #js {:onClick (fn [] (om/transact! this `[(set-query {:factory ~ui-leaf :args {:query [:x]}})]))} "Set query to :x")
        (dom/button #js {:onClick (fn [] (om/transact! this `[(set-query {:factory ~ui-leaf :args {:query [:y]}})]))} "Set query to :y")
        (dom/button #js {:onClick (fn [e] (if x
                                            (m/set-value! this :x (inc x))
                                            (m/set-value! this :y (inc y))))}
          (str "Count: " (or x y)))
        " Leaf"))))

(def ui-leaf (om/factory Leaf))

(defui ^:once HighScoreTab
  static InitialAppState
  (initial-state [clz params] {:which-tab :high-score :leaf (fc/get-initial-state Leaf {})})
  static om/IDynamicQuery
  (dynamic-query [this state] [:which-tab {:leaf (om/get-query ui-leaf state)}])

  static om/Ident
  (ident [this props]
    [(:which-tab props) :tab])
  Object
  (render [this]
    (let [{:keys [leaf]} (om/props this)]
      (dom/div nil
        (dom/button #js {:onClick (fn [e] (om/update-state! this update :count inc))}
          (str "Count: " (om/get-state this :count)))
        "HST"
        (dom/hr nil)
        (ui-leaf leaf)))))

(def ui-high-score-tab (om/factory HighScoreTab))

(defui ^:once Union
  static InitialAppState
  (initial-state [clz params] (initial-state HighScoreTab nil))
  static om/IDynamicQuery
  (dynamic-query [this state] {:active-users (om/get-query ui-active-users-tab state) :high-score (om/get-query ui-high-score-tab state)})
  static om/Ident
  (ident [this props] [(:which-tab props) :tab])
  Object
  (render [this]
    (let [props (om/props this)]
      (case (:which-tab props)
        :active-users (ui-active-users-tab props)
        (ui-high-score-tab props)))))

(def ui-settings-viewer (om/factory Union))

(defui ^:once Root
  static InitialAppState
  (initial-state [clz params] {:ui/react-key "A"
                               :current-tab  (fc/get-initial-state Union nil)})
  static om/IDynamicQuery
  (dynamic-query [this state] [{:current-tab (om/get-query ui-settings-viewer state)}
                               :ui/react-key])
  Object
  (render [this]
    (let [{:keys [ui/react-key current-tab] :as props} (om/props this)]
      (dom/div #js {:key (or react-key)}
        (ui-settings-viewer current-tab)))))

(defcard-fulcro union-initial-app-state
  Root
  {}
  {:inspect-data true})

(comment
  (def q (om/get-query Root))

  (meta q)

  )
