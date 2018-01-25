(ns fulcro.ui.form-state
  (:require [clojure.spec.alpha :as s]
    #?(:clj
            [clojure.future :refer :all])
            [fulcro.client.logging :as log]
            [fulcro.client.mutations :refer [defmutation]]
            [fulcro.util :as util]
            [fulcro.client.primitives :as prim :refer [defui defsc]]
            [fulcro.client.dom :as dom]))

(def ident-generator #(s/gen #{[:table 1] [:other/by-id 9]}))
(s/def ::id (s/with-gen util/ident? ident-generator))       ; form config uses the entity's ident as an ID
(s/def ::fields (s/every keyword? :kind set?))              ; a set of kws that are fields to track
(s/def ::subforms (s/every keyword? :kind set?))            ; a set of kws that are subforms
(s/def ::pristine-state (s/map-of keyword? any?))           ; the saved state of the form
(s/def ::complete? (s/every keyword? :kind set?))           ; the fields that have been interacted with
(s/def ::config (s/keys :req [::id ::fields] :opt [::pristine-state ::complete? ::subforms]))
(s/def ::field-tester (s/fspec
                        :args (s/cat :ui-entity (s/keys :req [::config]) :field (s/? keyword?))
                        :ret boolean?))
(s/def ::form-operation (s/fspec
                          :args (s/cat :entity map? :config ::config)
                          :ret (s/cat :entity map? :config ::config)))
(s/def ::validity #{:valid :invalid :unchecked})
(s/def ::denormalized-form (s/keys :req [::config]))
(s/def ::form-predicate
  (s/fspec
    :args (s/cat :form ::denormalized-form)
    :ret boolean?))

(defn form-id
  "Returns the form database table ID for the given entity ident."
  [entity-ident]
  {:table (first entity-ident)
   :row   (second entity-ident)})

(s/fdef form-id
  :args (s/cat :id util/ident?)
  :ret map?)

(defsc FormConfig
  "A component supporting normalization of form state configuration. Technically this component
  can also render the form config, if that is useful to you."
  [this {:keys [::id ::complete? ::fields ::subforms ::pristine-state]}]
  {:query [::id ::fields ::complete? ::subforms ::pristine-state]
   :ident (fn []
            [::forms-by-ident {:table (first id)
                               :row   (second id)}])}
  (dom/div nil
    (dom/h4 nil "Form Config")
    (dom/ul nil
      (dom/li nil (str "id" id))
      (dom/li nil (str "fields" fields))
      (dom/li nil (str "subforms" subforms))
      (dom/li nil (str "pristine-state" pristine-state)))))

(def ui-form-config
  "Render form config"
  (prim/factory FormConfig {:keyfn ::id}))

(def form-config-join "A query join to ::form-config." {::config (prim/get-query FormConfig)})

(defn form-config
  "Generate a form config given:

  entity-ident - The ident of the entity you're configuring forms for.
  fields - A set of keywords on the entity that is the form.
  subforms - An optional set of keywords on the entity that is the form, for the joins to subforms."
  ([entity-ident fields]
   (form-config entity-ident fields #{}))
  ([entity-ident fields subforms]
   {::id       entity-ident
    ::fields   fields
    ::subforms subforms}))

(s/fdef form-config
  :args (s/cat
          :id ::id
          :fields ::fields
          :subforms (s/? ::subforms))
  :ret ::config)

(defn add-form-config
  "Add form configuration data to a *normalized* entity (e.g. pre-merge). This is useful in
  initial state or when using `merge-component!`. This function *will not* touch an entity
  that already has form config.

  If you have subforms, you *must* call this function on each of them in order to add configuration
  to each of them, since this function is where you declare what fields have significance to the
  form state tracking.

  The form config requires namespaced keys (use f/form-config to make one):

  ::f/id - The ident of the entity your adding form configuration to.
  ::f/fields - A set of keywords that indicate which things on the entity are to be treated as fields
  ::f/subforms - A set of keywords that indicate which entity fields join to sub-forms (which must
  be initialized separately with this same call).

  Returns the (possibly updated) denormalized entity, ready to merge."
  [entity {:keys [::fields ::subforms] :as config}]
  (if (contains? entity ::config)
    entity
    (let [pristine-state (select-keys entity fields)]
      (merge entity {::config (assoc config ::pristine-state pristine-state
                                            ::subforms (or subforms #{}))}))))

(s/fdef add-form-config
  :args (s/cat :entity map? :config ::config)
  :ret (s/keys :req [::config]))

(defn add-form-config*
  "Identical to `add-form-config`, but works against normalized entities in the
  app state (usable from a mutation). This operation is *not* recursive since
  it requires different configuration data for each form.

  state-map - The application state database.
  entity-ident - The ident of the normalized entity to augment.
  form-config - The form configuration.

  Returns an updated state map with normalized form configuration in place for the entity."
  [state-map {:keys [::id ::fields ::subforms] :as config}]
  (let [normalized-entity (get-in state-map id)
        pristine-state    (select-keys normalized-entity fields)
        form-config       (assoc config ::pristine-state pristine-state
                                        ::subforms (or subforms #{}))
        config-ident      [::forms-by-ident (form-id id)]]
    (if (contains? normalized-entity ::config)
      state-map
      (-> state-map
        (assoc-in (conj id ::config) config-ident)
        (assoc-in config-ident form-config)))))

(s/fdef add-form-config*
  :args (s/cat :state map? :config ::config)
  :ret (s/keys :req [::config]))

(defn immediate-subforms
  "Get the instances of the immediate subforms that are joined into the given entity by
   subform-join-keys (works with to-one and to-many).

   entity - a denormalized (UI) entity.
   subform-join-keys - The keys of the subforms of this entity, as a set.

   Returns a sequence of those entities (all denormalized)."
  [entity subform-join-keys]
  (remove nil?
    (mapcat #(let [v (get entity %)]
               (if (sequential? v) v [v])) subform-join-keys)))

(s/fdef immediate-subforms
  :args (s/cat :entity map? :subform-join-keys set?)
  :ret (s/coll-of map?))

(defn dirty?
  "Returns true if the given ui-entity-props that are configured as a form differ from the pristine version.
  Recursively follows subforms if given no field. Returns true if anything doesn't match up.

  If given a field, it only checks that field."
  ([ui-entity-props field]
   (let [{{pristine-state ::pristine-state} ::config} ui-entity-props
         current  (get ui-entity-props field)
         original (get pristine-state field)]
     (not= current original)))
  ([ui-entity-props]
   (let [{:keys [::subforms ::fields]} (::config ui-entity-props)
         dirty-field?     (fn [k] (dirty? ui-entity-props k))
         subform-entities (immediate-subforms ui-entity-props subforms)]
     (boolean
       (or
         (some dirty-field? fields)
         (some dirty? subform-entities))))))

(s/def dirty? ::field-tester)

(defn no-spec-or-valid?
  "Returns true if the value is valid according to the spec for spec-key or there is no spec for it. Returns false
  if there is a spec and the value is not valid."
  [spec-key value]
  (or (not (s/get-spec spec-key))
    (s/valid? spec-key value)))

(s/fdef no-spec-or-valid?
  :args (s/cat :k keyword? :v any?)
  :ret boolean?)

(defn- merge-validity
  "Returns a new validity based on the combination of two.

  * :valid :valid = :valid
  * :valid :invalid = :invalid
  * :valid :unchecked = :unchecked
  * :invalid :valid = :invalid
  * :invalid :invalid = :invalid
  * :invalid :unchecked = :unchecked
  * :unchecked :valid = :unchecked
  * :unchecked :invalid = :unchecked
  * :unchecked :unchecked = :unchecked
  "
  [a b]
  (cond
    (or (= :unchecked a) (= :unchecked b)) :unchecked
    (and (= :valid a) (= :valid b)) :valid
    :otherwise :invalid))

(s/fdef merge-validity
  :args (s/cat :a ::validity :b ::validity)
  :ret ::validity)

(defn get-validity
  "Get the validity (:valid :invalid or :unchecked) for the given form/field.

  ui-entity-props : A denormalized (UI) entity, which can have subforms.
  field : Optional. Returns the validity of just the single field on the top-level form.

  Returns `:invalid` if all of the fields have been interacted with, and *any* are invalid.
  Returns `:unchecked` if any field is not yet been interacted with.

  Fields are marked as having been interacted with by programmatic action on your part via
  the validate* mutation helper can be used in a mutation to mark fields ready for validation.

  If given a field then it checks just that field."
  ([ui-entity-props field]
   (let [{{complete? ::complete?} ::config} ui-entity-props
         complete? (or complete? #{})]
     (cond
       (not (complete? field)) :unchecked
       (not (no-spec-or-valid? field (get ui-entity-props field))) :invalid
       :else :valid)))
  ([ui-entity-props]
   (let [{{:keys [::fields ::subforms ::complete?]} ::config} ui-entity-props
         immediate-subforms (immediate-subforms ui-entity-props subforms)
         field-validity     (fn [current-validity k] (merge-validity current-validity (get-validity ui-entity-props k)))
         subform-validities (map get-validity immediate-subforms)
         subform-validity   (reduce merge-validity :valid subform-validities)
         this-validity      (reduce field-validity :valid fields)]
     (merge-validity this-validity subform-validity))))

(s/def get-validity ::field-tester)

(defn valid?
  "Returns true if the denormalized (UI) form is :valid (recursively). Returns false if unchecked or invalid. Use checked?
  or `validity` for better detail. This function runs `get-validity` which is a recursive check. You may wish to call
  that function instead if your UI needs to make decisions across all three possible values."
  [ui-form]
  (= :valid (get-validity ui-form)))

(s/def valid? ::form-predicate)

(defn checked?
  "Returns true if the denormalized (UI) form has been checked for validation. Until this returns true the form will neither
  report :valid or :invalid as a validity, nor will valid? or invalid? return true. This function runs `get-validity` which is a recursive check. You may wish to call
  that function instead if your UI needs to make decisions across all three possible values."
  [ui-form]
  (not= :unchecked (get-validity ui-form)))

(s/def checked? ::form-predicate)

(defn invalid?
  "Returns true if the denormalized (UI) form is :invalid (recursively). Returns false if :unchecked or :invalid.
  Use checked? to detect if the form has been checked. Use the mutation helper `validate*` to validate the form. This function runs `get-validity` which is a recursive check. You may wish to call
  that function instead if your UI needs to make decisions across all three possible values."
  [ui-form]
  (= :invalid (get-validity ui-form)))

(s/def invalid? ::form-predicate)

(defn- immediate-subform-idents
  "Get the idents of the immediate subforms that are joined into entity by
   subform-join-keys (works with to-one and to-many). Entity is a NORMALIZED entity from the state map.

   Returns a sequence of those idents."
  [entity subform-join-keys]
  (remove nil?
    (mapcat (fn immediate-subform-idents-step [k]
              (let [v      (get entity k)
                    result (cond
                             (and (sequential? v) (every? util/ident? v)) v
                             (util/ident? v) [v]
                             :else [])]
                result)) subform-join-keys)))

(s/fdef immediate-subform-idents
  :args (s/cat :entity map? :ks (s/coll-of keyword :kind set?))
  :ret (s/coll-of util/ident?))

(defn update-forms
  "Recursively update a form and its subforms. This function works against the state database (normalized state).

  state-map : The application state map
  xform : A function (fn [entity form-config] [entity' form-config']) that is passed the normalized entity and form-config,
    and must return an updated version of them. Should not affect fields that are not involved in that specific level of the form.
  starting-entity-ident : An ident in the state map of an entity that has been initialized as a form.

  Returns the updated state map."
  [state-map xform starting-entity-ident]
  (let [entity         (get-in state-map starting-entity-ident)
        config-ident   (get entity ::config)
        config         (get-in state-map config-ident)
        {:keys [::subforms]} config
        [updated-entity updated-config] (xform entity config)
        subform-idents (immediate-subform-idents (get-in state-map starting-entity-ident) subforms)]
    (as-> state-map sm
      (assoc-in sm starting-entity-ident updated-entity)
      (assoc-in sm config-ident updated-config)
      (reduce (fn [s id]
                (update-forms s xform id)) sm subform-idents))))

(s/fdef update-forms
  :args (s/cat :state map? :xform ::form-operation :ident util/ident?)
  :ret map?)

(defn dirty-fields
  "Obtains all of the dirty fields for the given (denormalized) ui-entity, recursively.

  ui-entity - The entity (denormalized) from the UI
  as-delta? - If false, each field's reported (new) value will just be the new value. When true, each value will be a map with :before and :after keys
  with the old and new values (useful for optimistic transaction semantics).

  Returns a map keyed by form ID (for each form/subform) whose values are maps that given key/value pairs of
  changes. Fields containing temporary IDs will always be included.

  In other words, a change that happened for an entity with ident `entity-ident` on field `:field`:

  With `as-delta?` true:

  ```
  {entity-ident {:field {:before 1 :after 2}}}
  ```

  with `as-delta?` false:

  ```
  {entity-ident {:field 2}}
  ```
  "
  [ui-entity as-delta?]
  (let [{:keys [::id ::fields ::pristine-state ::subforms] :as config} (get ui-entity ::config)
        delta              (into {} (keep (fn [k]
                                            (let [before (get pristine-state k)
                                                  after  (get ui-entity k)]
                                              (if (not= before after)
                                                (if as-delta?
                                                  [k {:before before :after after}]
                                                  [k after])
                                                nil))) fields))
        local-dirty-fields {id delta}
        complete-delta     (reduce
                             (fn [dirty-fields-so-far subform-join-field]
                               (let [subform              (get ui-entity subform-join-field)
                                     dirty-subform-fields (dirty-fields subform as-delta?)]
                                 (merge dirty-fields-so-far dirty-subform-fields)))
                             local-dirty-fields
                             subforms)]
    complete-delta))

(s/fdef dirty-fields
  :args (s/cat :entity ::denormalized-form :delta boolean?)
  :ret map?)

(defn validate*
  "Mark the fields complete so that validation checks will return values. This function works on a app state database
  map (not atom) and is meant to be composed into mutations. See the `validate!` mutation if you do not need to combine
  this with other operations.

  Follows the subforms recursively through state, unless a specific field is given."
  ([state-map entity-ident field]
   (let [form-config-path (conj entity-ident ::config)
         form-config-path (if (util/ident? (get-in state-map form-config-path))
                            (get-in state-map form-config-path)
                            (do
                              (log/error (str "FORM NOT NORMALIZED: " entity-ident))
                              form-config-path))
         legal-fields     (get-in state-map (conj form-config-path ::fields) #{})
         complete-path    (conj form-config-path ::complete?)]
     (update-in state-map complete-path (fnil conj #{}) field)))
  ([state-map entity-ident]
   (update-forms state-map
     (fn validate*-step [e form-config]
       [e (assoc form-config ::complete? (::fields form-config))]) entity-ident)))

(s/fdef validate*
  :args (s/cat :state map? :entity-ident util/ident? :field (s/? keyword?))
  :ret map?)

(defn pristine->entity*
  "Copy the pristine state over top of the originating entity of the given form. Meant to be used inside of a
  mutation. Recursively follows subforms in app state. Returns the new app state map.

  state-map - The normalized state database (map, not atom)
  entity-ident - The ident of the entity that you wish to restore to its original pristine state

  Only affects declared fields and sub-forms."
  [state-map entity-ident]
  (update-forms state-map (fn reset-form-step [e {:keys [::pristine-state] :as config}]
                            [(merge e pristine-state) config]) entity-ident))

(s/fdef pristine->entity*
  :args (s/cat :state map? :entity-ident util/ident?)
  :ret map?)

(defn entity->pristine*
  "Overwrite the pristine state (form state's copy) of the entity. This is meant to be used from a mutation
  to update the form state tracking recursively to make the form as 'unmodified'. That is to say, as if you
  committed the values to the server, and the current entity state is now the pristine state.

  This function does no sanity checks, so you should ensure the entity is valid!

  Recursively updates all sub forms.

  Returns the updated state-map (database)."
  [state-map entity-ident]
  (update-forms state-map (fn commit-form-step [e {:keys [::fields] :as config}]
                            (let [new-pristine-state (select-keys e fields)]
                              [e (assoc config ::pristine-state new-pristine-state)])) entity-ident))

(s/fdef entity->pristine*
  :args (s/cat :state map? :entity-ident util/ident?)
  :ret map?)

(defmutation reset-form!
  "Mutation: Reset the form (recursively) to its (last recorded) pristine state. Requires the form's ident. See `pristine->entity*` for a function
   you can compose into your own mutations."
  [{:keys [form-ident]}]
  (action [{:keys [state]}]
    (swap! state pristine->entity* form-ident)))

(defmutation validate!
  "Mutation: Trigger validation for an entire (recursively) form, or just a field (optional). See `validate*`
  for a function you can compose into your own mutations."
  [{:keys [entity-ident field]}]
  (action [{:keys [state]}]
    (if field
      (swap! state validate* entity-ident field)
      (swap! state validate* entity-ident))))
