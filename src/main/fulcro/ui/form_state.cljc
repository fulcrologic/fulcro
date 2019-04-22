(ns fulcro.ui.form-state
  (:require [clojure.spec.alpha :as s]
            [clojure.set :as set]
            [fulcro.logging :as log]
            [fulcro.client.mutations :refer [defmutation]]
            [fulcro.util :as util]
            [fulcro.client.primitives :as prim :refer [defui defsc]]))

(defprotocol IFormFields
  (form-fields [this] "Returns a set of keywords that define the form fields of interest on an entity"))

(defn get-form-fields [class]
  #?(:clj  (when-let [ff (-> class meta :form-fields)]
             (ff class))
     :cljs (when (implements? IFormFields class)
             (form-fields class))))

(def ident-generator #(s/gen #{[:table 1] [:other/by-id 9]}))
(s/def ::id (s/with-gen util/ident? ident-generator))       ; form config uses the entity's ident as an ID
(s/def ::fields (s/every keyword? :kind set?))              ; a set of kws that are fields to track
(s/def ::subforms (s/map-of keyword? any?))                 ; a map of subform field to component class
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

(defn- assume-field [props field]
  (when-not (and (seq props) (contains? (some-> props ::config ::fields) field))
    (log/error (str "It appears you're using " field " as a form field, but it is *not* declared as a form field "
                 "on the component. It will fail to function properly (perhaps you forgot to initialize via `add-form-config`)."))))

(defn form-id
  "Returns the form database table ID for the given entity ident."
  [entity-ident]
  {:table (first entity-ident)
   :row   (second entity-ident)})

(s/fdef form-id
  :args (s/cat :id util/ident?)
  :ret map?)

(defsc FormConfig
  "A component supporting normalization of form state configuration. Use Fulcro Inspect for viewing that data.
  Rendering isn't supported on this component so it will work with React Native.
  can also render the form config, if that is useful to you."
  [this {:keys [::id ::complete? ::fields ::subforms ::pristine-state]}]
  {:query [::id ::fields ::complete? ::subforms ::pristine-state]
   :ident (fn []
            [::forms-by-ident {:table (first id)
                               :row   (second id)}])})

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
   (form-config entity-ident fields {}))
  ([entity-ident fields subforms]
   {::id       entity-ident
    ::fields   fields
    ::subforms (into {}
                 (map (fn [[k v]] {k (with-meta {} {:component v})}))
                 subforms)}))

(s/fdef form-config
  :args (s/cat
          :id ::id
          :fields ::fields
          :subforms (s/? ::subforms))
  :ret ::config)

(defn- derive-form-info [class]
  (let [query-nodes         (some-> class
                              (prim/get-query)
                              (prim/query->ast)
                              :children)
        query-nodes-by-key  (into {}
                              (map (fn [n] [(:dispatch-key n) n]))
                              query-nodes)
        join-component      (fn [k] (get-in query-nodes-by-key [k :component]))
        {props :prop joins :join} (group-by :type query-nodes)
        join-keys           (->> joins (map :dispatch-key) set)
        prop-keys           (->> props (map :dispatch-key) set)
        queries-for-config? (contains? join-keys ::config)
        all-fields          (get-form-fields class)
        has-fields?         (seq all-fields)
        fields              (set/intersection all-fields prop-keys)
        subform-keys        (set/intersection all-fields join-keys)
        subforms            (into {}
                              (map (fn [k] [k (with-meta {} {:component (join-component k)})]))
                              subform-keys)]
    (when-not queries-for-config?
      (throw (ex-info (str "Attempt to add form configuration to " (prim/component-name class) ", but it does not query for config!")
               {:offending-component class})))
    (when-not has-fields?
      (throw (ex-info (str "Attempt to add form configuration to " (prim/component-name class) ", but it does not declare any fields!")
               {:offending-component class})))
    [fields subforms subform-keys]))

