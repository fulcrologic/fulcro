(ns fulcro.ui.form-state-spec
  (:require
    #?(:clj [taoensso.timbre :as timbre])
            [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro-spec.core :refer [behavior specification assertions component when-mocking provided]]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [fulcro.ui.form-state :as f]))

(defsc Locale [this props]
  {:query [:db/id ::country f/form-config-join]
   :ident [:locale/by-id :db/id]})

(s/def ::country keyword?)

(defsc Phone [this props]
  {:query [:db/id {::locale (prim/get-query Locale)}
           f/form-config-join
           ::phone-number]
   :ident [:phone/by-id :db/id]})

(s/def ::phone-number (s/and string? #(re-matches #"[-0-9()]+" %)))

(defsc Person [this props]
  {:query [:db/id ::person-name
           f/form-config-join
           {::phone-numbers (prim/get-query Phone)}]
   :ident [:person/by-id :db/id]})

(s/def ::person-name (s/and string? #(not (empty? (str/trim %)))))

(specification "add-form-config" :focused
  (let [person      {:db/id 3 ::person-name "J.B." ::person-age 49 ::phone-numbers []}
        form        (f/add-form-config person {::f/id     (prim/get-ident Person person)
                                               ::f/fields #{::person-name ::person-age}})
        form-config (::f/config form)]
    (behavior "Lays out the given initial pristine state and config."
      (assertions
        "Places a valid form-config under the ::f/form-config key"
        (s/valid? ::f/config form-config) => true
        (s/explain-data ::f/config form-config) => nil
        "Returns something that looks like the regular entity"
        (dissoc form ::f/config) => person
        "Includes empty subforms if none are present"
        (::f/subforms form-config) => #{}
        "Places (in pristine-state) just the original fields from the entity state that belong in the form"
        (::f/pristine-state form-config) => (select-keys person #{::person-name ::person-age})))))

(let [locale                                  {:db/id 22 ::country :US}
      locale                                  (f/add-form-config locale (f/form-config (prim/get-ident Locale locale) #{::country}))
      phone-numbers                           [{:db/id 2 ::phone-number "555-1212" ::locale locale} {:db/id 3 ::phone-number "555-1212"}]
      phone-number-forms                      (mapv #(f/add-form-config % (f/form-config (prim/get-ident Phone %)
                                                                            #{::phone-number}
                                                                            #{::locale})) phone-numbers)
      person                                  {:db/id 1 ::person-name "Bo" ::phone-numbers phone-number-forms}
      person-form                             (f/add-form-config person (f/form-config (prim/get-ident Person person) #{::person-name} #{::phone-numbers}))
      validated-person                        (-> person-form
                                                (assoc-in [::f/config ::f/complete?] #{::person-name})
                                                (assoc-in [::phone-numbers 0 ::f/config ::f/complete?] #{::phone-number})
                                                (assoc-in [::phone-numbers 0 ::locale ::f/config ::f/complete?] #{::country})
                                                (assoc-in [::phone-numbers 1 ::f/config ::f/complete?] #{::phone-number}))
      state-map                               (prim/tree->db [{:the-person (prim/get-query Person)}] {:the-person person-form} true)
      validated-tree                          (fn [class form-to-validate]
                                                (as-> state-map sm
                                                  (f/validate* sm form-to-validate)
                                                  (prim/db->tree [{:k (prim/get-query class)}] sm sm)
                                                  (get sm :k)))
      person-with-incomplete-name             (assoc-in validated-person [::f/config ::f/complete?] #{})
      person-with-incomplete-nested-form      (assoc-in validated-person [::phone-numbers 0 ::locale ::f/config ::f/complete?] #{})
      person-with-invalid-name                (assoc validated-person ::person-name "")
      person-with-invalid-nested-phone-locale (assoc-in validated-person [::phone-numbers 0 ::locale ::country] "England")
      person-ui-tree                          (fn [state id]
                                                (get
                                                  (prim/db->tree [{[:person/by-id id] (prim/get-query Person)}] state state)
                                                  [:person/by-id id]))]

  (specification "dirty?" :focused
    (behavior "is a UI (tree) operation for checking if the form has been modified from pristine"
      (assertions
        "is false if there are no changes"
        (f/dirty? person-form) => false
        "is true if the data has changed in the top-level form"
        (f/dirty? (assoc person-form ::person-name "New name")) => true
        "is true if any subform item has changed"
        (f/dirty? (assoc-in person-form [::phone-numbers 0 ::phone-number] "555-1111")) => true
        (f/dirty? (assoc-in person-form [::phone-numbers 0 ::locale ::country] :MX)) => true
        (f/dirty? (assoc-in person-form [::phone-numbers 1 ::phone-number] "555-1111")) => true)))

  (specification "validity" :focused
    (behavior "is a UI (tree) operation for checking if the form (or fields) are valid. It:"
      (assertions
        "returns :unchecked if the fields have not been interacted with"
        (f/get-validity person-form) => :unchecked
        "returns :valid if all fields are complete and valid"
        (f/get-validity validated-person) => :valid
        "returns :unchecked if any field is not marked as complete"
        (f/get-validity person-with-incomplete-name) => :unchecked
        "returns :unchecked if any NESTED fields are not marked as complete"
        (f/get-validity person-with-incomplete-nested-form) => :unchecked
        "returns :invalid if any top-level property is invalid"
        (f/get-validity person-with-invalid-name) => :invalid
        "returns :invalid if any nexted property is invalid"
        (f/get-validity person-with-invalid-nested-phone-locale) => :invalid)))
  (specification "valid?" :focused
    (assertions
      "Returns true if validity is :valid"
      (f/valid? validated-person) => true
      "Returns false if validity is :unchecked"
      (f/valid? person-with-incomplete-nested-form) => false
      "Returns false if validity is :invalid"
      (f/valid? person-with-invalid-name) => false))
  (specification "checked?" :focused
    (assertions
      "Returns true if validity is :valid or :invalid"
      (f/checked? validated-person) => true
      (f/checked? person-with-invalid-name) => true
      (f/checked? person-with-invalid-nested-phone-locale) => true
      "Returns false if validity is :unchecked"
      (f/checked? person-with-incomplete-nested-form) => false))
  (specification "invalid?" :focused
    (assertions
      "Returns true if validity is :invalid"
      (f/invalid? person-with-invalid-name) => true
      (f/invalid? person-with-invalid-nested-phone-locale) => true
      "Returns false if validity is :unchecked"
      (f/invalid? person-with-incomplete-nested-form) => false
      "Returns false if validity is :valid"
      (f/invalid? validated-person) => false))

  (specification "update-forms" :focused
    (behavior "Allows one to traverse a nested form set in the app state database and apply xforms to the form and config"
      (let [updated-state (f/update-forms state-map (fn [e c] [(assoc e ::touched true) (assoc c ::touched true)]) [:person/by-id 1])]
        (assertions
          "Touches the top-level form config"
          (get-in updated-state [::f/forms-by-ident (f/form-id [:person/by-id 1]) ::touched]) => true
          "Touches the nested form configs"
          (get-in updated-state [::f/forms-by-ident (f/form-id [:phone/by-id 2]) ::touched]) => true
          (get-in updated-state [::f/forms-by-ident (f/form-id [:phone/by-id 3]) ::touched]) => true
          (get-in updated-state [::f/forms-by-ident (f/form-id [:locale/by-id 22]) ::touched]) => true
          "Touches the top-level entity"
          (get-in updated-state [:person/by-id 1 ::touched]) => true
          "Touches the nested form  entities"
          (get-in updated-state [:phone/by-id 2 ::touched]) => true
          (get-in updated-state [:phone/by-id 3 ::touched]) => true
          (get-in updated-state [:locale/by-id 22 ::touched]) => true))))

  (specification "validate*" :focused
    (behavior "is a state map operation that marks field(s) as complete, so validation checks can be applied"
      (let [get-person (fn [state id validate?] (let [validated-state (cond-> state
                                                                        validate? (f/validate* [:person/by-id id]))]
                                                  (person-ui-tree validated-state id)))]
        (assertions
          "Validating makes the form checked? = true"
          (f/checked? (get-person state-map 1 false)) => false
          (f/checked? (get-person state-map 1 true)) => true
          "Valid forms become valid"
          (f/valid? (get-person state-map 1 false)) => false
          (f/valid? (get-person state-map 1 true)) => true))))

  (specification "reset-form*" :focused
    (behavior "is a state map operation that recursively undoes any entity state changes that differ from pristine"
      (let [modified-state-map (-> state-map
                                 (assoc-in [:phone/by-id 3 ::phone-number] "111")
                                 (assoc-in [:locale/by-id 22 ::country] :UK)
                                 (assoc-in [:person/by-id 1 ::person-name] "Bobby"))
            reset-state-map    (f/pristine->entity* modified-state-map [:person/by-id 1])]
        (assertions
          (not= modified-state-map state-map) => true
          reset-state-map => state-map))))

  (specification "commit-form*" :focused
    (behavior "is a state map operation that recursively updates any entity pristine form state so that the form is no longer dirty"
      (let [modified-state-map  (-> state-map
                                  (assoc-in [:phone/by-id 3 ::phone-number] "111")
                                  (assoc-in [:locale/by-id 22 ::country] :UK)
                                  (assoc-in [:person/by-id 1 ::person-name] "Bobby"))
            modified-ui-tree    (person-ui-tree modified-state-map 1)
            committed-state-map (f/entity->pristine* modified-state-map [:person/by-id 1])
            committed-ui-tree   (person-ui-tree committed-state-map 1)]
        (assertions
          "committing transitions dirty -> clean"
          (f/dirty? modified-ui-tree) => true
          (f/dirty? committed-ui-tree) => false
          "the clean version has the updated data"
          (get-in committed-ui-tree [::person-name]) => "Bobby"
          (get-in committed-ui-tree [::phone-numbers 1 ::phone-number]) => "111"
          (get-in committed-ui-tree [::phone-numbers 0 ::locale ::country]) => :UK)))))
