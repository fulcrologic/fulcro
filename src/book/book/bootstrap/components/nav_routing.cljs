(ns book.bootstrap.components.nav-routing
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [fulcro.client.routing :as routing :refer [defrouter]]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc HomeScreen [this props]
  {:initial-state {:screen-type :home}
   :query         [:screen-type]}
  (dom/div nil "HOME SCREEN"))

(defsc OtherScreen [this props]
  {:initial-state {:screen-type :other}
   :query         [:screen-type]}
  (dom/div nil "OTHER SCREEN"))

(defrouter MainRouter :main-router
  (ident [this props] [(:screen-type props) :singleton])
  :home HomeScreen
  :other OtherScreen)

(def ui-router (prim/factory MainRouter))

(m/defmutation select-tab
  "Select the given tab"
  [{:keys [tab]}]
  (action [{:keys [state]}]
    (swap! state (fn [s]
                   (-> s
                     (routing/set-route :main-router [tab :singleton])
                     (b/set-active-nav-link* :main-nav tab))))))

(defsc RouterRoot [this {:keys [router nav]}]
  {:initial-state (fn [p] {
                           :nav    (b/nav :main-nav :tabs :normal
                                     :home
                                     [(b/nav-link :home "Home" false) (b/nav-link :other "Other" false)])
                           :router (prim/get-initial-state MainRouter {})})
   :query         [{:router (prim/get-query MainRouter)} {:nav (prim/get-query b/Nav)}]}
  (render-example "100%" "150px"
    (b/container-fluid {}
      (b/row {}
        (b/col {:xs 12} (b/ui-nav nav :onSelect #(prim/transact! this `[(select-tab ~{:tab %})]))))
      (b/row {}
        (b/col {:xs 12} (ui-router router))))))


