(ns untangled.client.initial-app-state-card
  (:require [devcards.core :as dc :refer-macros [defcard]]
            [om.dom :as dom]
            [untangled.client.core :as uc :refer [InitialAppState initial-state]]
            [untangled.client.cards :refer [untangled-app]]
            [om.next :as om :refer [defui]]))

(defui ^:once ActiveUsersTab
  static InitialAppState
  (initial-state [clz params] {:which-tab :active-users})

  static om/IQuery
  (query [this] [:which-tab])

  static om/Ident
  (ident [this props]
    [(:which-tab props) :tab])

  Object
  (render [this]))

(def ui-active-users-tab (om/factory ActiveUsersTab))

(defui ^:once HighScoreTab
  static InitialAppState
  (initial-state [clz params] {:which-tab :high-score})
  static om/IQuery
  (query [this] [:which-tab])

  static om/Ident
  (ident [this props]
    [(:which-tab props) :tab])
  Object
  (render [this]))

(def ui-high-score-tab (om/factory HighScoreTab))

(defui ^:once Union
  static InitialAppState
  (initial-state
    [clz params]
    (initial-state HighScoreTab nil))
  static om/IQuery
  (query [this]
    {:active-users (om/get-query ActiveUsersTab)
     :high-score   (om/get-query HighScoreTab)})

  static om/Ident
  (ident [this props] [(:which-tab props) :tab]))

(def ui-settings-viewer (om/factory Union))

(defui ^:once Root
  static InitialAppState
  (initial-state [clz params] {:ui/react-key "A"
                               :current-tab  (initial-state Union nil)})
  static om/IQuery
  (query [this] [{:current-tab (om/get-query Union)}
                 :ui/react-key])
  Object
  (render [this]
    (let [{:keys [ui/react-key] :as props} (om/props this)]
      (dom/div #js {:key (or react-key)} (str react-key)))))

(dc/defcard union-initial-app-state
  ""
  (untangled-app Root)
  {}
  {:inspect-data true})

