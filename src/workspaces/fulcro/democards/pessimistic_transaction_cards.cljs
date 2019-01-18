(ns fulcro.democards.pessimistic-transaction-cards
  (:require
    [devcards.core :as dc]
    [fulcro.client :as fc]
    [fulcro.server :as server]
    [fulcro.client.cards :refer [defcard-fulcro make-root]]
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

(defcard-fulcro ptransact-card
  (make-root Item {})
  {}
  {:fulcro {:networking {:remote-a (net/fulcro-http-remote {:url "/api" :serial? true})
                         :remote-b (net/fulcro-http-remote {:url "/api" :serial? true})}}})