(defn add-form-config
  "Add form configuration data to a *denormalized* entity (e.g. pre-merge). This is useful in
  initial state or when using `merge-component!`. This function *will not* touch an entity
  that already has form config but will recurse the entire form set. It can therefore be
  invoked on the top-level of the form set when adding, for example, an instance of a sub-form.

  class - The component class
  entity - A denormalized (tree) of data that matches the given component class.

  Returns the (possibly updated) denormalized entity, ready to merge."
  [class entity]
  (let [[fields subform-classmap subform-keys] (derive-form-info class)
        local-entity (if (contains? entity ::config)
                       entity
                       (let [pristine-state (select-keys entity fields)
                             subform-ident  (fn [k entity] (some-> (get subform-classmap k) meta :component (prim/get-ident entity)))
                             subform-keys   (-> subform-classmap keys set)
                             subform-refs   (reduce
                                              (fn [refs k]
                                                (let [items (get entity k)]
                                                  (cond
                                                    ; to-one
                                                    (map? items) (assoc refs k (subform-ident k items))
                                                    ; to-many
                                                    (vector? items) (assoc refs k (mapv #(subform-ident k %) items))
                                                    :else refs)))
                                              {}
                                              subform-keys)
                             pristine-state (merge pristine-state subform-refs)
                             config         {::id             (prim/get-ident class entity)
                                             ::fields         fields
                                             ::pristine-state pristine-state
                                             ::subforms       (or subform-classmap {})}]
                         (merge entity {::config config})))]
    (reduce
      (fn [resulting-entity k]
        (let [c     (some-> subform-classmap (get k) meta :component)
              child (get resulting-entity k)]
          (try
            (cond
              (and c child (vector? child)) (assoc resulting-entity k (mapv #(add-form-config c %) child))
              (and c child) (assoc resulting-entity k (add-form-config c child))
              :else resulting-entity)
            (catch #?(:clj Exception :cljs :default) e
              (throw (ex-info (str "Subform " (prim/component-name c) " of " (prim/component-name class) " failed to initialize.")
                       {:nested-exception e}))))))
      local-entity
      subform-keys)))

(s/fdef add-form-config
  :args (s/cat :class any? :entity map?)
  :ret (s/keys :req [::config]))

(defn add-form-config*
  "Identical to `add-form-config`, but works against normalized entities in the
  app state. This makes it ideal for composition within mutations.

  state-map - The application state database (map, not atom).
  class - The component class. Must have declared form fields.
  entity-ident - The ident of the normalized entity of the given class that you wish to initialize.

  Returns an updated state map with normalized form configuration in place for the entity."
  [state-map class entity-ident]
  (let [[fields subform-classmap subform-keys] (derive-form-info class)
        entity            (get-in state-map entity-ident)
        updated-state-map (if (contains? entity ::config)
                            state-map
                            (let [pristine-state (select-keys entity (set/union subform-keys fields))
                                  config         {::id             entity-ident
                                                  ::fields         fields
                                                  ::pristine-state pristine-state
                                                  ::subforms       (or subform-classmap {})}
                                  cfg-ident      [::forms-by-ident (form-id entity-ident)]]
                              (-> state-map
                                (assoc-in cfg-ident config)
                                (assoc-in (conj entity-ident ::config) cfg-ident))))]
    (reduce
      (fn [smap subform-key]
        (let [subform-class  (some-> subform-classmap (get subform-key) meta :component)
              subform-target (get entity subform-key)]
          (try
            (cond
              (and subform-class subform-target (every? util/ident? subform-target))
              (reduce (fn [s subform-ident] (add-form-config* s subform-class subform-ident)) smap subform-target)

              (and subform-class (util/ident? subform-target))
              (add-form-config* smap subform-class subform-target)

              :else smap)
            (catch #?(:clj Exception :cljs :default) e
              (throw (ex-info (str "Subform " (prim/component-name subform-class) " of " (prim/component-name class) " failed to initialize.")
                       {:nested-exception e}))))))
      updated-state-map
      subform-keys)))

(s/fdef add-form-config*
  :args (s/cat :state map? :class any? :ident ::id)
  :ret map?)

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
     (assume-field ui-entity-props field)
     (not= current original)))
  ([ui-entity-props]
   (let [{:keys [::subforms ::fields]} (::config ui-entity-props)
         dirty-field?     (fn [k] (dirty? ui-entity-props k))
         subform-entities (immediate-subforms ui-entity-props (-> subforms (keys) (set)))]
     (boolean
       (or
         (some dirty-field? fields)
         (some dirty? subform-entities))))))

(s/def dirty? ::field-tester)

(defn no-spec-or-valid?
  "Returns false if and only if the given key has a spec, and the spec is not valid for the value found in the given
  map of entity props (e.g. `(s/valid? key (get entity-props key))`).

  Returns true otherwise."
  [entity-props key]
  (or (not (s/get-spec key))
    (s/valid? key (get entity-props key))))

(s/fdef no-spec-or-valid?
  :args (s/cat :entity map? :k keyword?)
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

(defn make-validator
  "Create a form/field validation function using a supplied field checker. The field checker will be given
  then entire form (denormalized) and a single field key that is to be checked. It must return
  a boolean indicating if that given field is valid.

  During a recursive check for a form, the validation function will be in the correct context (e.g. the form supplied will contain
  the field. There is no need to search for it in subforms).

  make-validator returns a dual arity function:

  (fn [form] ...) - Calling this version will return :unchecked, :valid, or :invalid for the entire form.
  (fn [form field] ...) - Calling this version will return :unchecked, :valid, or :invalid for the single field.

  Typical usage would be to show messages around the form fields:

  ```
  (def field-valid? [form field] true) ; just say everything is valid

  (def my-validator (make-validator field-valid?))

  (defn valid? [form field]
     (= :valid (my-validator form field)))

  (defn checked? [form field]
     (not= :unchecked (my-validator form field)))
  ```
  "
  [field-valid?]
  (fn custom-get-validity*
    ([ui-entity-props field]
     (let [{{complete? ::complete?} ::config} ui-entity-props
           complete? (or complete? #{})]
       (assume-field ui-entity-props field)
       (cond
         (not (complete? field)) :unchecked
         (not (field-valid? ui-entity-props field)) :invalid
         :else :valid)))
    ([ui-entity-props]
     (let [{{:keys [::fields ::subforms]} ::config} ui-entity-props
           immediate-subforms (immediate-subforms ui-entity-props (-> subforms keys set))
           field-validity     (fn [current-validity k] (merge-validity current-validity (custom-get-validity* ui-entity-props k)))
           subform-validities (map custom-get-validity* immediate-subforms)
           subform-validity   (reduce merge-validity :valid subform-validities)
           this-validity      (reduce field-validity :valid fields)]
       (merge-validity this-validity subform-validity)))))

(let [spec-validator (make-validator no-spec-or-valid?)]
  (defn get-spec-validity
    "Get the validity (:valid :invalid or :unchecked) for the given form/field using Clojure specs of the field keys.

    ui-entity-props : A denormalized (UI) entity, which can have subforms.
    field : Optional. Returns the validity of just the single field on the top-level form.

    Returns `:invalid` if all of the fields have been interacted with, and *any* are invalid.
    Returns `:unchecked` if any field is not yet been interacted with.

    Fields are marked as having been interacted with by programmatic action on your part via
    the validate* mutation helper can be used in a mutation to mark fields ready for validation.

    If given a field then it checks just that field."
    ([form] (spec-validator form))
    ([form field] (spec-validator form field))))

(s/def get-spec-validity ::field-tester)

(defn valid-spec?
  "Returns true if the given field (or the entire denormalized (UI) form recursively) is :valid
  according to clojure specs. Returns false if unchecked or invalid. Use `checked-spec?` or `get-spec-validity`
  for better detail."
  ([ui-form] (= :valid (get-spec-validity ui-form)))
  ([ui-form field] (= :valid (get-spec-validity ui-form field))))

(defn invalid-spec?
  "Returns true if the given field (or any field if only a form is given) in the denormalized (UI) form is :invalid
  (recursively) according to clojure specs. Returns false if the field is marked unchecked. Use `checked-spec?` or
  `get-spec-validity` for better detail."
  ([ui-form] (= :invalid (get-spec-validity ui-form)))
  ([ui-form field] (= :invalid (get-spec-validity ui-form field))))

(let [do-not-care        (constantly true)
      carefree-validator (make-validator do-not-care)]
  (defn checked?
    "Returns true if the field (or entire denormalized (UI) form) is ready to be checked for validation.
    Until this returns true validators will simply return :unchecked for a form/field."
    ([ui-form] (not= :unchecked (carefree-validator ui-form)))
    ([ui-form field]
     (assume-field ui-form field)
     (not= :unchecked (carefree-validator ui-form field)))))

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
    and must return an updated version of them.
  starting-entity-ident : An ident in the state map of an entity that has been initialized as a form.

  Returns the updated state map."
  [state-map xform starting-entity-ident]
  (let [entity         (get-in state-map starting-entity-ident)
        config-ident   (get entity ::config)
        config         (get-in state-map config-ident)
        {:keys [::subforms]} config
        [updated-entity updated-config] (xform entity config)
        subform-idents (immediate-subform-idents (get-in state-map starting-entity-ident) (-> subforms keys set))]
    (as-> state-map sm
      (assoc-in sm starting-entity-ident updated-entity)
      (assoc-in sm config-ident updated-config)
      (reduce (fn [s id]
                (update-forms s xform id)) sm subform-idents))))

(s/fdef update-forms
  :args (s/cat :state map? :xform ::form-operation :ident util/ident?)
  :ret map?)

(defn dirty-fields
  "Obtains all of the dirty fields for the given (denormalized) ui-entity, recursively. This works against UI props
  because submission mutations should close over the data as parameters to a mutation. In other words, your form
  submission to a server should be triggered from UI with the output of this function as parameters:

  ```
  (dom/input { :onClick #(prim/transact! this `[(some-submit-function {:diff ~(f/dirty-fields props true)})]) })
  ```

  ui-entity - The entity (denormalized) from the UI.
  as-delta? - If false, each field's reported (new) value will just be the new value. When true, each value will be a map with :before and :after keys
  with the old and new values (useful for optimistic transaction semantics).

  Returns a map keyed by form ID (for each form/subform) whose values are maps of key/value pairs of
  changes. Fields from entities that have a temporary IDs will always be included.

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
        subform-keys       (-> subforms keys set)
        subform-ident      (fn [k entity] (some-> (get subforms k) meta :component (prim/get-ident entity)))
        new-entity?        (prim/tempid? (second id))
        delta              (into {} (keep (fn [k]
                                            (let [before (get pristine-state k)
                                                  after  (get ui-entity k)]
                                              (if (or new-entity? (not= before after))
                                                (if as-delta?
                                                  [k {:before before :after after}]
                                                  [k after])
                                                nil))) fields))
        delta-with-refs    (into delta
                             (keep
                               (fn [k]
                                 (let [items         (get ui-entity k)
                                       old-value     (get-in ui-entity [::config ::pristine-state k])
                                       current-value (cond
                                                       (map? items) (subform-ident k items)
                                                       (vector? items) (mapv #(subform-ident k %) items)
                                                       :else items)
                                       has-tempids?  (if (every? util/ident? current-value)
                                                       (some #(prim/tempid? (second %)) current-value)
                                                       (prim/tempid? (second current-value)))]
                                   (if (or has-tempids? (not= old-value current-value))
                                     (if as-delta?
                                       [k {:before old-value :after current-value}]
                                       [k current-value])
                                     nil)))
                               subform-keys))
        local-dirty-fields (if (empty? delta-with-refs) {} {id delta-with-refs})
        complete-delta     (reduce
                             (fn [dirty-fields-so-far subform-join-field]
                               (let [subform (get ui-entity subform-join-field)]
                                 (cond
                                   ; to many
                                   (vector? subform) (reduce (fn [d f] (merge d (dirty-fields f as-delta?))) dirty-fields-so-far subform)
                                   ; to one
                                   (map? subform) (let [dirty-subform-fields (dirty-fields subform as-delta?)]
                                                    (merge dirty-fields-so-far dirty-subform-fields))
                                   ; missing subform
                                   :else dirty-fields-so-far)))
                             local-dirty-fields
                             subform-keys)]
    complete-delta))

(s/fdef dirty-fields
  :args (s/cat :entity ::denormalized-form :delta boolean?)
  :ret map?)

(defn clear-complete*
  "Mark the fields incomplete so that validation checks will no longer return values. This function works on a app state database
  map (not atom) and is meant to be composed into mutations. See the `mark-incomplete!` mutation if you do not need to combine
  this with other operations.

  Follows the subforms recursively through state, unless a specific field is given."
  ([state-map entity-ident field]
   (let [form-config-path (conj entity-ident ::config)
         form-config-path (if (util/ident? (get-in state-map form-config-path))
                            (get-in state-map form-config-path)
                            (do
                              (log/error (str "FORM NOT NORMALIZED: " entity-ident))
                              form-config-path))
         complete-path    (conj form-config-path ::complete?)]
     (update-in state-map complete-path (fnil disj #{}) field)))
  ([state-map entity-ident]
   (update-forms state-map
     (fn mark*-step [e form-config]
       [e (assoc form-config ::complete? #{})]) entity-ident)))

(defn mark-complete*
  "Mark the fields complete so that validation checks will return values. This function works on a app state database
  map (not atom) and is meant to be composed into mutations. See the `mark-complete!` mutation if you do not need to combine
  this with other operations.

  Follows the subforms recursively through state, unless a specific field is given."
  ([state-map entity-ident field]
   (let [form-config-path (conj entity-ident ::config)
         form-config-path (if (util/ident? (get-in state-map form-config-path))
                            (get-in state-map form-config-path)
                            (do
                              (log/error (str "FORM NOT NORMALIZED: " entity-ident))
                              form-config-path))
         complete-path    (conj form-config-path ::complete?)]
     (update-in state-map complete-path (fnil conj #{}) field)))
  ([state-map entity-ident]
   (update-forms state-map
     (fn mark*-step [e form-config]
       [e (assoc form-config ::complete? (::fields form-config))]) entity-ident)))

(s/fdef mark-complete*
  :args (s/cat :state map? :entity-ident util/ident? :field (s/? keyword?))
  :ret map?)

(defn delete-form-state*
  "Removes copies of entities used by form-state logic."
  [state-map entity-ident-or-idents]
  (let [entity-idents (if (util/ident? entity-ident-or-idents)
                        [entity-ident-or-idents]
                        entity-ident-or-idents)

        ks            (mapv (fn [[t r]]
                              {:table t :row r})
                        entity-idents)]
    (update state-map ::forms-by-ident
      (fn [s]
        (apply dissoc s ks)))))

(s/fdef delete-form-state*
  :args (s/cat :state-map map?
          :entity-ident-or-idents (s/or :entity-ident util/ident?
                                    :entity-idents (s/coll-of util/ident?)))
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
  (update-forms state-map (fn commit-form-step [e {:keys [::fields ::subforms] :as config}]
                            (let [subform-keys       (-> subforms keys set)
                                  new-pristine-state (select-keys e (set/union subform-keys fields))]
                              [e (assoc config ::pristine-state new-pristine-state)])) entity-ident))

(s/fdef entity->pristine*
  :args (s/cat :state map? :entity-ident util/ident?)
  :ret map?)

(defmutation reset-form!
  "Mutation: Reset the form (recursively) to its (last recorded) pristine state. If form ident is not supplied it uses the ident
   of the calling component. See `pristine->entity*` for a function you can compose into your own mutations."
  [{:keys [form-ident]}]
  (action [{:keys [ref state]}]
    (swap! state pristine->entity* (or form-ident ref))))

(defmutation mark-complete!
  "Mutation: Mark a given form (recursively) or field complete.

  entity-ident - The ident of the entity to mark complete. This is optional, but if not supplied it will derive it from
                 the ident of the invoking component.
  field - (optional) limit the marking to a single field.

  See `mark-complete*` for a function you can compose into your own mutations."
  [{:keys [entity-ident field]}]
  (action [{:keys [ref state]}]
    (let [entity-ident (or entity-ident ref)]
      (if field
        (swap! state mark-complete* entity-ident field)
        (swap! state mark-complete* entity-ident)))))

(defmutation clear-complete!
  "Mutation: Mark a given form (recursively) or field incomplete.

  entity-ident - The ident of the entity to mark. This is optional, but if not supplied it will derive it from
                 the ident of the invoking component.
  field - (optional) limit the marking to a single field.

  See `clear-complete*` for a function you can compose into your own mutations."
  [{:keys [entity-ident field]}]
  (action [{:keys [ref state]}]
    (let [entity-ident (or entity-ident ref)]
      (if field
        (swap! state clear-complete* entity-ident field)
        (swap! state clear-complete* entity-ident)))))
