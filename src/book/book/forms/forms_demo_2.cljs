(ns book.forms.forms-demo-2
  (:require
    [clojure.string :as str]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client :as fc]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.ui.forms :as f :refer [defvalidator]]))

(declare ValidatedPhoneForm)

(defn field-with-label
  "A non-library helper function, written by you to help lay out your form."
  ([comp form name label] (field-with-label comp form name label nil))
  ([comp form name label validation-message]
   (dom/div #js {:className (str "form-group" (if (f/invalid? form name) " has-error" ""))}
     (dom/label :.col-sm-2 {:htmlFor name} label)
     ;; THE LIBRARY SUPPLIES f/form-field. Use it to render the actual field
     (dom/div :.col-sm-10 (f/form-field comp form name))
     (when (and validation-message (f/invalid? form name))
       (dom/span :.col-sm-offset-2.col-sm-10 {:className (str name)} validation-message)))))

;; Sample validator that requires there be at least two words
(f/defvalidator name-valid? [_ value args]
  (let [trimmed-value (str/trim value)]
    (str/includes? trimmed-value " ")))

(defvalidator us-phone?
  [sym value args]
  (seq (re-matches #"[(][0-9][0-9][0-9][)] [0-9][0-9][0-9]-[0-9][0-9][0-9][0-9]" value)))

(defsc ValidatedPhoneForm [this form]
  {:initial-state (fn [params] (f/build-form ValidatedPhoneForm (or params {})))
   :form-fields   [(f/id-field :db/id)
                   (f/text-input :phone/number :validator `us-phone?) ; Addition of validator
                   (f/dropdown-input :phone/type [(f/option :home "Home") (f/option :work "Work")])]
   :query         [:db/id :phone/type :phone/number f/form-key]
   :ident         [:phone/by-id :db/id]}
  (dom/div :.form-horizontal
    (field-with-label this form :phone/type "Phone type:")
    ;; One more parameter to give the validation error message:
    (field-with-label this form :phone/number "Number:" "Please format as (###) ###-####")))

(def ui-vphone-form (prim/factory ValidatedPhoneForm))

(defsc Root [this {:keys [phone]}]
  {:query         [f/form-key {:phone (prim/get-query ValidatedPhoneForm)}]
   :initial-state (fn [params]
                    (let [phone-number {:db/id 1 :phone/type :home :phone/number "555-1212"}]
                      {:phone (prim/get-initial-state ValidatedPhoneForm phone-number)}))}
  (dom/div
    (ui-vphone-form phone)))
