(ns book.simple-router-2
  (:require [fulcro.client.routing :as r :refer-macros [defsc-router]]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
            [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.mutations :as m]))

(defsc Index [this {:keys [router/page db/id]}]
  {:query         [:db/id :router/page]
   :ident         (fn [] [page id])
   :initial-state {:db/id 1 :router/page :PAGE/index}}
  (dom/div "Index Page"))

(defsc EmailSettings [this {:keys [db/id router/page]}]
  {:query         [:db/id :router/page]
   :ident         (fn [] [page id])
   :initial-state {:db/id 1 :router/page :PAGE/email}}
  (dom/div "Email Settings Page"))

(defsc ColorSettings [this {:keys [db/id router/page]}]
  {:query         [:db/id :router/page]
   :ident         (fn [] [page id])
   :initial-state {:db/id 1 :router/page :PAGE/color}}
  (dom/div "Color Settings"))

(defsc-router SettingsRouter [this {:keys [router/page db/id]}]
  {:router-id      :settings/router
   :ident          (fn [] [page id])
   :router-targets {:PAGE/email EmailSettings
                    :PAGE/color ColorSettings}
   :default-route  EmailSettings}
  (dom/div "Bad route"))

(def ui-settings-router (prim/factory SettingsRouter))

(defsc Settings [this {:keys [router/page db/id subpage]}]
  {:query         [:db/id :router/page {:subpage (prim/get-query SettingsRouter)}]
   :ident         (fn [] [page id])
   :initial-state (fn [p]
                    {:db/id       1
                     :router/page :PAGE/settings
                     :subpage     (prim/get-initial-state SettingsRouter {})})}
  (dom/div
    (dom/a {:onClick #(prim/transact! this
                        `[(r/set-route {:router :settings/router
                                        :target [:PAGE/email 1]})])} "Email") " | "
    (dom/a {:onClick #(prim/transact! this
                        `[(r/set-route {:router :settings/router
                                        :target [:PAGE/color 1]})])} "Colors")
    (js/console.log :p (prim/props this))
    (ui-settings-router subpage)))

(defsc-router RootRouter [this {:keys [router/page db/id]}]
  {:router-id      :root/router
   :ident          (fn [] [page id])
   :default-route  Index
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


