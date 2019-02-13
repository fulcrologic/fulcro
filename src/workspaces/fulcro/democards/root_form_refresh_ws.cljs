(ns fulcro.democards.root-form-refresh-ws
  (:require
    [nubank.workspaces.core :as ws]
    [nubank.workspaces.model :as wsm]
    [nubank.workspaces.card-types.fulcro :as ct.fulcro]
    [nubank.workspaces.lib.fulcro-portal :as f.portal]
    [fulcro.client.dom :as dom]
    [goog.object]
    [fulcro.client.primitives :as prim :refer [defui defsc InitialAppState initial-state]]
    [fulcro.client.mutations :as m]
    [fulcro.ui.forms :as f]))

(m/defmutation simple-edit
  "Simple mutation that updates variable at the path, doesn't operate with non-normalized"
  [{:keys [path fn]}]
  (action [{:keys [state]}]
    (swap! state update-in path fn)))


(defsc PersonForm [this {:keys [person/id person/name person/active] :as form}]
  {:query         [f/form-key :person/id :person/active :person/name]
   :form-fields   [(f/id-field :person/id) (f/text-input :person/name) (f/checkbox-input :person/active :default-value true)]
   :ident         [:person/by-id :person/id]
   :initial-state (fn [params] (f/build-form PersonForm {:person/id 1 :person/active false :person/name "Mike"}))}
  (dom/div
    (dom/div (f/form-field this form :person/name))
    (dom/button {:onClick (fn [evt] (prim/transact! this `[(simple-edit {:path [:person/by-id ~id :person/active] :fn ~not}) f/form-root-key]))}
      "CLICK ME")))

(def ui-person-form (prim/factory PersonForm {:key-fn :person/id}))

(defsc TopForm [this {:keys [modal-item] :as form}]
  {:form-fields   [(f/subform-element :modal-item PersonForm :one :isComponent false)]
   :query         [f/form-root-key f/form-key {:modal-item (prim/get-query PersonForm)}]
   :ident         (fn [] [:topform :singleton])
   :initial-state (fn [params] (f/build-form TopForm {:modal-item (prim/get-initial-state PersonForm {})}))}
  (let [{:keys [person/name person/active]} modal-item]
    (dom/div
      (dom/div (if active (str "! " name) (str "# " name)))
      (dom/div
        (ui-person-form modal-item)))))

(def ui-top-form (prim/factory TopForm))

(defsc Root [this {:keys [top-form]}]
  {:query         [{:top-form (prim/get-query TopForm)}]
   :initial-state (fn [params] {:top-form (prim/get-initial-state TopForm {})})}
  (dom/div
    (dom/div "Title")
    (ui-top-form top-form)))

(ws/defcard root-form-refresh
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root Root}))

