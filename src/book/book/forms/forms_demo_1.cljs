(ns book.forms.forms-demo-1
  (:require
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.ui.forms :as f]))

(defn field-with-label
  "A non-library helper function, written by you to help lay out your form."
  ([comp form name label] (field-with-label comp form name label nil))
  ([comp form name label validation-message]
   (dom/div :.form-group {:className (when (f/invalid? form name) " has-error")}
     (dom/label :.col-sm-2 {:htmlFor name} label)
     (dom/div :.col-sm-10
       ;; THE LIBRARY SUPPLIES f/form-field. Use it to render the actual field
       (f/form-field comp form name))
     (when (and validation-message (f/invalid? form name))
       (dom/span :.col-sm-offset-2.col-sm-10 {:className (str name)} validation-message)))))

; form/props are the same thing. The entity state *is* the form state
(defsc PhoneForm [this form]
  {:initial-state (fn [params] (f/build-form PhoneForm (or params {})))
   :form-fields   [(f/id-field :db/id) (f/text-input :phone/number :className "form-control") (f/dropdown-input :phone/type [(f/option :home "Home") (f/option :work "Work")])]
   :query         (fn [] [:db/id :phone/type :phone/number f/form-key]) ; Don't forget f/form-key!
   :ident         [:phone/by-id :db/id]}
  (dom/div :.form-horizontal
    (field-with-label this form :phone/type "Phone type:")  ; Use your own helpers to render out the fields
    (field-with-label this form :phone/number "Number:")))

(def ui-phone-form (prim/factory PhoneForm {:keyfn :db/id}))

(defsc Root [this {:keys [phone]}]
  {:query         [{:phone (prim/get-query PhoneForm)}]
   :initial-state (fn [params]
                    (let [phone-number {:db/id 1 :phone/type :home :phone/number "555-1212"}]
                      {:phone (prim/get-initial-state PhoneForm phone-number)}))}
  (dom/div
    (ui-phone-form phone)))
