(ns book.simple-router-1
  (:require [fulcro.client.routing :as r :refer-macros [defsc-router]]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
            [fulcro.client.data-fetch :as df]
            [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.mutations :as m]))

(defsc Index [this {:keys [db/id router/page]}]
  {:query         [:db/id :router/page]
   :ident         (fn [] [page id])                         ; IMPORTANT! Look up both things, don't use the shorthand for idents on screens!
   :initial-state {:db/id 1 :router/page :PAGE/index}}
  (dom/div nil "Index Page"))

(defsc Settings [this {:keys [db/id router/page]}]
  {:query         [:db/id :router/page]
   :ident         (fn [] [page id])
   :initial-state {:db/id 1 :router/page :PAGE/settings}}
  (dom/div "Settings Page"))

(defsc-router RootRouter [this {:keys [router/page db/id]}]
  {:router-id      :root/router
   :default-route  Index
   :ident          (fn [] [page id])
   :router-targets {:PAGE/index    Index
                    :PAGE/settings Settings}}
  (dom/div "Bad route"))

(def ui-root-router (prim/factory RootRouter))

(defsc Root [this {:keys [router]}]
  {:initial-state (fn [p] {:router (prim/get-initial-state RootRouter {})})
   :query         [{:router (prim/get-query RootRouter)}]}
  (dom/div
    (dom/a {:onClick #(prim/transact! this
                        `[(r/set-route {:router :root/router
                                        :target [:PAGE/index 1]})])} "Index") " | "
    (dom/a {:onClick #(prim/transact! this
                        `[(r/set-route {:router :root/router
                                        :target [:PAGE/settings 1]})])} "Settings")
    (ui-root-router router)))


