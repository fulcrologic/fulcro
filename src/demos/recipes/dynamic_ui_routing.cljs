(ns recipes.dynamic-ui-routing
  (:require [fulcro.client.routing :as r]
            [om.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.core :as fc :refer [InitialAppState initial-state]]
            [fulcro.client.cards :refer-macros [fulcro-app]]
            [fulcro.client.data-fetch :as df]
            [om.next :as om :refer [defui]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.logging :as log]
            [fulcro.client.util :as util]))

(om/defui ^:once Main
  static fc/InitialAppState
  (initial-state [clz params] {:page :main :label "MAIN"})
  static om/Ident
  (ident [this props] [:main :single])
  static om/IQuery
  (query [this] [:page :label])
  Object
  (render [this]
    (let [{:keys [label]} (om/props this)]
      (dom/div #js {:style #js {:backgroundColor "red"}}
        label))))

(om/defui ^:once Login
  static fc/InitialAppState
  (initial-state [clz params] {:page :login :label "LOGIN"})
  static om/Ident
  (ident [this props] [:login :single])
  static om/IQuery
  (query [this] [:page :label])
  Object
  (render [this]
    (let [{:keys [label]} (om/props this)]
      (dom/div #js {:style #js {:backgroundColor "green"}}
        label))))

(om/defui ^:once NewUser
  static fc/InitialAppState
  (initial-state [clz params] {:page :new-user :label "New User"})
  static om/Ident
  (ident [this props] [:new-user :single])
  static om/IQuery
  (query [this] [:page :label])
  Object
  (render [this]
    (let [{:keys [label]} (om/props this)]
      (dom/div #js {:style #js {:backgroundColor "skyblue"}}
        label))))

(defmulti get-component (fn [k] k))

(defmethod get-component :default [k] nil)
(defmethod get-component :main [k] Main)

(defui ^:once DynamicRouter
  static fc/InitialAppState
  (initial-state [clz params] {:id :top-router :current-route (fc/get-initial-state Main {})})
  static om.next/Ident
  (ident [this props] [:fulcro.client.routing.routers/by-id :top-router])
  static om.next/IQuery
  (query [this] [:id {:current-route (om.next/get-query Main)}])
  Object
  (render [this]
    (let [{:keys [current-route]} (om/props this)
          {:keys [page]} current-route
          c       (get-component page)
          factory (when c (om/factory c {:keyfn :page}))]
      (when factory
        (factory current-route)))))

(def ui-router (om/factory DynamicRouter))

(m/defmutation route-to [{:keys [router-id page]}]
  (action [{:keys [state reconciler]}]
    (let [router (om/ref->any reconciler [r/routers-table router-id])
          target (get-component page)]
      (js/console.log :r router :c target)
      (when router
        (swap! state assoc-in [r/routers-table router-id :current-route] [page :single])
        (om/set-query! router {:query [:id {:current-route (om/get-query target)}]})))))

(om/defui ^:once Root
  static fc/InitialAppState
  (initial-state [clz params] {:top-router (fc/get-initial-state DynamicRouter {})})
  static om/IQuery
  (query [this] [:ui/react-key {:top-router (om/get-query DynamicRouter)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key top-router]} (om/props this)]
      (dom/div #js {:key react-key}
        ; Sample nav mutations
        (dom/a #js {:onClick #(om/transact! this `[(route-to {:router-id :top-router :page :main})])} "Main") " | "
        (dom/a #js {:onClick #(om/transact! this `[(route-to {:router-id :top-router :page :new-user})])} "New User") " | "
        (dom/a #js {:onClick #(om/transact! this `[(route-to {:router-id :top-router :page :login})])} "Login") " | "
        (ui-router top-router)))))

(defn add-route-initial-state [state page component]
  (let [tree-state       {:tmp/new-route (fc/get-initial-state component nil)}
        query            [{:tmp/new-route (om/get-query component)}]
        normalized-state (-> (om/tree->db query tree-state true)
                           (dissoc :tmp/new-route))]
    (defmethod get-component page [k] component)
    (swap! state util/deep-merge normalized-state)))

(defmutation install-route [{:keys [page component]}]
  (action [{:keys [state]}]
    (add-route-initial-state state page component)))

; these would happen as a result of module loads:
(defn application-loaded [{:keys [reconciler]}]
  (om/transact! reconciler `[(install-route {:page :new-user :component ~NewUser})])
  (om/transact! reconciler `[(install-route {:page :login :component ~Login})]))
