(ns com.fulcrologic.fulcro.algorithms.form-state-spec
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]
    [fulcro-spec.core :refer [behavior specification assertions component when-mocking provided]]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]))

(declare =>)

(defsc Locale [this props]
  {:query       [:db/id ::country fs/form-config-join]
   :ident       [:locale/by-id :db/id]
   :form-fields #{::country}})

(s/def ::country keyword?)

(defsc Phone [this props]
  {:query       [:db/id {::locale (comp/get-query Locale)}
                 ::phone-number
                 fs/form-config-join]
   :ident       [:phone/id :db/id]
   :form-fields #{::locale ::phone-number}})

(defsc UnusedForm [this props]
  {:query       [:db/id ::data fs/form-config-join]
   :ident       [:unused/by-id :db/id]
   :form-fields #{::data}})

(s/def ::phone-number (s/and string? #(re-matches #"[-0-9()]+" %)))

(defsc Person [this props]
  {:query       [:db/id ::person-name ::person-age
                 {::unused (comp/get-query UnusedForm)}
                 {::phone-numbers (comp/get-query Phone)}
                 fs/form-config-join]
   :ident       [:person/id :db/id]
   :form-fields #{::person-name ::unused ::person-age ::phone-numbers}})

(defsc NonForm [this props]
  {:query       [:id :x]
   :ident       [:ntop :id]
   :form-fields #{:ntop}})

(defsc FormNoFields [this props]
  {:query [:id :x fs/form-config-join]
   :ident [:ntop :id]})

(defsc BadlyNestedForm [this props]
  {:query       [:id :name {:thing (comp/get-query NonForm)} fs/form-config-join]
   :ident       [:top :id]
   :form-fields #{:name :thing}})

(s/def ::person-name (s/and string? #(not (empty? (str/trim %)))))

(specification "add-form-config"
  (component "returns the entity with added configuration data, where:"
    (let [data-tree       {:db/id          1
                           ::person-name   "Joe"
                           ::phone-numbers [{:db/id   2 ::phone-number "555-1212"
                                             ::locale {:db/id 5 ::country :US}}]}
          configured-form (fs/add-form-config Person data-tree)
          form-config     (get configured-form ::fs/config)]
      (assertions
        "::f/config is a spec-valid config"
        (s/valid? ::fs/config form-config) => true
        (s/explain-data ::fs/config form-config) => nil
        "the original entity fields are unchanged"
        (-> configured-form
          (dissoc ::fs/config)
          (update-in [::phone-numbers 0] dissoc ::fs/config)
          (update-in [::phone-numbers 0 ::locale] dissoc ::fs/config)) => data-tree
        "the original fields (and subform idents) are saved to pristine state"
        (::fs/pristine-state form-config) => {::person-name   "Joe"
                                              ::phone-numbers [[:phone/id 2]]}
        "the entity's ident is the form's ID"
        (get-in configured-form [::fs/config ::fs/id]) => [:person/id 1]
        "has the scalar declared fields"
        (get-in configured-form [::fs/config ::fs/fields]) => #{::person-name ::person-age}
        "data about each populated subform is included (recursively)"
        (some-> form-config ::fs/subforms ::phone-numbers meta :component) => Phone
        (some-> configured-form ::phone-numbers first ::fs/config ::fs/subforms ::locale meta :component) => Locale
        "data about empty subforms is included"
        (some-> form-config ::fs/subforms ::unused meta :component) => UnusedForm
        "each subform is recursively initialized"
        (get-in configured-form [::phone-numbers 0 ::fs/config ::fs/id]) => [:phone/id 2]
        (get-in configured-form [::phone-numbers 0 ::locale ::fs/config ::fs/id]) => [:locale/by-id 5])))
  (component "error checking"
    (let [data-tree {:id 1 :name "A" :thing {:id 2 :x 42}}]
      (assertions
        "throws an exception if the target fails to query for form config"
        (fs/add-form-config NonForm data-tree) =throws=> #"to .*NonForm, but it does not query for config"
        "throws an exception if the target fails to declare fields"
        (fs/add-form-config FormNoFields data-tree) =throws=> #"to .*FormNoFields, but it does not declare any fields"
        "does recursive checks on subforms"
        (fs/add-form-config BadlyNestedForm data-tree) =throws=> #"Subform .*NonForm of .*BadlyNestedForm"))))

(specification "add-form-config*"
  (let [state-map         {:person/id {1 {:db/id          1 ::person-name "Joe" :ui/checked? true
                                          ::phone-numbers [[:phone/id 5]]}}
                           :root-prop 99
                           :phone/id  {5 {:db/id 5 ::phone-number "555-4444"
                                          :ui/n  22}}}
        configured-db     (fs/add-form-config* state-map Person [:person/id 1])
        fconfig-id-person [::fs/forms-by-ident (fs/form-id [:person/id 1])]
        fconfig-id-phone  [::fs/forms-by-ident (fs/form-id [:phone/id 5])]]
    (assertions
      "Adds for configuration to normalized tables"
      (get-in configured-db [:person/id 1 ::fs/config]) => fconfig-id-person
      (get-in configured-db [:phone/id 5 ::fs/config]) => fconfig-id-phone
      (get-in configured-db fconfig-id-person) =fn=> (fn [c] (contains? c ::fs/id))
      "leaves existing (non-form) data alone"
      (get-in configured-db [:person/id 1 :ui/checked?]) => true
      (get-in configured-db [:phone/id 5 :ui/n]) => 22)))

(specification "delete-form-state*"
  (let [state-map     {:person/id {1 {:db/id          1 ::person-name "Joe" :ui/checked? true
                                      ::phone-numbers [[:phone/id 5]]}}
                       :root-prop 99
                       :phone/id  {5 {:db/id 5 ::phone-number "555-4444"
                                      :ui/n  22}}}
        configured-db (fs/add-form-config* state-map Person [:person/id 1])]
    (assertions
      "Removes form states of multiple entity-idents"
      (-> configured-db
        (fs/delete-form-state* [[:person/id 1]
                                [:phone/id 5]])
        ::fs/forms-by-ident)
      => {}
      "Removes form states of one entity-ident at a time"
      (-> configured-db
        (fs/delete-form-state* [:person/id 1])
        (fs/delete-form-state* [:phone/id 5])
        ::fs/forms-by-ident)
      => {})))

(let [locale                                  {:db/id 22 ::country :US}
      locale                                  (fs/add-form-config Locale locale)
      phone-numbers                           [{:db/id 2 ::phone-number "555-1212" ::locale locale} {:db/id 3 ::phone-number "555-1212"}]
      phone-number-forms                      (mapv #(fs/add-form-config Phone %) phone-numbers)
      person                                  {:db/id 1 ::person-name "Bo" ::phone-numbers phone-number-forms}
      person-form                             (fs/add-form-config Person person)
      state-map                               (fnorm/tree->db [{:the-person (comp/get-query Person)}] {:the-person person-form} true)
      validated-person                        (-> person-form
                                                (assoc-in [::fs/config ::fs/complete?] #{::person-name ::person-age})
                                                (assoc-in [::phone-numbers 0 ::fs/config ::fs/complete?] #{::phone-number})
                                                (assoc-in [::phone-numbers 0 ::locale ::fs/config ::fs/complete?] #{::country})
                                                (assoc-in [::phone-numbers 1 ::fs/config ::fs/complete?] #{::phone-number}))
      person-with-incomplete-name             (assoc-in validated-person [::fs/config ::fs/complete?] #{})
      person-with-incomplete-nested-form      (assoc-in validated-person [::phone-numbers 0 ::locale ::fs/config ::fs/complete?] #{})
      person-with-invalid-name                (assoc validated-person ::person-name "")
      person-with-invalid-nested-phone-locale (assoc-in validated-person [::phone-numbers 0 ::locale ::country] "England")
      person-ui-tree                          (fn [state id]
                                                (get
                                                  (fdn/db->tree [{[:person/id id] (comp/get-query Person)}] state state)
                                                  [:person/id id]))
      new-phone-id                            (tempid/tempid)
      new-phone-number                        {:db/id new-phone-id ::phone-number "444-111-3333"}
      existing-phone-number                   {:db/id 10 ::phone-number "444-111-3333"}
      new-phone-ident                         (comp/get-ident Phone new-phone-number)
      formified-phone                         (fs/add-form-config Phone new-phone-number)
      edited-form-state-map                   (-> state-map
                                                (assoc-in [:phone/id new-phone-id] formified-phone)
                                                (assoc-in [:person/id 1 ::person-name] "New Name")
                                                (update-in [:person/id 1 ::phone-numbers] conj new-phone-ident)
                                                (assoc-in [:phone/id 3 ::phone-number] "555-9999"))
      new-person-id                           (tempid/tempid)
      new-person-to-many                      (fs/add-form-config Person {:db/id       new-person-id ::person-name "New"
                                                                          ::person-age 22 ::phone-numbers [new-phone-number]})
      new-person-to-one                       (fs/add-form-config Person {:db/id       new-person-id ::person-name "New"
                                                                          ::person-age 22 ::phone-numbers new-phone-number})
      existing-person-no-child                (fs/add-form-config Person {:db/id       1 ::person-name "Existing"
                                                                          ::person-age 22})
      existing-person-to-one                  (fs/add-form-config Person {:db/id       1 ::person-name "Existing"
                                                                          ::person-age 22 ::phone-numbers existing-phone-number})]

  (specification "dirty-fields"
    (behavior "(as delta)"
      (let [delta (fs/dirty-fields (person-ui-tree edited-form-state-map 1) true)]
        (assertions
          "Reports all fields of any entity with a temporary ID"
          (get-in delta [new-phone-ident ::phone-number :after]) => "444-111-3333"
          "Reports the modified fields of entities with a regular ID"
          (get-in delta [[:person/id 1] ::person-name :before]) => "Bo"
          (get-in delta [[:person/id 1] ::person-name :after]) => "New Name"
          (get-in delta [[:phone/id 3] ::phone-number :before]) => "555-1212"
          (get-in delta [[:phone/id 3] ::phone-number :after]) => "555-9999"

          "Includes the list of changes to subform idents"
          (get-in delta [[:person/id 1] ::phone-numbers :before]) => [[:phone/id 2] [:phone/id 3]]
          (get-in delta [[:person/id 1] ::phone-numbers :after]) => [[:phone/id 2] [:phone/id 3] [:phone/id new-phone-id]])))
    (behavior "(not as delta)"
      (let [delta (fs/dirty-fields (person-ui-tree edited-form-state-map 1) false)]
        (assertions
          "Reports all fields of any entity with a temporary ID"
          (get-in delta [new-phone-ident ::phone-number]) => "444-111-3333"
          "Reports the modified fields of entities with a regular ID"
          (get-in delta [[:person/id 1] ::person-name]) => "New Name"
          (get-in delta [[:phone/id 3] ::phone-number]) => "555-9999"

          "Includes the list of changes to subform idents"
          (get-in delta [[:person/id 1] ::phone-numbers]) => [[:phone/id 2] [:phone/id 3] [:phone/id new-phone-id]])))
    (behavior "(new-entity? flag)"
      (let [delta (fs/dirty-fields existing-person-to-one true {:new-entity? true})]
        (assertions
          "Reports all entity fields"
          (get-in delta [[:person/id 1] ::person-name :after]) => "Existing"
          (get-in delta [[:person/id 1] ::person-age :after]) => 22
          "Reports all nested entity fields"
          (get-in delta [[:phone/id 10] ::phone-number :after]) => "444-111-3333")))
    (behavior "Brand new forms with relations"
      (assertions
        "Includes subform idents"
        (get-in (fs/dirty-fields new-person-to-many false) [[:person/id new-person-id] ::phone-numbers]) => [[:phone/id new-phone-id]]
        (get-in (fs/dirty-fields new-person-to-one false) [[:person/id new-person-id] ::phone-numbers]) => [:phone/id new-phone-id]))
    (behavior "Existing forms with empty relations"
      (assertions
        "Report empty list of changes"
        (fs/dirty-fields existing-person-no-child false) => {}
        (fs/dirty-fields existing-person-no-child true) => {})))

  (specification "dirty?"
    (behavior "is a UI (tree) operation for checking if the form has been modified from pristine"
      (let [new-phone-number      {:db/id 4 ::phone-number "888-1212"}
            new-phone-number-form (fs/add-form-config Phone new-phone-number)]
        (assertions
          "is false if there are no changes"
          (fs/dirty? person-form) => false
          "is true if the data has changed in the top-level form"
          (fs/dirty? (assoc person-form ::person-name "New name")) => true
          "is true if any subform item has changed"
          (fs/dirty? (assoc-in person-form [::phone-numbers 0 ::phone-number] "555-1111")) => true
          (fs/dirty? (assoc-in person-form [::phone-numbers 0 ::locale ::country] :MX)) => true
          (fs/dirty? (assoc-in person-form [::phone-numbers 1 ::phone-number] "555-1111")) => true
          "is true if new subform item is added"
          (fs/dirty? (update person-form ::phone-numbers conj new-phone-number-form)) => true
          "is true if new subform item is removed"
          (fs/dirty? (assoc person-form ::phone-numbers [(first phone-number-forms)])) => true))))

  (specification "get-spec-validity"
    (behavior "is a UI (tree) operation for checking if the form (or fields) are valid. It:"
      (assertions
        "returns :unchecked if the fields have not been interacted with"
        (fs/get-spec-validity person-form) => :unchecked
        "returns :valid if all fields are complete and valid"
        (fs/get-spec-validity validated-person) => :valid
        "returns :unchecked if any field is not marked as complete"
        (fs/get-spec-validity person-with-incomplete-name) => :unchecked
        "returns :unchecked if any NESTED fields are not marked as complete"
        (fs/get-spec-validity person-with-incomplete-nested-form) => :unchecked
        "returns :invalid if any top-level property is invalid"
        (fs/get-spec-validity person-with-invalid-name) => :invalid
        "returns :invalid if any nexted property is invalid"
        (fs/get-spec-validity person-with-invalid-nested-phone-locale) => :invalid)))
  (specification "valid-spec?"
    (assertions
      "Returns true if validity is :valid"
      (fs/valid-spec? validated-person) => true
      "Returns false if validity is :unchecked"
      (fs/valid-spec? person-with-incomplete-nested-form) => false
      "Returns false if validity is :invalid"
      (fs/valid-spec? person-with-invalid-name) => false))
  (specification "checked?"
    (assertions
      "Returns true if validity is :valid or :invalid"
      (fs/checked? validated-person) => true
      (fs/checked? person-with-invalid-name) => true
      (fs/checked? person-with-invalid-nested-phone-locale) => true
      "Returns false if validity is :unchecked"
      (fs/checked? person-with-incomplete-nested-form) => false))
  (specification "invalid?"
    (assertions
      "Returns true if validity is :invalid"
      (fs/invalid-spec? person-with-invalid-name) => true
      (fs/invalid-spec? person-with-invalid-nested-phone-locale) => true
      "Returns false if validity is :unchecked"
      (fs/invalid-spec? person-with-incomplete-nested-form) => false
      "Returns false if validity is :valid"
      (fs/invalid-spec? validated-person) => false))

  (specification "update-forms"
    (behavior "Allows one to traverse a nested form set in the app state database and apply xforms to the form and config"
      (let [updated-state (fs/update-forms state-map (fn [e c] [(assoc e ::touched true) (assoc c ::touched true)]) [:person/id 1])]
        (assertions
          "Touches the top-level form config"
          (get-in updated-state [::fs/forms-by-ident (fs/form-id [:person/id 1]) ::touched]) => true
          "Touches the nested form configs"
          (get-in updated-state [::fs/forms-by-ident (fs/form-id [:phone/id 2]) ::touched]) => true
          (get-in updated-state [::fs/forms-by-ident (fs/form-id [:phone/id 3]) ::touched]) => true
          (get-in updated-state [::fs/forms-by-ident (fs/form-id [:locale/by-id 22]) ::touched]) => true
          "Touches the top-level entity"
          (get-in updated-state [:person/id 1 ::touched]) => true
          "Touches the nested form  entities"
          (get-in updated-state [:phone/id 2 ::touched]) => true
          (get-in updated-state [:phone/id 3 ::touched]) => true
          (get-in updated-state [:locale/by-id 22 ::touched]) => true))))

  (specification "mark-complete*"
    (behavior "is a state map operation that marks field(s) as complete, so validation checks can be applied"
      (let [get-person (fn [state id validate?] (let [validated-state (cond-> state
                                                                        validate? (fs/mark-complete* [:person/id id]))]
                                                  (person-ui-tree validated-state id)))]
        (assertions
          "makes the form checked? = true"
          (fs/checked? (get-person state-map 1 false)) => false
          (fs/checked? (get-person state-map 1 true)) => true
          "valid forms become valid"
          (fs/valid-spec? (get-person state-map 1 false)) => false
          (fs/valid-spec? (get-person state-map 1 true)) => true))))

  (specification "pristine->entity*"
    (behavior "is a state map operation that recursively undoes any entity state changes that differ from pristine"
      (let [modified-state-map (-> state-map
                                 (assoc-in [:phone/id 3 ::phone-number] "111")
                                 (assoc-in [:locale/by-id 22 ::country] :UK)
                                 (assoc-in [:person/id 1 ::person-age] 42)
                                 (assoc-in [:person/id 1 ::person-name] "Bobby"))
            reset-state-map    (fs/pristine->entity* modified-state-map [:person/id 1])
            actual-person      (get-in reset-state-map [:person/id 1])
            expected-person    (get-in state-map [:person/id 1])]
        (assertions
          "The modified flag returns to normal"
          (not= modified-state-map state-map) => true
          "Fields that were missing are properly removed"
          (contains? actual-person ::person-age) => false
          "Recursive fields have returned to their original value"
          (get-in reset-state-map [:phone/id 3 ::phone-number]) => "555-1212"
          "Top-level Fields that changed have returned to their original values"
          (::person-name actual-person) => (::person-name expected-person)))))

  (specification "entity->pristine*"
    (behavior "is a state map operation that recursively updates any entity pristine form state so that the form is no longer dirty"
      (let [modified-state-map  (-> state-map
                                  (update-in [:phone/id 3] dissoc ::phone-number)
                                  (assoc-in [:locale/by-id 22 ::country] :UK)
                                  (assoc-in [:person/id 1 ::person-name] "Bobby"))
            modified-ui-tree    (person-ui-tree modified-state-map 1)
            committed-state-map (fs/entity->pristine* modified-state-map [:person/id 1])
            committed-ui-tree   (person-ui-tree committed-state-map 1)]
        (assertions
          "committing transitions dirty -> clean"
          (fs/dirty? modified-ui-tree) => true
          (fs/dirty? committed-ui-tree) => false
          "The pristine form state has the new data"
          (get-in committed-state-map [::fs/forms-by-ident {:table :person/id, :row 1} ::fs/pristine-state])
          => {::person-name "Bobby" ::phone-numbers [[:phone/id 2] [:phone/id 3]]}

          (get-in committed-state-map [::fs/forms-by-ident {:table :locale/by-id, :row 22} ::fs/pristine-state])
          => {::country :UK}

          "Removes things from the clean version that disappeared"
          (contains? (get-in committed-state-map [::fs/forms-by-ident {:table :phone/id, :row 3} ::fs/pristine-state])
            ::phone-number) => false

          "the clean version has the updated data"
          (get-in committed-ui-tree [::person-name]) => "Bobby"
          (get-in committed-ui-tree [::phone-numbers 0 ::locale ::country]) => :UK)))))

(defsc SomeEntity [_ _]
  {:query [:entity/id]
   :ident :entity/id})

(defsc FormPickingEntity [_ _]
  {:query       [:form/id :form/field {:form/entity (comp/get-query SomeEntity)} fs/form-config-join]
   :ident       :form/id
   :form-fields #{:form/field :form/entity}})

(specification "Working with joins to entities that are meant to be selected"
  (let [initial-form (fs/add-form-config FormPickingEntity {:form/id     1
                                                            :form/field  "A"
                                                            :form/entity {:entity/id 22}})
        updated-form (assoc initial-form :form/entity {:entity/id 23})]
    (assertions
      "Reports no dirty fields on a pristine form"
      (fs/dirty-fields initial-form true) => {}
      "Reports the updated target ident if the entity changes"
      (fs/dirty-fields updated-form true) => {[:form/id 1]
                                              {:form/entity {:before [:entity/id 22]
                                                             :after  [:entity/id 23]}}})))

(specification "Working with joins to entities that are meant to be selected (to-many)"
  (let [initial-form (fs/add-form-config FormPickingEntity {:form/id     1
                                                            :form/field  "A"
                                                            :form/entity [{:entity/id 22} {:entity/id 23}]})
        updated-form (assoc initial-form :form/entity [{:entity/id 23}])]
    (assertions
      "Reports no dirty fields on a pristine form"
      (fs/dirty-fields initial-form true) => {}
      "Reports the updated target ident if the entity changes"
      (fs/dirty-fields updated-form true) => {[:form/id 1]
                                              {:form/entity {:before [[:entity/id 22] [:entity/id 23]]
                                                             :after  [[:entity/id 23]]}}})))
