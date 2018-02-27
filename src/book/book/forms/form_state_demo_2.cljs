(ns book.forms.form-state-demo-2
  (:require [devcards.core]
            [fulcro.ui.elements :as ele]
            [fulcro.server :as server]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.ui.bootstrap3 :as bs]
            [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.ui.form-state :as fs]
            [clojure.string :as str]
            [cljs.spec.alpha :as s]
            [fulcro.client.data-fetch :as df]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server Code
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; a simple query for any person, that will return valid-looking data
(server/defquery-entity :person/by-id
  (value [env id params]
    {:db/id          id
     ::person-name   (str "User " id)
     ::person-age    56
     ::phone-numbers [{:db/id 1 ::phone-number "555-111-1212" ::phone-type :work}
                      {:db/id 2 ::phone-number "555-333-4444" ::phone-type :home}]}))

(defonce id (atom 1000))
(defn next-id [] (swap! id inc))

; Server submission...just prints delta for demo, and remaps tempids (forms with tempids are always considered dirty)
(server/defmutation submit-person [params]
  (action [env]
    (js/console.log "Server received form submission with content: ")
    (cljs.pprint/pprint params)
    (let [ids    (map (fn [[k v]] (second k)) (:diff params))
          remaps (into {} (keep (fn [v] (when (prim/tempid? v) [v (next-id)])) ids))]
      {:tempids remaps})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Client Code
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::person-name (s/and string? #(seq (str/trim %))))
(s/def ::person-age #(s/int-in-range? 1 120 %))

(defn render-field [component field renderer]
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

(def integer-fields #{::person-age})

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
                                                                                     :field        ~field})
                                                                 :root/person])
                          :onChange (if (integer-fields field)
                                      #(m/set-integer! component field :event %)
                                      #(m/set-string! component field :event %))} field-label)))))

(s/def ::phone-number #(re-matches #"\(?[0-9]{3}[-.)]? *[0-9]{3}-?[0-9]{4}" %))

(defsc PhoneForm [this {:keys [::phone-type ui/dropdown] :as props}]
  {:query       [:db/id ::phone-number ::phone-type
                 {:ui/dropdown (prim/get-query bs/Dropdown)}
                 fs/form-config-join]
   :form-fields #{::phone-number ::phone-type}
   :ident       [:phone/by-id :db/id]}
  (dom/div #js {:className "form"}
    (input-with-label this ::phone-number "Phone:" "10-digit phone number is required.")
    (input-with-label this ::phone-type "Type:" ""
      (fn [attrs]
        (bs/ui-dropdown dropdown
          :value phone-type
          :onSelect (fn [v]
                      (m/set-value! this ::phone-type v)
                      (prim/transact! this `[(fs/mark-complete! {:field ::phone-type})
                                             :root/person])))))))

(def ui-phone-form (prim/factory PhoneForm {:keyfn :db/id}))

(defn add-phone-dropdown*
  "Add a phone type dropdown to a phone entity"
  [state-map phone-id default-type]
  (let [dropdown-id (random-uuid)
        dropdown    (bs/dropdown dropdown-id "Type" [(bs/dropdown-item :work "Work") (bs/dropdown-item :home "Home")])]
    (-> state-map
      (prim/merge-component bs/Dropdown dropdown)           ; we're being a bit wasteful here and adding a new dropdown to state every time
      (bs/set-dropdown-item-active* dropdown-id default-type)
      (assoc-in [:phone/by-id phone-id :ui/dropdown] (bs/dropdown-ident dropdown-id)))))

(defn add-phone*
  "Add the given phone info to a person."
  [state-map phone-id person-id type number]
  (let [phone-ident      [:phone/by-id phone-id]
        new-phone-entity {:db/id phone-id ::phone-type type ::phone-number number}]
    (-> state-map
      (update-in [:person/by-id person-id ::phone-numbers] (fnil conj []) phone-ident)
      (assoc-in phone-ident new-phone-entity)
      (add-phone-dropdown* phone-id type))))

(defmutation add-phone
  "Mutation: Add a phone number to a person, and initialize it as a working form."
  [{:keys [person-id]}]
  (action [{:keys [state]}]
    (let [phone-id (prim/tempid)]
      (swap! state (fn [s]
                     (-> s
                       (add-phone* phone-id person-id :home "")
                       (fs/add-form-config* PhoneForm [:phone/by-id phone-id])))))))

(defsc PersonForm [this {:keys [:db/id ::phone-numbers]}]
  {:query       [:db/id ::person-name ::person-age
                 {::phone-numbers (prim/get-query PhoneForm)}
                 fs/form-config-join]
   :form-fields #{::person-name ::person-age ::phone-numbers} ; ::phone-numbers here becomes a subform because it is a join in the query.
   :ident       [:person/by-id :db/id]}
  (dom/div #js {:className "form"}
    (input-with-label this ::person-name "Name:" "Name is required.")
    (input-with-label this ::person-age "Age:" "Age must be between 1 and 120")
    (dom/h4 #js {} "Phone numbers:")
    (when (seq phone-numbers)
      (map ui-phone-form phone-numbers))
    (bs/button {:onClick #(prim/transact! this `[(add-phone {:person-id ~id})])} (bs/glyphicon {} :plus))))

(def ui-person-form (prim/factory PersonForm {:keyfn :db/id}))

(defn add-person*
  "Add a person with the given details to the state database."
  [state-map id name age]
  (let [person-ident [:person/by-id id]
        person       {:db/id id ::person-name name ::person-age age}]
    (assoc-in state-map person-ident person)))

(defmutation edit-new-person [_]
  (action [{:keys [state]}]
    (let [person-id    (prim/tempid)
          person-ident [:person/by-id person-id]
          phone-id     (prim/tempid)]
      (swap! state
        (fn [s] (-> s
                  (add-person* person-id "" 0)
                  (add-phone* phone-id person-id :home "")
                  (assoc :root/person person-ident)         ; join it into the UI as the person to edit
                  (fs/add-form-config* PersonForm [:person/by-id person-id])))))))

(defn add-dropdowns* [state-map person-id]
  (let [phone-number-idents (get-in state-map [:person/by-id person-id ::phone-numbers])]
    (reduce (fn [s phone-ident]
              (let [phone-id   (second phone-ident)
                    phone-type (get-in s [:phone/by-id phone-id ::phone-type])]
                (add-phone-dropdown* s phone-id phone-type)))
      state-map
      phone-number-idents)))

(defmutation edit-existing-person
  "Turn an existing person with phone numbers into an editable form with phone subforms."
  [{:keys [person-id]}]
  (action [{:keys [state]}]
    (swap! state
      (fn [s] (-> s
                (assoc :root/person [:person/by-id person-id])
                (fs/add-form-config* PersonForm [:person/by-id person-id]) ; will not re-add config to entities that were present
                (fs/entity->pristine* [:person/by-id person-id]) ; in case we're re-loading it, make sure the pristine copy it up-to-date
                (fs/mark-complete* [:person/by-id person-id]) ; it just came from server, so all fields should be valid
                (add-dropdowns* person-id))))))             ; each phone number needs a dropdown

(defmutation submit-person [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state fs/entity->pristine* [:person/by-id id]))
  (remote [env] true))

(defsc Root [this {:keys [root/person]}]
  {:query         [{:root/person (prim/get-query PersonForm)}]
   :initial-state (fn [params] {})}
  (ele/ui-iframe {:frameBorder 0 :width 800 :height 700}
    (dom/div #js {}
      (dom/link #js {:rel "stylesheet" :href "bootstrap-3.3.7/css/bootstrap.min.css"})
      (bs/button {:onClick #(df/load this [:person/by-id 21] PersonForm {:target               [:root/person]
                                                                         :marker               false
                                                                         :post-mutation        `edit-existing-person
                                                                         :post-mutation-params {:person-id 21}})}
        "Simulate Edit (existing) Person from Server")
      (bs/button {:onClick #(prim/transact! this `[(edit-new-person {})])} "Simulate New Person Creation")
      (when (::person-name person)
        (ui-person-form person))
      (dom/div nil
        (bs/button {:onClick  #(prim/transact! this `[(fs/reset-form! {:form-ident [:person/by-id ~(:db/id person)]})])
                    :disabled (not (fs/dirty? person))} "Reset")
        (bs/button {:onClick  #(prim/transact! this `[(submit-person {:id ~(:db/id person) :diff ~(fs/dirty-fields person false)})])
                    :disabled (or
                                (fs/invalid-spec? person)
                                (not (fs/dirty? person)))} "Submit")))))
