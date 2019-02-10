(ns fulcro.democards.pessimistic-transaction-ws
  (:require
    [nubank.workspaces.core :as ws]
    [nubank.workspaces.model :as wsm]
    [nubank.workspaces.card-types.fulcro :as ct.fulcro]
    [nubank.workspaces.lib.fulcro-portal :as f.portal]
    [fulcro.client.primitives :as prim :refer [defui defsc]]
    [fulcro.client.dom :as dom]
    [fulcro.client.network :as net]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.data-fetch :as df]))

;; example requires running tiny server in comment section of user.clj

(defmutation a [_]
  (remote-a [env] true))

(defmutation b [_]
  (action [env] (js/console.log "B")))

(defmutation c [_]
  (remote-b [env] true))

(defmutation d [_]
  (action [env] (js/console.log "D")))

(defsc Item [this {:keys [:db/id] :as props}]
  {:query         [:db/id]
   :ident         [:item/by-id :db/id]
   :initial-state {:db/id :param/id}}
  (dom/button {:onClick #(prim/ptransact! this `[(a) (b) (c) (d)])} "TODO"))

(ws/defcard ptransact-card
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       Item
     ::f.portal/app  {:networking {:remote-a (net/fulcro-http-remote {:url "/api" :serial? true})
                                   :remote-b (net/fulcro-http-remote {:url "/api" :serial? true})}}
     ::f.portal/wrap-root? true}))
