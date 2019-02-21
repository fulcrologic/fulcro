(ns fulcro.ui.form-state-spec
  (:require
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro-spec.core :refer [behavior specification assertions component when-mocking provided]]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [fulcro.ui.form-state :as f]))

(defsc Locale [this props]
  {:query       [:db/id ::country f/form-config-join]
   :ident       [:locale/by-id :db/id]
   :form-fields #{::country}})

(s/def ::country keyword?)

(defsc Phone [this props]
  {:query       [:db/id {::locale (prim/get-query Locale)}
                 ::phone-number
                 f/form-config-join]
   :ident       [:phone/by-id :db/id]
   :form-fields #{::locale ::phone-number}})

(defsc UnusedForm [this props]
  {:query       [:db/id ::data f/form-config-join]
   :ident       [:unused/by-id :db/id]
   :form-fields #{::data}})

(s/def ::phone-number (s/and string? #(re-matches #"[-0-9()]+" %)))

(defsc Person [this props]
  {:query       [:db/id ::person-name ::person-age
                 {::unused (prim/get-query UnusedForm)}
                 {::phone-numbers (prim/get-query Phone)}
                 f/form-config-join]
   :ident       [:person/by-id :db/id]
   :form-fields #{::person-name ::unused ::person-age ::phone-numbers}})

(defsc NonForm [this props]
  {:query [:id :x]
   :ident [:ntop :id]})

(defsc FormNoFields [this props]
  {:query [:id :x f/form-config-join]
   :ident [:ntop :id]})

(defsc BadlyNestedForm [this props]
  {:query       [:id :name {:thing (prim/get-query NonForm)} f/form-config-join]
   :ident       [:top :id]
   :form-fields #{:name :thing}})

(s/def ::person-name (s/and string? #(not (empty? (str/trim %)))))

(specification "add-form-config"
  (component "returns the entity with added configuration data, where:"
    (let [data-tree       {:db/id          1
                           ::person-name   "Joe"
                           ::phone-numbers [{:db/id   2 ::phone-number "555-1212"
                                             ::locale {:db/id 5 ::country "US"}}]}
          configured-form (f/add-form-config Person data-tree)
          form-config     (get configured-form ::f/config)]
      (assertions
        "::f/config is a spec-valid config"
        (s/valid? ::f/config form-config) => true
        (s/explain-data ::f/config form-config) => nil
        "the original entity fields are unchanged"
        (-> configured-form
          (dissoc ::f/config)
          (update-in [::phone-numbers 0] dissoc ::f/config)
          (update-in [::phone-numbers 0 ::locale] dissoc ::f/config)) => data-tree
        "the original fields (and subform idents) are saved to pristine state"
        (::f/pristine-state form-config) => {::person-name   "Joe"
                                             ::phone-numbers [[:phone/by-id 2]]}
        "the entity's ident is the form's ID"
        (get-in configured-form [::f/config ::f/id]) => [:person/by-id 1]
        "has the scalar declared fields"
        (get-in configured-form [::f/config ::f/fields]) => #{::person-name ::person-age}
        "data about each populated subform is included (recursively)"
        (some-> form-config ::f/subforms ::phone-numbers meta :component) => Phone
        (some-> configured-form ::phone-numbers first ::f/config ::f/subforms ::locale meta :component) => Locale
        "data about empty subforms is included"
        (some-> form-config ::f/subforms ::unused meta :component) => UnusedForm
        "each subform is recursively initialized"
        (get-in configured-form [::phone-numbers 0 ::f/config ::f/id]) => [:phone/by-id 2]
        (get-in configured-form [::phone-numbers 0 ::locale ::f/config ::f/id]) => [:locale/by-id 5])))
  (component "error checking"
    (let [data-tree {:id 1 :name "A" :thing {:id 2 :x 42}}]
      (assertions
        "throws an exception if the target fails to query for form config"
        (f/add-form-config NonForm data-tree) =throws=> {:regex #"to .*NonForm, but it does not query for config"}
        "throws an exception if the target fails to declare fields"
        (f/add-form-config FormNoFields data-tree) =throws=> {:regex #"to .*FormNoFields, but it does not declare any fields"}
        "does recursive checks on subforms"
        (f/add-form-config BadlyNestedForm data-tree) =throws=> {:regex #"Subform .*NonForm of .*BadlyNestedForm"
                                                                 :fn    (fn [e] (some-> e (ex-data) (contains? :nested-exception)))}))))

(specification "add-form-config*"
  (let [state-map         {:person/by-id {1 {:db/id          1 ::person-name "Joe" :ui/checked? true
                                             ::phone-numbers [[:phone/by-id 5]]}}
                           :root-prop    99
                           :phone/by-id  {5 {:db/id 5 ::phone-number "555-4444"
                                             :ui/n  22}}}
        configured-db     (f/add-form-config* state-map Person [:person/by-id 1])
        fconfig-id-person [::f/forms-by-ident (f/form-id [:person/by-id 1])]
        fconfig-id-phone  [::f/forms-by-ident (f/form-id [:phone/by-id 5])]]
    (assertions
      "Adds for configuration to normalized tables"
      (get-in configured-db [:person/by-id 1 ::f/config]) => fconfig-id-person
      (get-in configured-db [:phone/by-id 5 ::f/config]) => fconfig-id-phone
      (get-in configured-db fconfig-id-person) =fn=> (fn [c] (contains? c ::f/id))
      "leaves existing (non-form) data alone"
      (get-in configured-db [:person/by-id 1 :ui/checked?]) => true
      (get-in configured-db [:phone/by-id 5 :ui/n]) => 22)))

(specification "delete-form-state*"
  (let [state-map     {:person/by-id {1 {:db/id          1 ::person-name "Joe" :ui/checked? true
                                         ::phone-numbers [[:phone/by-id 5]]}}
                       :root-prop    99
                       :phone/by-id  {5 {:db/id 5 ::phone-number "555-4444"
                                         :ui/n  22}}}
        configured-db (f/add-form-config* state-map Person [:person/by-id 1])]
    (assertions
      "Removes form states of multiple entity-idents"
      (-> configured-db
        (f/delete-form-state* [[:person/by-id 1]
                               [:phone/by-id 5]])
        ::f/forms-by-ident)
      => {}
      "Removes form states of one entity-ident at a time"
      (-> configured-db
        (f/delete-form-state* [:person/by-id 1])
        (f/delete-form-state* [:phone/by-id 5])
        ::f/forms-by-ident)
      => {})))

(let [locale                                  {:db/id 22 ::country :US}
      locale                                  (f/add-form-config Locale locale)
      phone-numbers                           [{:db/id 2 ::phone-number "555-1212" ::locale locale} {:db/id 3 ::phone-number "555-1212"}]
      phone-number-forms                      (mapv #(f/add-form-config Phone %) phone-numbers)
      person                                  {:db/id 1 ::person-name "Bo" ::phone-numbers phone-number-forms}
      person-form                             (f/add-form-config Person person)
      state-map                               (prim/tree->db [{:the-person (prim/get-query Person)}] {:the-person person-form} true)
      validated-person                        (-> person-form
                                                (assoc-in [::f/config ::f/complete?] #{::person-name ::person-age})
                                                (assoc-in [::phone-numbers 0 ::f/config ::f/complete?] #{::phone-number})
                                                (assoc-in [::phone-numbers 0 ::locale ::f/config ::f/complete?] #{::country})
                                                (assoc-in [::phone-numbers 1 ::f/config ::f/complete?] #{::phone-number}))
      person-with-incomplete-name             (assoc-in validated-person [::f/config ::f/complete?] #{})
      person-with-incomplete-nested-form      (assoc-in validated-person [::phone-numbers 0 ::locale ::f/config ::f/complete?] #{})
      person-with-invalid-name                (assoc validated-person ::person-name "")
      person-with-invalid-nested-phone-locale (assoc-in validated-person [::phone-numbers 0 ::locale ::country] "England")
      person-ui-tree                          (fn [state id]
                                                (get
                                                  (prim/db->tree [{[:person/by-id id] (prim/get-query Person)}] state state)
                                                  [:person/by-id id]))
      new-phone-id                            (prim/tempid)
      new-phone-number                        {:db/id new-phone-id ::phone-number "444-111-3333"}
      new-phone-ident                         (prim/get-ident Phone new-phone-number)
      formified-phone                         (f/add-form-config Phone new-phone-number)
      edited-form-state-map                   (-> state-map
                                                (assoc-in [:phone/by-id new-phone-id] formified-phone)
                                                (assoc-in [:person/by-id 1 ::person-name] "New Name")
                                                (update-in [:person/by-id 1 ::phone-numbers] conj new-phone-ident)
                                                (assoc-in [:phone/by-id 3 ::phone-number] "555-9999"))
      new-person-id                           (prim/tempid)
      new-person-to-many                      (f/add-form-config Person {:db/id       new-person-id ::person-name "New"
                                                                         ::person-age 22 ::phone-numbers [new-phone-number]})
      new-person-to-one                       (f/add-form-config Person {:db/id       new-person-id ::person-name "New"
                                                                         ::person-age 22 ::phone-numbers new-phone-number})
      existing-person-no-child                (f/add-form-config Person {:db/id       1 ::person-name "New"
                                                                         ::person-age 22})]

  (specification "dirty-fields"
    (behavior "(as delta)"
      (let [delta (f/dirty-fields (person-ui-tree edited-form-state-map 1) true)]
        (assertions
          "Reports all fields of any entity with a temporary ID"
          (get-in delta [new-phone-ident ::phone-number :after]) => "444-111-3333"
          "Reports the modified fields of entities with a regular ID"
          (get-in delta [[:person/by-id 1] ::person-name :before]) => "Bo"
          (get-in delta [[:person/by-id 1] ::person-name :after]) => "New Name"
          (get-in delta [[:phone/by-id 3] ::phone-number :before]) => "555-1212"
          (get-in delta [[:phone/by-id 3] ::phone-number :after]) => "555-9999"

          "Includes the list of changes to subform idents"
          (get-in delta [[:person/by-id 1] ::phone-numbers :before]) => [[:phone/by-id 2] [:phone/by-id 3]]
          (get-in delta [[:person/by-id 1] ::phone-numbers :after]) => [[:phone/by-id 2] [:phone/by-id 3] [:phone/by-id new-phone-id]])))
    (behavior "(not as delta)"
      (let [delta (f/dirty-fields (person-ui-tree edited-form-state-map 1) false)]
        (assertions
          "Reports all fields of any entity with a temporary ID"
          (get-in delta [new-phone-ident ::phone-number]) => "444-111-3333"
          "Reports the modified fields of entities with a regular ID"
          (get-in delta [[:person/by-id 1] ::person-name]) => "New Name"
          (get-in delta [[:phone/by-id 3] ::phone-number]) => "555-9999"

          "Includes the list of changes to subform idents"
          (get-in delta [[:person/by-id 1] ::phone-numbers]) => [[:phone/by-id 2] [:phone/by-id 3] [:phone/by-id new-phone-id]])))
    (behavior "Brand new forms with relations"
      (assertions
        "Includes subform idents"
        (get-in (f/dirty-fields new-person-to-many false) [[:person/by-id new-person-id] ::phone-numbers]) => [[:phone/by-id new-phone-id]]
        (get-in (f/dirty-fields new-person-to-one false) [[:person/by-id new-person-id] ::phone-numbers]) => [:phone/by-id new-phone-id]))
    (behavior "Existing forms with empty relations"
      (assertions
        "Report empty list of changes"
        (f/dirty-fields existing-person-no-child false) => {}
        (f/dirty-fields existing-person-no-child true) => {})))

  (specification "dirty?"
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

  (specification "get-spec-validity"
    (behavior "is a UI (tree) operation for checking if the form (or fields) are valid. It:"
      (assertions
        "returns :unchecked if the fields have not been interacted with"
        (f/get-spec-validity person-form) => :unchecked
        "returns :valid if all fields are complete and valid"
        (f/get-spec-validity validated-person) => :valid
        "returns :unchecked if any field is not marked as complete"
        (f/get-spec-validity person-with-incomplete-name) => :unchecked
        "returns :unchecked if any NESTED fields are not marked as complete"
        (f/get-spec-validity person-with-incomplete-nested-form) => :unchecked
        "returns :invalid if any top-level property is invalid"
        (f/get-spec-validity person-with-invalid-name) => :invalid
        "returns :invalid if any nexted property is invalid"
        (f/get-spec-validity person-with-invalid-nested-phone-locale) => :invalid)))
  (specification "valid-spec?"
    (assertions
      "Returns true if validity is :valid"
      (f/valid-spec? validated-person) => true
      "Returns false if validity is :unchecked"
      (f/valid-spec? person-with-incomplete-nested-form) => false
      "Returns false if validity is :invalid"
      (f/valid-spec? person-with-invalid-name) => false))
  (specification "checked?"
    (assertions
      "Returns true if validity is :valid or :invalid"
      (f/checked? validated-person) => true
      (f/checked? person-with-invalid-name) => true
      (f/checked? person-with-invalid-nested-phone-locale) => true
      "Returns false if validity is :unchecked"
      (f/checked? person-with-incomplete-nested-form) => false))
  (specification "invalid?"
    (assertions
      "Returns true if validity is :invalid"
      (f/invalid-spec? person-with-invalid-name) => true
      (f/invalid-spec? person-with-invalid-nested-phone-locale) => true
      "Returns false if validity is :unchecked"
      (f/invalid-spec? person-with-incomplete-nested-form) => false
      "Returns false if validity is :valid"
      (f/invalid-spec? validated-person) => false))

  (specification "update-forms"
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

  (specification "mark-complete*"
    (behavior "is a state map operation that marks field(s) as complete, so validation checks can be applied"
      (let [get-person (fn [state id validate?] (let [validated-state (cond-> state
                                                                        validate? (f/mark-complete* [:person/by-id id]))]
                                                  (person-ui-tree validated-state id)))]
        (assertions
          "makes the form checked? = true"
          (f/checked? (get-person state-map 1 false)) => false
          (f/checked? (get-person state-map 1 true)) => true
          "valid forms become valid"
          (f/valid-spec? (get-person state-map 1 false)) => false
          (f/valid-spec? (get-person state-map 1 true)) => true))))

  (specification "pristine->entity*"
    (behavior "is a state map operation that recursively undoes any entity state changes that differ from pristine"
      (let [modified-state-map (-> state-map
                                 (assoc-in [:phone/by-id 3 ::phone-number] "111")
                                 (assoc-in [:locale/by-id 22 ::country] :UK)
                                 (assoc-in [:person/by-id 1 ::person-name] "Bobby"))
            reset-state-map    (f/pristine->entity* modified-state-map [:person/by-id 1])]
        (assertions
          (not= modified-state-map state-map) => true
          reset-state-map => state-map))))

  (specification "entity->pristine*"
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
