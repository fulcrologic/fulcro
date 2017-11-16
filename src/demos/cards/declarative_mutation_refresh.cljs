(ns cards.declarative-mutation-refresh
  (:require [fulcro.client.dom :as dom]
            [fulcro.client.primitives :as prim :refer [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.core :as fc :refer [defsc]]
            [fulcro.client.data-fetch :as df]))

(defmutation ping-left [params]
  (action [{:keys [state]}]
    (swap! state update-in [:left/by-id 5 :left/value] inc))
  (refresh [env] [:left/value]))

(declare Right)

(defmutation ping-right [params]
  (remote [{:keys [state ast]}]
    (m/returning ast state Right))
  (refresh [env] [:right/value]))

(defsc Left [this {:keys [db/id left/value]} _ _]
  {:query         [:db/id :left/value]
   :initial-state {:db/id 5 :left/value 42}
   :ident         [:left/by-id :db/id]}
  (dom/div (clj->js {:style {:float :left}})
    (dom/button #js {:onClick #(prim/transact! this `[(ping-right {})])} "Ping Right")
    value))

(def ui-left (prim/factory Left {:keyfn :db/id}))

(defsc Right [this {:keys [db/id right/value]} _ _]
  {:query         [:db/id :right/value]
   :initial-state {:db/id 1 :right/value 99}
   :ident         [:right/by-id :db/id]}
  (dom/div (clj->js {:style {:float :right}})
    (dom/button #js {:onClick #(prim/transact! this `[(ping-left {})])} "Ping Left")
    value))

(def ui-right (prim/factory Right {:keyfn :db/id}))

(defsc Root [this {:keys [ui/react-key left right]} _ _]
  {:query         [{:left (prim/get-query Left)}
                   :ui/react-key
                   {:right (prim/get-query Right)}]
   :initial-state {:left {} :right {}}}
  (dom/div (clj->js {:key react-key :style {:width "500px" :height "50px"}})
    (ui-left left)
    (ui-right right)))

(defcard-fulcro mutation-refresh Root)
