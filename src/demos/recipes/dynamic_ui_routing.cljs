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


(om/defui ^:once Login
  static fc/InitialAppState
  (initial-state [clz params] {r/dynamic-route-key :login :label "LOGIN"})
  static om/Ident
  (ident [this props] [:login :singleton])
  static om/IQuery
  (query [this] [r/dynamic-route-key :label])
  Object
  (render [this]
    (let [{:keys [label]} (om/props this)]
      (dom/div #js {:style #js {:backgroundColor "green"}}
        label))))

(om/defui ^:once NewUser
  static fc/InitialAppState
  (initial-state [clz params] {r/dynamic-route-key :new-user :label "New User"})
  static om/Ident
  (ident [this props] [:new-user :singleton])
  static om/IQuery
  (query [this] [r/dynamic-route-key :label])
  Object
  (render [this]
    (let [{:keys [label]} (om/props this)]
      (dom/div #js {:style #js {:backgroundColor "skyblue"}}
        label))))

(om/defui ^:once Root
  static fc/InitialAppState
  (initial-state [clz params] (merge
                                (r/routing-tree
                                  (r/make-route :main [(r/router-instruction :top-router [:main :singleton])])
                                  (r/make-route :login [(r/router-instruction :top-router [:login :singleton])])
                                  (r/make-route :new-user [(r/router-instruction :top-router [:new-user :singleton])]))
                                {:top-router (fc/get-initial-state r/DynamicRouter {:id :top-router})}))
  static om/IQuery
  (query [this] [:ui/react-key {:top-router (om/get-query r/DynamicRouter)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key top-router]} (om/props this)]
      (dom/div #js {:key react-key}
        ; Sample nav mutations
        (dom/a #js {:onClick #(om/transact! this `[(r/route-to {:handler :main})])} "Main") " | "
        (dom/a #js {:onClick #(om/transact! this `[(r/route-to {:handler :new-user})])} "New User") " | "
        (dom/a #js {:onClick #(om/transact! this `[(r/route-to {:handler :login})])} "Login") " | "
        (r/ui-dynamic-router top-router)))))

; these would happen as a result of module loads:
(defn application-loaded [{:keys [reconciler]}]
  ; Let the dynamic router know that two of the routes are already loaded.
  (om/transact! reconciler `[(r/install-route {:target-kw :new-user :component ~NewUser})
                             (r/install-route {:target-kw :login :component ~Login})
                             (r/route-to {:handler :login})]))
