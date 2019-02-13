(ns fulcro.democards.routing-ws
  (:require
    [nubank.workspaces.core :as ws]
    [nubank.workspaces.model :as wsm]
    [nubank.workspaces.card-types.fulcro :as ct.fulcro]
    [nubank.workspaces.lib.fulcro-portal :as f.portal]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.routing :as r :refer [defsc-router]]))

(defsc A [this {:keys [db/id screen] :as props}]
  {:query         [:db/id :screen]
   :ident         (fn [] [screen id])
   :initial-state {:db/id 1 :screen :A}}
  (dom/div nil "A"))

(defsc B [this {:keys [db/id screen] :as props}]
  {:query         [:db/id :screen]
   :ident         (fn [] [screen id])
   :initial-state {:db/id 1 :screen :B}}
  (dom/div nil "B"))

(defsc-router TopRouter [this {:keys [screen db/id]}]
  {:router-id         :top-router
   :ident             (fn [] [screen id])
   :default-route     A
   :router-targets    {:A A
                       :B B}
   :componentDidMount (fn [] (js/console.log :router-mounted this))
   }
  (dom/div "Bummer. Your route sucks!"))

(def ui-router (prim/factory TopRouter))

(defsc Switcher [this {:keys [router] :as props}]
  {:query         [{:router (prim/get-query TopRouter)}]
   :initial-state {:router {}}}
  (dom/div
    (dom/button {:onClick #(prim/transact! this `[(r/set-route {:router :top-router :target [:A 1]})])} "Go to A")
    (dom/button {:onClick #(prim/transact! this `[(r/set-route {:router :top-router :target [:B 1]})])} "Go to B")
    (ui-router router)))

(ws/defcard router-card
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       Switcher }))
