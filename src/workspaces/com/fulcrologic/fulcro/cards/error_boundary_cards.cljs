(ns com.fulcrologic.fulcro.cards.error-boundary-cards
  (:require
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as m]
    [taoensso.timbre :as log]))


(defsc BadActor [this {:keys [id] :as props}]
  {:query                [:id]
   :ident                :id
   :componentDidMount    (fn [this]
                           (when (and
                                   (not (comp/get-computed this :reset?))
                                   (= 2 (:id (comp/props this))))
                             (throw (ex-info "mount Craptastic!" {}))))
   :componentWillUnmount (fn [this] (log/spy :info (comp/props this))
                           (when (= 3 (:id (comp/props this)))
                             (throw (ex-info "unmount Craptastic!" {}))))
   :initial-state        {:id :param/id}}
  (if (and (= id 4)
        (not (comp/get-computed this :reset?)))
    (throw (ex-info "Render craptastic!" {}))
    (dom/div "Actor")))

(def ui-bad-actor (comp/computed-factory BadActor {:keyfn :id}))

(defsc Child [this {:child/keys [id name actor] :as props}]
  {:query                    [:child/id :child/name {:child/actor (comp/get-query BadActor)}]
   :ident                    :child/id
   :componentDidCatch        (fn [this err info] (log/spy :error [err info]))
   :getDerivedStateFromError (fn [error]
                               (log/spy :info error)
                               {:error? true})
   :initial-state            {:child/id    :param/id :child/name :param/name
                              :child/actor {:id :param/id}}}
  (if (log/spy :info (comp/get-state this :error?))
    (dom/div
      "There was an error in this part of the UI."
      (dom/button {:onClick #(comp/set-state! this {:error? false})} "Retry"))
    (dom/div
      (dom/label "Child: " name)
      (ui-bad-actor actor {:reset? (contains? (comp/get-state this) :error?)}))))

(def ui-child (comp/factory Child {:keyfn :child/id}))

(defsc Root [this {:keys [children]}]
  {:query          [{:children (comp/get-query Child)}]
   :initLocalState (fn [] {:idx 0})
   :initial-state  {:children [{:id 1 :name "Joe"}
                               {:id 2 :name "Sally"}
                               {:id 3 :name "Bob"}
                               {:id 4 :name "Alice"}]}}
  (let [idx (comp/get-state this :idx)]
    (dom/div
      (dom/button {:onClick (fn [] (comp/set-state! this {:idx (min 3 (inc idx))}))} "Next actor")
      (dom/button {:onClick (fn [] (comp/set-state! this {:idx (max 0 (dec idx))}))} "Prior actor")
      (ui-child (get children idx)))))

(ws/defcard error-boundary-card
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? false
     ::ct.fulcro/root       Root
     ::ct.fulcro/app        {}}))
