(ns book.bootstrap.components.nav
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [fulcro.client.routing :as routing :refer [defrouter]]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.ui.bootstrap3 :as b]))

(m/defmutation nav-to [{:keys [page]}]
  (action [{:keys [state]}] (swap! state assoc :current-page page)))

(defsc NavRoot [this {:keys [nav current-page]}]
  {
   :initial-state (fn [props] {:current-page :home
                               ; Embed the nav in app state as a child of this component
                               :nav          (b/nav :main-nav :tabs :normal
                                               :home
                                               [(b/nav-link :home "Home" false)
                                                (b/nav-link :other "Other" false)
                                                (b/dropdown :reports "Reports"
                                                  [(b/dropdown-item :report-1 "Report 1")
                                                   (b/dropdown-item :report-2 "Report 2")])])})
   ; make sure to add the join on the same keyword (:nav)
   :query         [:current-page {:nav (prim/get-query b/Nav)}]}
  (render-example "100%" "150px"
    (b/container-fluid {}
      (b/row {}
        (b/col {:xs 12}
          ; render it, use onSelect to be notified when nav changes. Note: `nav-to` is just part of this demo.
          (b/ui-nav nav :onSelect (fn [id] (prim/transact! this `[(nav-to ~{:page id})])))))
      (b/row {}
        (b/col {:xs 12}
          (dom/p #js {} (str "Current page: " current-page)))))))

