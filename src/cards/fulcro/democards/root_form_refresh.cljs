(ns fulcro.democards.root-form-refresh
  (:require [devcards.core :as dc]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
            [fulcro.client.cards :refer [defcard-fulcro]]
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
  {:query [f/form-key :person/id :person/active :person/name]
   :form-fields [(f/id-field :person/id) (f/text-input :person/name) (f/checkbox-input :person/active :default-value true)]
   :ident [:person/by-id :person/id]
   :initial-state (fn [params] (f/build-form PersonForm {:person/id 1 :person/active false :person/name "Mike"}))}
  (dom/div nil
    (dom/div nil (f/form-field this form :person/name))
    (dom/button #js {:onClick (fn [evt] (prim/transact! this `[(simple-edit {:path [:person/by-id ~id :person/active] :fn ~not}) f/form-root-key]))}
      "CLICK ME")))

(def ui-person-form (prim/factory PersonForm {:key-fn :person/id}))

(defsc TopForm [this {:keys [modal-item] :as form}]
  {:form-fields [(f/subform-element :modal-item PersonForm :one :isComponent false)]
   :query [f/form-root-key f/form-key {:modal-item (prim/get-query PersonForm)}]
   :ident (fn [] [:topform :singleton])
   :initial-state (fn [params] (f/build-form TopForm {:modal-item (prim/get-initial-state PersonForm {})}))}
  (let [{:keys [person/name person/active]} modal-item]
    (dom/div nil
      (dom/div nil (if active (str "! " name) (str "# " name)))
      (dom/div nil
        (ui-person-form modal-item)))))

(def ui-top-form (prim/factory TopForm))

(defsc Root [this {:keys [ui/react-key top-form]}]
  {:query [:ui/react-key {:top-form (prim/get-query TopForm)}]
   :initial-state (fn [params] {:top-form (prim/get-initial-state TopForm {})})}
  (dom/div #js {:key react-key}
    (dom/div nil "Title")
    (ui-top-form top-form)))

(defcard-fulcro root-form-refresh Root)
