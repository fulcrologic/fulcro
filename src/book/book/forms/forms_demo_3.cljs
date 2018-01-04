(ns book.forms.forms-demo-3
(:require
  [clojure.string :as str]
  [fulcro.client.dom :as dom]
  [fulcro.client.primitives :as prim :refer [defui defsc]]
  [fulcro.client.mutations :as m :refer [defmutation]]
  [fulcro.ui.forms :as f :refer [defvalidator]]
  [fulcro.i18n :refer [tr]]))

(declare ValidatedPhoneForm)

;; Sample validator that requires there be at least two words
(defn field-with-label
  "A non-library helper function, written by you to help lay out your form."
  ([comp form name label] (field-with-label comp form name label nil))
  ([comp form name label validation-message]
   (dom/div #js {:className (str "form-group" (if (f/invalid? form name) " has-error" ""))}
     (dom/label #js {:className "col-sm-2" :htmlFor name} label)
     ;; THE LIBRARY SUPPLIES f/form-field. Use it to render the actual field
     (dom/div #js {:className "col-sm-10"} (f/form-field comp form name))
     (when (and validation-message (f/invalid? form name))
       (dom/span #js {:className (str "col-sm-offset-2 col-sm-10" name)} validation-message)))))

(defn checkbox-with-label
  "A helper function to lay out checkboxes."
  ([comp form name label] (field-with-label comp form name label nil))
  ([comp form name label validation-message]
   (dom/div #js {:className "checkbox"}
     (dom/label nil (f/form-field comp form name) label))))

(f/defvalidator name-valid? [_ value args]
  (let [trimmed-value (str/trim value)]
    (str/includes? trimmed-value " ")))

(defvalidator us-phone?
  [sym value args]
  (seq (re-matches #"[(][0-9][0-9][0-9][)] [0-9][0-9][0-9]-[0-9][0-9][0-9][0-9]" value)))

(defmutation add-phone [{:keys [id person]}]
  (action [{:keys [state]}]
    (let [new-phone    (f/build-form ValidatedPhoneForm {:db/id id :phone/type :home :phone/number ""})
          person-ident [:people/by-id person]
          phone-ident  (prim/ident ValidatedPhoneForm new-phone)]
      (swap! state assoc-in phone-ident new-phone)
      (prim/integrate-ident! state phone-ident :append (conj person-ident :person/phone-numbers)))))

(defui ^:once ValidatedPhoneForm
  static prim/InitialAppState
  (initial-state [this params] (f/build-form this (or params {})))
  static f/IForm
  (form-spec [this] [(f/id-field :db/id)
                     (f/text-input :phone/number :validator `us-phone?) ; Addition of validator
                     (f/dropdown-input :phone/type [(f/option :home "Home") (f/option :work "Work")])])
  static prim/IQuery
  (query [this] [:db/id :phone/type :phone/number f/form-key])
  static prim/Ident
  (ident [this props] [:phone/by-id (:db/id props)])
  Object
  (render [this]
    (let [form (prim/props this)]
      (dom/div #js {:className "form-horizontal"}
        (field-with-label this form :phone/type "Phone type:")
        ;; One more parameter to give the validation error message:
        (field-with-label this form :phone/number "Number:" "Please format as (###) ###-####")))))

(def ui-vphone-form (prim/factory ValidatedPhoneForm))

(defui PersonForm
  static prim/InitialAppState
  (initial-state [this params] (f/build-form this (or params {})))
  static f/IForm
  (form-spec [this] [(f/id-field :db/id)
                     (f/subform-element :person/phone-numbers ValidatedPhoneForm :many)
                     (f/text-input :person/name :validator `name-valid?)
                     (f/integer-input :person/age :validator `f/in-range?
                       :validator-args {:min 1 :max 110})
                     (f/checkbox-input :person/registered-to-vote?)])
  static prim/IQuery
  ; NOTE: f/form-root-key so that sub-forms will trigger render here
  (query [this] [f/form-root-key f/form-key
                 :db/id :person/name :person/age
                 :person/registered-to-vote?
                 {:person/phone-numbers (prim/get-query ValidatedPhoneForm)}])
  static prim/Ident
  (ident [this props] [:people/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [person/phone-numbers] :as props} (prim/props this)]
      (dom/div #js {:className "form-horizontal"}
        (field-with-label this props :person/name "Full Name:" "Please enter your first and last name.")
        (field-with-label this props :person/age "Age:" "That isn't a real age!")
        (checkbox-with-label this props :person/registered-to-vote? "Registered?")
        (when (f/current-value props :person/registered-to-vote?)
          (dom/div nil "Good on you!"))
        (dom/div nil
          (mapv ui-vphone-form phone-numbers))
        (when (f/valid? props)
          (dom/div nil "All fields have had been validated, and are valid"))
        (dom/div #js {:className "button-group"}
          (dom/button #js {:className "btn btn-primary"
                           :onClick   #(prim/transact! this
                                         `[(add-phone ~{:id     (prim/tempid)
                                                        :person (:db/id props)})])}
            "Add Phone")
          (dom/button #js {:className "btn btn-default" :disabled (f/valid? props)
                           :onClick   #(f/validate-entire-form! this props)}
            "Validate")
          (dom/button #js {:className "btn btn-default", :disabled (not (f/dirty? props))
                           :onClick   #(f/reset-from-entity! this props)}
            "UNDO")
          (dom/button #js {:className "btn btn-default", :disabled (not (f/dirty? props))
                           :onClick   #(f/commit-to-entity! this)}
            "Submit"))))))

(def ui-person-form (prim/factory PersonForm))

(defui ^:once Root
  static prim/InitialAppState
  (initial-state [this params]
    {:ui/person-id 1
     :person       (prim/get-initial-state PersonForm
                     {:db/id                      1
                      :person/name                "Tony Kay"
                      :person/age                 23
                      :person/registered-to-vote? false
                      :person/phone-numbers       [(prim/get-initial-state ValidatedPhoneForm
                                                     {:db/id        22
                                                      :phone/type   :work
                                                      :phone/number "(123) 412-1212"})
                                                   (prim/get-initial-state ValidatedPhoneForm
                                                     {:db/id        23
                                                      :phone/type   :home
                                                      :phone/number "(541) 555-1212"})]})})
  static prim/IQuery
  (query [this] [:ui/person-id {:person (prim/get-query PersonForm)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key ui/person-id person]} (prim/props this)]
      (dom/div #js {:key react-key}
        (when person
          (ui-person-form person))))))

