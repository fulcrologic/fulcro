(ns book.simple-router-1
  (:require [fulcro.client.routing :as r :refer-macros [defrouter]]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
            [fulcro.client.data-fetch :as df]
            [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.mutations :as m]))

(defsc Index [this props]
  {:query         [:db/id :router/page]
   :ident         [:router/page :db/id]
   :initial-state {:db/id 1 :router/page :index}}
  (dom/div nil "Index Page"))

(defsc Settings [this props]
  {:query         [:db/id :router/page]
   :ident         [:router/page :db/id]
   :initial-state {:db/id 1 :router/page :settings}}
  (dom/div nil "Settings Page"))

(defrouter RootRouter :root/router
  [:router/page :db/id]
  :index Index
  :settings Settings)

(def ui-root-router (prim/factory RootRouter))

(defsc Root [this {:keys [router]}]
  {:initial-state (fn [p] {:router (prim/get-initial-state RootRouter {})})
   :query         [{:router (prim/get-query RootRouter)}]}
  (dom/div nil
    (dom/a #js {:onClick #(prim/transact! this `[(r/set-route! {:router :root/router :target [:index 1]})])} "Index") " | "
    (dom/a #js {:onClick #(prim/transact! this `[(r/set-route! {:router :root/router :target [:settings 1]})])} "New User") " | "
    (ui-root-router router)))


