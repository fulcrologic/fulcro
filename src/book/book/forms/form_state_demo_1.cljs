(ns book.forms.form-state-demo-1
  (:require [fulcro.ui.elements :as ele]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.ui.form-state :as fs]
            [fulcro.ui.bootstrap3 :as bs]
            [fulcro.client.primitives :as prim :refer [defui defsc]]
            [fulcro.client.dom :as dom]
            [clojure.spec.alpha :as s]
            [garden.core :as g]))

(declare Root PhoneForm)

(defn render-field
  "A helper function for rendering just the fields."
  [component field renderer]
  (let [form         (prim/props component)
        entity-ident (prim/get-ident component form)
        id           (str (first entity-ident) "-" (second entity-ident))
        is-dirty?    (fs/dirty? form field)
        clean?       (not is-dirty?)
        validity     (fs/get-spec-validity form field)
        is-invalid?  (= :invalid validity)
        value        (get form field "")]
    (renderer {:dirty?   is-dirty?
               :ident    entity-ident
               :id       id
               :clean?   clean?
               :validity validity
               :invalid? is-invalid?
               :value    value})))

(defn input-with-label
  "A non-library helper function, written by you to help lay out your form."
  ([component field field-label validation-string input-element]
   (render-field component field
     (fn [{:keys [invalid? id dirty?]}]
       (bs/labeled-input {:error           (when invalid? validation-string)
                          :id              id
                          :warning         (when dirty? "(unsaved)")
                          :input-generator input-element} field-label))))
  ([component field field-label validation-string]
   (render-field component field
     (fn [{:keys [invalid? id dirty? value invalid ident]}]
       (bs/labeled-input {:value    value
                          :id       id
                          :error    (when invalid? validation-string)
                          :warning  (when dirty? "(unsaved)")
                          :onBlur   #(prim/transact! component `[(fs/mark-complete! {:entity-ident ~ident
                                                                                     :field        ~field})])
                          :onChange #(m/set-string! component field :event %)} field-label)))))

(s/def ::phone-number #(re-matches #"\(?[0-9]{3}[-.)]? *[0-9]{3}-?[0-9]{4}" %))

(defmutation abort-phone-edit [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state (fn [s]
                   (-> s
                     ; stop editing
                     (dissoc :root/phone)
                     ; revert to the pristine state
                     (fs/pristine->entity* [:phone/by-id id])))))
  (refresh [env] [:root/phone]))

(defmutation submit-phone [{:keys [id delta]}]
  (action [{:keys [state]}]
    (js/console.log delta)
    (swap! state (fn [s]
                   (-> s
                     ; stop editing
                     (dissoc :root/phone)
                     ; update the pristine state
                     (fs/entity->pristine* [:phone/by-id id])))))
  (remote [env] true)
  (refresh [env] [:root/phone [:phone/by-id id]]))

(defsc PhoneForm [this {:keys [:db/id ::phone-type root/dropdown] :as props}]
  {:query       [:db/id ::phone-type ::phone-number
                 {[:root/dropdown '_] (prim/get-query bs/Dropdown)} ;reusable dropdown
                 fs/form-config-join]
   :form-fields #{::phone-number ::phone-type}
   :ident       [:phone/by-id :db/id]}
  (dom/div :.form
    (input-with-label this ::phone-number "Phone:" "10-digit phone number is required.")
    (input-with-label this ::phone-type "Type:" ""
      (fn [attrs]
        (bs/ui-dropdown dropdown
          :value phone-type
          :onSelect (fn [v]
                      (m/set-value! this ::phone-type v)))))
    (bs/button {:onClick #(prim/transact! this `[(abort-phone-edit {:id ~id})])} "Cancel")
    (bs/button {:disabled (or (not (fs/checked? props)) (fs/invalid-spec? props))
                :onClick  #(prim/transact! this `[(submit-phone {:id ~id :delta ~(fs/dirty-fields props true)})])} "Commit!")))

(def ui-phone-form (prim/factory PhoneForm {:keyfn :db/id}))

(defsc PhoneNumber [this {:keys [:db/id ::phone-type ::phone-number]} {:keys [onSelect]}]
  {:query         [:db/id ::phone-number ::phone-type]
   :initial-state {:db/id :param/id ::phone-number :param/number ::phone-type :param/type}
   :ident         [:phone/by-id :db/id]}
  (dom/li
    (dom/a {:onClick (fn [] (onSelect id))}
      (str phone-number " (" (phone-type {:home "Home" :work "Work" nil "Unknown"}) ")"))))

(def ui-phone-number (prim/factory PhoneNumber {:keyfn :db/id}))

(defsc PhoneBook [this {:keys [:db/id ::phone-numbers]} {:keys [onSelect]}]
  {:query         [:db/id {::phone-numbers (prim/get-query PhoneNumber)}]
   :initial-state {:db/id          :main-phone-book
                   ::phone-numbers [{:id 1 :number "541-555-1212" :type :home}
                                    {:id 2 :number "541-555-5533" :type :work}]}
   :ident         [:phonebook/by-id :db/id]}
  (dom/div
    (dom/h4 "Phone Book (click a number to edit)")
    (dom/ul
      (map (fn [n] (ui-phone-number (prim/computed n {:onSelect onSelect}))) phone-numbers))))

(def ui-phone-book (prim/factory PhoneBook {:keyfn :db/id}))

(defmutation edit-phone-number [{:keys [id]}]
  (action [{:keys [state]}]
    (let [phone-type (get-in @state [:phone/by-id id ::phone-type])]
      (swap! state (fn [s]
                     (-> s
                       ; make sure the form config is with the entity
                       (fs/add-form-config* PhoneForm [:phone/by-id id])
                       ; since we're editing an existing thing, we should start it out complete (validations apply)
                       (fs/mark-complete* [:phone/by-id id])
                       (bs/set-dropdown-item-active* :phone-type phone-type)
                       ; tell the root UI that we're editing a phone number by linking it in
                       (assoc :root/phone [:phone/by-id id])))))))

(defsc Root [this {:keys [:root/phone :root/phonebook]}]
  {:query         [{:root/dropdown (prim/get-query bs/Dropdown)}
                   {:root/phonebook (prim/get-query PhoneBook)}
                   {:root/phone (prim/get-query PhoneForm)}]
   :initial-state (fn [params]
                    {:root/dropdown  (bs/dropdown :phone-type "Type" [(bs/dropdown-item :work "Work")
                                                                      (bs/dropdown-item :home "Home")])
                     :root/phonebook (prim/get-initial-state PhoneBook {})})}
  (ele/ui-iframe {:frameBorder 0 :width 500 :height 200}
    (dom/div
      (dom/link {:rel "stylesheet" :href "bootstrap-3.3.7/css/bootstrap.min.css"})
      (if (contains? phone ::phone-number)
        (ui-phone-form phone)
        (ui-phone-book (prim/computed phonebook {:onSelect (fn [id] (prim/transact! this `[(edit-phone-number {:id ~id})]))}))))))
