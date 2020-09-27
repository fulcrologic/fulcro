(ns com.fulcrologic.fulcro.issues.issue431-computed-sync-tx-cards
  (:require
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]
    [com.fulcrologic.fulcro.dom :refer [div ul li p h3 button b p]]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.rendering.ident-optimized-render :as ior]
    [taoensso.timbre :as log]))

(defsc Item
  [this {:item/keys [name]}]
  {:ident         :item/id
   :query         [:item/id :item/name :ui/clicked?]
   :initial-state {:item/id 1 :item/name "Item 1" :ui/clicked? false}
   :use-hooks?    true}
  (let [computed-prop (comp/get-computed this :computed-prop)]
    (log/info :computed-prop computed-prop)
    (li
      (button {:onClick (fn []
                          (log/info "Computed prop from callback" computed-prop)
                          #_(m/toggle!! this :ui/clicked?)
                          ;; The single ! variation works
                          (m/toggle! this :ui/clicked?))}
        name))))

(def ui-item (comp/factory Item {:keyfn :item/id}))

(defsc Root [_this {:root/keys [items]}]
  {:query         [{:root/items (comp/get-query Item)}]
   :initial-state {:root/items [{}]}}
  (div
    (p "Updating a child component that sets use-hooks? to true with transact!! makes it lose the computed props.")
    (p "Click on the button and see what happens to Item's computed props.")
    (ul
      (map #(ui-item (comp/computed % {:computed-prop :anything})) items))))

(ws/defcard form-pre-merge-sample
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? false
     ::ct.fulcro/root       Root
     ::ct.fulcro/app {:optimized-render! ior/render!}}))