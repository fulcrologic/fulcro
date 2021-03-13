(ns com.fulcrologic.fulcro.cards.composition-cards
  (:require
    [nubank.workspaces.card-types.react :as ct.react]
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]
    [com.fulcrologic.fulcro-css.localized-dom :as dom]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    ["react" :as react]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]))

(defsc Counter [this props]
  {:query         [:counter/id :counter/n]
   :ident         :counter/id
   :initial-state {:counter/id :param/id
                   :counter/n  :param/n}})

(defsc SampleRoot [this {:keys [x]}]
  {:query         [:x]
   :initial-state {:x 5}})

(defonce APP (do
               (let [app (app/fulcro-app)]
                 (app/set-root! app SampleRoot {:initialize-state? true})
                 app)))

(m/defmutation bump [{:counter/keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:counter/id id :counter/n] inc)))

(defn hook-demo-card [f]
  (ct.fulcro/fulcro-card
    {::ct.fulcro/root
     (comp/configure-hooks-component!
       f
       {:componentName (keyword (gensym "hook-demo"))})
     ::ct.fulcro/wrap-root?
     false}))

(defn- pcs [app component prior-props-tree]
  (let [ident           (comp/get-ident component prior-props-tree)
        state-map       (app/current-state app)
        starting-entity (get-in state-map ident)
        query           (comp/get-query component state-map)]
    (fdn/db->tree query starting-entity state-map)))

(defn use-fulcro [app component initial-state-params]
  (let [[current-props-tree set-state!] (hooks/use-state (comp/get-initial-state component initial-state-params))]
    (hooks/use-effect
      (fn [] (merge/merge-component! app component current-props-tree))
      [])
    {:props     (pcs app component current-props-tree)
     :transact! (fn [tx]
                  (comp/transact!! app tx)
                  (set-state! (pcs app component current-props-tree)))}))

(defn SampleComponent [props]
  (let [{:keys [props transact!]} (use-fulcro APP Counter {:id 1 :n 100})]
    (log/spy :info props)
    (dom/button {:onClick #(transact! [(bump props)])} (str (:counter/n props)))))

(ws/defcard fulcro-composed-into-vanilla-react
  (ct.react/react-card
    (dom/create-element SampleComponent {})
    ))