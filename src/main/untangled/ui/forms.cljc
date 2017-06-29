(ns untangled.ui.forms
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [om.dom :as dom]
    [om.next :as om]
    [om.util :as util]
    [clojure.tools.reader :as reader]
    [untangled.client.core :as uc]
    [untangled.client.data-fetch :as df]
    [untangled.client.logging :as log]
    [untangled.client.mutations :as m :refer [defmutation]]))

#?(:clj (def clj->js identity))

(defn fail!
  ([msg] (fail! msg nil))
  ([obj msg ex-data]
   (let [message (str obj " failed because of: " msg)
         ex-data (assoc ex-data :failing/obj obj)]
     (fail! message ex-data)))
  ([msg ex-data] (log/error msg ex-data)))

(defn assert-or-fail [obj pred msg & [ex-data]]
  (when-not (pred obj)
    (fail! obj msg ex-data)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FORM PROTOCOL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IForm
  (form-spec [this]
    "Returns the form specification,
     ie: what the form is made of,
     eg: fields, subforms, form change listeners."))

(defn- ui-ns [kw-name]
  ;; workaround for no *ns* in cljs
  (keyword (namespace ::_) kw-name))

(def form-key
  "Query this in *all* of your form components, else form support will fail!
   (often in subtle/obscure ways, WIP on how to better catch & report this)"
  (ui-ns "form"))

(def form-root-key
  "Query this in your top level form component.
   Is okay to have multiple 'root' components on screen at once,
   as om and react will optimize the rendering step."
  (ui-ns "form-root"))

(defn- get-form-spec
  "Returns a map with:
   * :elements - contains user level fields
   * :form - contains internal form details"
  [this]
  (let [assert-no-duplicate
        (fn [field]
          (fn [old-value]
            (assert (nil? old-value)
              (str "Cannot implement field <" field "> more than once!"))
            field))]
    (-> (reduce (fn [acc field]
                  (let [spec-key (if (= form-key (:input/type field))
                                   :form :elements)]
                    (update-in acc [spec-key (:input/name field)]
                      (assert-no-duplicate
                        (cond-> field (= :form spec-key)
                          (dissoc :input/name :input/type))))))
          {} (form-spec this))
      (update :elements vals))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ELEMENT/FIELD/INPUT DEFINITIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj (def implements? satisfies?))

(defn subform-element
  "Declare that the current form links to subforms through the given entity property in a :one or :many capacity. this
  must be included in your list of form elements if you want form interactions to trigger across a form group.

  Additional named parameters:

  `isComponent` - A boolean to indicate that references to instances of this subform are the only uses of the target,
  such that removing the reference indicates that the target is no longer used and can be removed from the database."
  ([field form-class cardinality & {:keys [isComponent]}]
   (assert (contains? #{:one :many} cardinality) "subform-element requires a cardinality of :one or :many")
   (assert ((every-pred #(implements? om/Ident %) #(implements? IForm %) #(implements? om/IQuery %)) form-class)
     (str "Subform element " field " MUST implement IForm, IQuery, and Ident."))
   (with-meta {:input/name          field
               :input/is-form?      true
               :input/is-component? isComponent
               :input/cardinality   cardinality
               :input/type          ::subform}
     {:component form-class})))

(defn form-switcher-input
  "Create a field that understands it points to a to-many list of subforms, only one of which
  can be interacted with at a time, but all of which will be affected by top-level form operations like commit and
  validate. Functions like `valid?` check the validity of the list of subforms when applied to such a
  field. Rendering such a field requires that you pass the desired value of `select-key` to select the subform."
  [field FormClass select-key]
  (assoc (subform-element field FormClass :many)
    :input/type ::switcher
    :input/select-key select-key))

(defn id-field
  "Declare a hidden identity field.
   Required to read/write to/from other db tables,
   and to make sure tempids and such follow along properly."
  [name]
  {:input/name name
   :input/type ::identity})

(defn text-input
  "Declare a text input on a form. The allowed options are named parameters:

  :className nm    Additional CSS classnames to include on the input (as a string)
  :validator sym   A symbol to target the dispatch of validation
  :validator-args  Arguments that will be passed to the validator
  :placeholder     The input placeholder. Supports a lambda or string
  :default-value   A value to use in the field if the app-state value is nil
  :validate-on-blur? Should the field be validated on a blur event (default = true)
  "
  [name & {:keys [validator validator-args className default-value placeholder validate-on-blur?]
           :or   {placeholder "" default-value "" className "" validate-on-blur? true}}]
  (cond-> {:input/name              name
           :input/default-value     default-value
           :input/placeholder       placeholder
           :input/css-class         className
           :input/validate-on-blur? validate-on-blur?
           :input/type              ::text}
    validator (assoc :input/validator validator)
    validator-args (assoc :input/validator-args validator-args)))

(defn integer-input
  "Declare an integer input on a form. See text-input for additional options."
  [name & options]
  (-> (apply text-input name options)
    (assoc :input/type ::integer)
    (update :input/default-value (fn [v] (if (integer? v) v 0)))))

(defn textarea-input
  "Declare a text area on a form. See text-input for additional options.

  When rendering a text input, the params passed to the field render will be merged
  with the textarea HTML props."
  [name & options]
  (-> (apply text-input name options)
    (assoc :input/type ::textarea)))

(defn checkbox-input
  "Declare a checkbox on a form"
  [name & {:keys [className default-value]
           :or   {default-value false}}]
  {:input/type          ::checkbox
   :input/default-value (boolean default-value)
   :input/css-class     className
   :input/name          name})

(defn dropdown-input
  "Declare a dropdown menu selector.

  name is the keyword property name of the field
  options is a vector of f/option items to display

  Additional (optional) named parameters are `default-value` and `className`. The
  default-value should be the `:key` of one of the options (defaults to :f/none)."
  [name options & {:keys [default-value className]
                   :or   {default-value ::none className ""}}]
  {:pre [(or (= default-value ::none)
           (some #(= default-value (:option/key %)) options))
         (and (seq options)
           (every? :option/key options))]}
  {:input/type          ::dropdown
   :input/default-value default-value
   :input/options       options
   :input/css-class     className
   :input/name          name})

(defn option
  "Create an option for use in a dropdown. The key is used as your app database value, and label as the label."
  [key label]
  {:option/key   key
   :option/label label})

(defn radio-input
  "Declare an input that will render as some number of radio buttons.

  `name` : The field name
  `options` : A set of legal values. Can be anything that `pr-str` and `read-string` can convert to/from strings.

  Radio button rendering is done via the params of `form-field`. If you declare:

  ```
  (radio-input :rating #{1 2 3 4 5})
  ```

  then in your rendering you will render the field five times:

  ```
  (dom/div nil
    (form-field form :rating :choice 1) 1
    (form-field form :rating :choice 2) 2
    (form-field form :rating :choice 3) 3
    (form-field form :rating :choice 4) 4
    (form-field form :rating :choice 5) 5)
  ```
  "
  [name options & {:keys [default-value className]
                   :or   {default-value ::none className ""}}]
  {:pre [(set? options)]}
  {:input/name          name
   :input/type          ::radio
   :input/default-value default-value
   :input/css-class     className
   :input/options       options})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GENERAL FORM STATE ACCESS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn is-form? [?form] (get ?form form-key))

(defn form-component
  "Get the UI component that declared the given form."
  [form] (-> form form-key meta :component))

(defn form-ident
  "Get the ident of this form's entity"
  [form] (get-in form [form-key :ident]))

(defn field-config
  "Get the configuration for the given field in the form."
  [form name] (get-in form [form-key :elements/by-name name]))

(defn field-type
  "Get the configuration for the given field in the form."
  [form name] (:input/type (field-config form name)))

(defn placeholder
  "Returns the current value of the placeholder, which may be a lambda or a string."
  [form field]
  (let [{:keys [:input/placeholder] :or {placeholder ""}} (field-config form field)]
    (if (string? placeholder)
      placeholder
      (placeholder))))

(defn is-subform?
  "Returns whether the element, or the field-key in the form, is a subform."
  ([element]
   (:input/is-form? element))
  ([form field-key]
   (is-subform? (field-config form field-key))))

(defn- is-ui-query-fragment?
  "TODO: Maybe make it public & access it from untangled-client ?"
  [kw] (when (keyword? kw) (some->> kw namespace (re-find #"^ui(?:\.|$)"))))

(defn ui-field?
  "For checking if a field is only a ui concern.
   eg: should therefore not be sent to the server."
  ([form field]
   (-> (field-config form field)
     :input/name is-ui-query-fragment?)))

(defn current-value
  "Gets the current value of a field in a form."
  [form field] (get form field))

(declare set-validation)

(defn update-current-value
  "Updates the current value of a field in a form (with a fn) and marks it as :unchecked."
  [form field f & args]
  (as-> form the-form
    (apply update the-form field f args)
    (set-validation the-form field :unchecked)))

(defn set-current-value
  "Sets the current value of a field in a form, and marks it as :unchecked."
  [form field value]
  (-> form
    (assoc field value)
    (set-validation field :unchecked)))

(defn css-class
  "Gets the css class for the form field"
  [form field]
  (:input/css-class (field-config form field)))

(defn element-names
  "Get all of the field names that are defined on the form."
  [form] (keys (get-in form [form-key :elements/by-name])))

(defn get-original-data
  "Get the unmodified copy of the form state from when it was first initialized."
  ([form] (get-in form [form-key :origin]))
  ([form field] (get (get-original-data form) field)))

(defn- ?normalize [{:keys [input/cardinality]} x]
  (if-not (or (is-form? x) (and (coll? x) (seq x) (every? is-form? x)))
    x
    (case cardinality, :one (form-ident x), :many (mapv form-ident x), x)))

(defn dirty-field? [form field]
  (let [cfg  (field-config form field)
        curr (?normalize cfg (current-value form field))]
    (or (om/tempid? curr)                                   ;;TODO FIXME ??? how does this work if its an ident? or not normalized?
      (not= curr (?normalize cfg (get-original-data form field))))))

(declare validator)

(defn validatable-fields
  "Get all of the names of the validatable fields that are defined on the (initialized) form."
  [form] (filter #(not (is-subform? form %))
           (element-names form)))

(defn commit-state
  "Commits the state of the form to the entity, making it the new original data."
  [form] (assoc-in form [form-key :origin]
           (select-keys form (keys (get-original-data form)))))

(defn reset-entity
  "Resets the form back to the original state, ie when it was first created/initialized"
  [form] (merge form (get-original-data form)))

(defn- subforms*
  "Returns a map whose keys are the query key-path from the component's query that point to subforms, and whose values are the
   defui component of that form (e.g. `{ [:k :k2] Subform }`). This will give you ALL of the current subforms declared in the static query and IForm
   fields. NOTE: union queries in grouped forms are not supported, since there would be no way to auto-gather non-displayed
   forms in the 'current' state.

   Use get-forms to obtain the current state of active forms. It is a gathering mechanism only."
  ([form-class] (subforms* form-class []))
  ([form-class current-path]
   (let [ast            (om/query->ast (om/get-query form-class))
         elements       (:elements (get-form-spec form-class))
         subform-fields (set (keep #(when (is-subform? %) (:input/name %)) elements))
         get-class      (fn [ast-node] (let [subquery (:query ast-node)]
                                         (if (or (int? subquery) (= '... subquery))
                                           (fail! "Forms do not support recursive-query-based subforms!"
                                             {:subquery subquery :ast-node ast-node})
                                           (:component ast-node))))
         is-form-node?  (fn [ast-node]
                          (let [form-class   (get-class ast-node)
                                prop         (:key ast-node)
                                join?        (= :join (:type ast-node))
                                union?       (and join? (map? (:query ast-node)))
                                wants-to-be? (contains? subform-fields prop)]
                            (when (and union? wants-to-be?)
                              (fail! "Subforms cannot be on union queries. You will have to manually group your subforms if you use unions."
                                {:ast-node ast-node}))
                            (when (and wants-to-be?
                                    (not (and (implements? om/Ident form-class)
                                           (implements? IForm form-class)
                                           (implements? om/IQuery form-class))))
                              (fail! (str "Declared subform for property " prop
                                       " does not implement IForm, IQuery, and Ident.")
                                {:ast-node ast-node}))
                            (and form-class wants-to-be? join? (not union?) (implements? om/IQuery form-class)
                              (implements? om/Ident form-class) (implements? IForm form-class))))
         sub-forms      (->> ast :children
                          (keep (fn [ast-node]
                                  (when (is-form-node? ast-node)
                                    (let [path       (conj current-path (:key ast-node))
                                          form-class (get-class ast-node)]
                                      [path form-class])))))]
     (reduce (fn [collected-so-far [path component]]
               (-> collected-so-far
                 (conj [path component])
                 (into (subforms* component path))))
       [] sub-forms))))

(defn- to-idents
  "Follows a key-path through the graph database started from the current object. Follows to-one and to-many joins.
   Results in a sequence of all of the idents of the items indicated by the given key-path from the given object."
  [app-state current-object key-path]
  (loop [path key-path obj current-object]
    (let [k           (first path)
          remainder   (rest path)
          v           (get obj k)
          to-many?    (and (vector? v) (every? util/ident? v))
          ident?      (and (not to-many?) (util/ident? v))
          many-idents (if-not to-many? []
                                       (apply concat
                                         (map-indexed (fn [idx _] (to-idents app-state v (conj remainder idx)))
                                           v)))
          result      (vec (keep identity (conj many-idents (when ident? v))))]
      (if (and ident? (seq remainder))
        (recur remainder (get-in app-state v))
        result))))

(defn get-forms
  "Reads the app state database starting at form-ident, and returns a sequence of :

  {:ident ident :class form-class :form form-value}

  for the top form and all of its **declared** subforms. Useful for running transforms and collection across a nested form.

  If there are any to-many relations in the database, they will be expanded to individual entries of the returned sequence.
  "
  [app-state root-form-class form-ident]
  (let [form (get-in app-state form-ident)]
    (lazy-cat [{:ident form-ident :class root-form-class :form form}]
      (sequence (comp (mapcat (fn [[query-key-path class]]
                                (for [ident (to-idents app-state form query-key-path)]
                                  (let [value (get-in app-state ident)]
                                    {:ident ident :class class :form value}))))
                  (filter :ident))
        (subforms* root-form-class)))))

(defn update-forms
  "Similar to update-in, but walks your form declaration to affect all (initialized and preset) nested forms.
  Useful for applying validation or some mutation to all forms. Returns the new app-state. You supply a
  `form-update-fn` of type (fn [{:keys [ident class form]}] => form), where:
   * `:class` is the component that has the form,
   * `:ident` is of the form in app state,
   * `:form`  is the value of the form in app state."
  [app-state form form-update-fn]
  (transduce (map #(assoc % :form (form-update-fn %)))
    (completing
      (fn [s {:keys [ident form]}]
        (assoc-in s ident form)))
    app-state
    (get-forms app-state
      (form-component form)
      (form-ident form))))

(defn reduce-forms
  "Similar to reduce, but walks the forms.
   Useful for gathering information from nested forms (eg: are all of them valid?).
   At each form it calls (form-fn accumulator {:keys [ident form class]}).
   The first visit will use `starting-value` as the initial accumulator,
   and the return value of form-fn will become the new accumulator.

   Returns the final accumulator value."
  ([app-state form starting-value form-fn]
   (reduce form-fn starting-value
     (get-forms app-state
       (form-component form)
       (form-ident form)))))

(defn- all-forms
  "Returns a sequence of all the forms under a given `form`"
  [form]
  (concat [form]
    (sequence
      (comp
        (filter (partial is-subform? form))
        (mapcat #(let [curr (current-value form %)]
                   (cond-> curr
                     (and curr (= :one (:input/cardinality (field-config form %))))
                     #_=> vector)))
        (mapcat all-forms))
      (element-names form))))

(defn form-reduce
  "Reduces over a `form` acquired via `om/props` on a component,
   at each step calls `F` with each form.
   `init` is the initial value for the reduce.

   Optionally takes a transducing function
   as an extra second argument & therefore calls transduce."
  ([form init F]
   (reduce F init (all-forms form)))
  ([form xf init F]
   (transduce xf (completing F) init (all-forms form))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FORM CONSTRUCTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn default-state
  "INTERNAL METHOD. Get the default state configuration for the given field definitions.
   MUST ONLY BE PASSED PURE FIELDS. Not subforms."
  [fields]
  (let [parse-field
        (fn [f]
          (merge f
            (if (= ::identity (:input/type f))
              {:value (om/tempid) :valid :valid}
              {:value (:input/default-value f) :valid :unchecked})))]
    (transduce (map parse-field)
      (completing
        (fn [acc {:keys [value valid input/name]}]
          (-> acc
            (assoc-in [:state name] value)
            (assoc-in [:validation name] valid))))
      {} fields)))

(defn initialized-state
  "INTERNAL. Get the initialized state of the form based on default state of the fields and the current entity state"
  [empty-form-state field-keys-to-initialize entity]
  (assert-or-fail field-keys-to-initialize (every-pred seq (partial every? keyword?))
    "Empty or invalid field keys")
  (reduce (fn [s k] (if-let [v (get entity k)]
                      (assoc s k v)
                      s))
    empty-form-state field-keys-to-initialize))

; TODO: Not clj compatible. Will cause server-side rendering to fail
(defn build-form
  "Build an empty form based on the given entity state. Returns an entity that is compatible with the original, but
   that has had form support added. If any fields are declared on
   the form that do not exist in the entity, then the form will fill those with
   the default field values for the declared input fields.
   This function does **not** recursively build out nested forms, even when declared. See `init-form`."
  [form-class entity-state]
  (let [{:keys [elements form]} (get-form-spec form-class)
        element-keys             (map :input/name elements)
        elements-by-name         (zipmap element-keys elements)
        {:keys [state validation]} (default-state elements)
        entity-state-of-interest (select-keys entity-state element-keys)
        init-state               (initialized-state state element-keys entity-state-of-interest)
        final-state              (merge entity-state init-state)]
    (-> final-state
      (assoc form-key
             (-> form
               (merge
                 {:elements/by-name elements-by-name
                  :ident            (om/ident form-class final-state)
                  :origin           (into {}
                                      (map (fn [[k v]]
                                             [k (if (and (is-subform? (elements-by-name k))
                                                      (not (or (util/ident? v)
                                                             (every? util/ident? v))))
                                                  (case (:input/cardinality (elements-by-name k))
                                                    :many (mapv form-ident v)
                                                    (form-ident v))
                                                  v)]))
                                      init-state)
                  :subforms         (or (filterv :input/is-form? elements) [])
                  :validation       validation})
               (vary-meta merge {:component form-class}))))))

(declare init-form*)

(defn initialized? "Returns true if the given form is already initialized with form setup data"
  [form] (map? (form-key form)))

(defn init-one
  [state base-form subform-spec visited]
  (let [k             (:input/name subform-spec)
        subform-class (some-> subform-spec meta :component)
        subform-ident (get base-form k)
        visited       (update-in visited subform-ident inc)]
    (assert (or (nil? subform-ident)
              (util/ident? subform-ident))
      "Initialize-one form did not find a to-one relation in the database")
    (if (or (nil? (second subform-ident))
          (> (get-in visited subform-ident) 1))
      state
      (init-form* state subform-class subform-ident visited))))

(defn init-many
  [state base-form subform-spec visited]
  (let [k              (:input/name subform-spec)
        subform-idents (get base-form k)
        subform-class  (some-> subform-spec meta :component)
        visited        (reduce (fn [v ident] (update-in v ident inc)) visited subform-idents)]
    (assert (or (nil? subform-idents)
              (every? util/ident? subform-idents))
      "Initialize-many form did not find a to-many relation in the database")
    (reduce (fn [st f-ident]
              (if (or
                    (nil? (second f-ident))
                    (> (get-in visited f-ident) 1))
                st
                (init-form* st subform-class f-ident visited)))
      state subform-idents)))

(defn- init-form*
  [app-state form-class form-ident forms-visited]
  (if-let [form (get-in app-state form-ident)]
    (let [base-form      (cond->> form (not (initialized? form))
                           (build-form form-class))
          base-app-state (assoc-in app-state form-ident base-form)]
      (transduce (filter is-subform?)
        (fn [state subform-spec]
          (if (= :many (:input/cardinality subform-spec))
            (init-many state base-form subform-spec forms-visited)
            (init-one state base-form subform-spec forms-visited)))
        base-app-state
        (:elements (get-form-spec form-class))))
    app-state))

(defn init-form
  "Recursively initialize a form from an app state database. Will follow subforms (even when top-levels are initialized).
  Returns the new app state (can be used to `swap!` on app state atom). Will **not** add forms where there is not
  already an entity in the database. If there are subforms, this function will only initialize those that are present
  AND uninitialized. Under no circumstances will this function re-initialize a form or subform.

  `app-state` The map of the current app state.
  `form-class` The defui class that defines the top-level form.
  `form-ident` The ident of the entity's data in app state."
  [app-state form-class form-ident] (init-form* app-state form-class form-ident {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VALIDATION SUPPORT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:cljs (defmutation noop "Do nothing." [params]))

(defn on-form-change
  "Declare an Untangled mutation (as a properly namespaced symbol) that will be triggered on
  each form change. Only one such mutation can be defined for a form.

  Add this to your IForm declarations:

  ```
  (defui ^:once PhoneForm
    static uc/InitialAppState
    (initial-state [this params] (f/build-form this (or params {})))
    static f/IForm
    (form-spec [this] [(f/id-field :db/id)
                       (f/on-form-change 'some-ns/global-validate-phone-form)
                       ...])
  ...)
  ```

  When invoked, the target mutation params will include:

  `:form-id` The ident of the form. You may use the app state in `env` to do anything you want to do (validate, etc.)
  `:field` The name of the field that changed
  `:kind` The kind of change:
     `:blur` The user finished with the given field and moved away from it.
     `:edit` The user changed the value. Text fields edits will trigger one of these per keystroke."
  [mut-sym]
  {:input/type                     form-key
   :input/name                     :on-form-change
   :on-form-change/mutation-symbol mut-sym})

(defn- get-on-form-change-mutation
  "Get the Om mutation symbol to invoke when the form changes. This is typically used in the implementation
  of form field renderers as part of the transaction to run on change and blur events.

  Returns a valid symbolic data structure that can be used inside of transact:

  ```
  (om/transact! `[~@(get-on-form-change-mutation form :f :blur)])
  ```

  will convert to something like:

  ```
  (om/transact! `[(your-change-handler-symbol {:form-id [:form 1] :field :f :kind :blur})])
  ```

  This function returns a list of mutations expressions to run (which will contain zero or one).
  Use list unquote to patch it into place."
  [form field-name kind]
  {:pre [(contains? #{:blur :edit} kind)]}
  (when-let [mutation-symbol (get-in form [form-key :on-form-change :on-form-change/mutation-symbol])]
    [(list mutation-symbol {:form-id (form-ident form) :kind kind :field field-name})]))

(defn current-validity
  "Returns the current validity from a form's props for the given field. One of :valid, :invalid, or :unchecked"
  [form field]
  (get-in form [form-key :validation field]))

(defn- reduced-if [p x]
  (cond-> x (p x) reduced))

(defn invalid?
  "Returns true iff the form or field has been validated, and the validation failed.
   Using this on a form ignores unchecked fields,
   so you should run validate-entire-form! before trusting this value on a form.

   SEE ALSO `would-be-valid?` if you'd like to pretend that full-form validation has been run
   in a query-only sort of way.

   `root-form` is the props of a top-level form. Evaluates form recursively.
   `form` is the props of a specific form
   `field` is a field to check on a specific form"
  ([root-form] (form-reduce root-form false
                 (fn [inv? form]
                   (reduced-if true?
                     (reduce (fn [_ field]
                               (reduced-if true? (invalid? form field)))
                       inv? (validatable-fields form))))))
  ([form field] (= :invalid (current-validity form field))))

(defn valid?
  "Returns true iff the form or field has been validated, and the validation is ok.

   Please make sure you've read and understood the form state lifecycle with respect to validation.

   SEE ALSO `would-be-valid?` if you'd like to pretend that full-form validation has been run
   in a query-only sort of way.

   `root-form` is the props of a top-level form. Evaluates form recursively.
   `form` is the props of a specific form
   `field` is a field to check on a specific form"
  ([root-form] (form-reduce root-form true
                 (fn [vld? form]
                   (reduced-if false?
                     (reduce (fn [_ field]
                               (reduced-if false?
                                 (valid? form field)))
                       vld? (validatable-fields form))))))
  ([form field] (= :valid (current-validity form field))))

(defn validator
  "Returns the validator symbol from the form field.

  `form` The form props
  `field` The field name"
  [form field]
  (:input/validator (field-config form field)))

(defn validator-args
  "Returns the validator args from the form field

  `form` The form props
  `field` The field name"
  [form field]
  (assoc (get (field-config form field) :input/validator-args {})
    ::this-form form))

(defn- set-validation
  [form field value]
  (assoc-in form [form-key :validation field] value))

(defmulti form-field-valid? "Extensible form field validation. Triggered by symbols. Arguments (args) are declared on the fields themselves."
  (fn [symbol value args] symbol))

(defmethod form-field-valid? `in-range? [_ value {:keys [min max]}]
  (let [value (int value)]
    (<= min value max)))

;; Sample validator that requires a number be in the (inclusive) range.
(defn validate-field*
  "Given a form and a field, returns a new form with that field validated. Does NOT recurse into subforms."
  [form field]
  (set-validation form field
    (if-let [validator (validator form field)]
      (let [validator-args (validator-args form field)
            valid?         (form-field-valid? validator (current-value form field) validator-args)]
        (if valid? :valid :invalid))
      :valid)))

(defn validate-fields
  "Runs validation on the defined fields and returns a new form with them properly marked."
  [form & [{:keys [skip-unchanged?]}]]
  (transduce (filter (if skip-unchanged? (partial dirty-field? form) identity))
    validate-field*
    form (validatable-fields form)))

(defn would-be-valid?
  "Checks (recursively on this form and subforms) if the values on the given form would be
  considered valid if full validation were to be run on the form. Returns true/false."
  [form]
  (letfn [(non-recursive-valid? [form]
            (reduce (fn [still-valid? field]
                      (let [f            (validate-field* form field)
                            field-valid? (valid? f field)]
                        (reduced-if false? (and still-valid? field-valid?)))) true (validatable-fields form)))]
    (form-reduce form true (fn [result form] (and result (non-recursive-valid? form))))))

(defn dirty?
  "Checks if the top-level form, or any of the subforms, are dirty. NOTE: Forms remain dirty as long as they have tempids."
  [form]
  (letfn [(dirty-form? [form]
            (boolean
              (some (partial dirty-field? form)
                (validatable-fields form))))]
    (form-reduce form false (fn [_ form] (reduced-if true? (dirty-form? form))))))

(defn validate-forms
  "Run validation on an entire form (by ident) with subforms. Returns an updated app-state."
  [app-state form-id & [opts]]
  (let [form       (get-in app-state form-id)
        form-class (form-component form)]
    (if form-class
      (update-forms app-state form
        (comp #(validate-fields % opts) :form))
      (fail! "Unable to validate form. No component associated with form. Did you remember to use build-form?"))))

#?(:cljs (defmutation validate-field
           "Om Mutation: run validation on a specific field.

           `form-id` is the ident of the entity acting as a form.
           `field` is the declared name for the field to validate.
           "
           [{:keys [form-id field]}]
           (action [{:keys [state]}] (swap! state update-in form-id validate-field* field))))

#?(:cljs (defmutation validate-form
           "Om Mutation: run (recursive) validation on an entire form.

           `form-id` is the ident of the entity acting as a form."
           [{:as opts :keys [form-id]}]
           (action [{:keys [state]}] (swap! state validate-forms form-id (dissoc opts :form-id)))))

(defn validate-entire-form!
  "Trigger whole-form validation as a TRANSACTION. The form will not be validated upon return of this function,
   but the UI will update after validation is complete. If you want to test if a form is valid use validate-fields on
   the state of the form to obtain an updated validated form. If you want to trigger validation as *part* of your
   own transaction (so your mutation can see the validated form), you may use the underlying
   `(f/validate-form {:form-id fident})` mutation in your own call to `transact!`."
  [comp-or-reconciler form & {:as opts}]
  (om/transact! comp-or-reconciler
    `[(validate-form ~(merge opts {:form-id (form-ident form)}))
      ~form-root-key]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GENERAL FORM MUTATION METHODS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:cljs (defmutation toggle-field
           "Toggle a boolean form field. `form-id` is the ident of the object acting as a form. `field` is the keyword
           name of the field to toggle."
           [{:keys [form-id field]}]
           (action [{:keys [state]}] (swap! state update-in form-id update-current-value field not))))

#?(:cljs (defmutation set-field
           "Om Mutation: Set the `field` on the form with an ident of `form-id` to the specified `value`"
           [{:keys [form-id field value]}]
           (action [{:keys [state]}] (swap! state update-in form-id set-current-value field value))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FORM FIELD RENDERING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti form-field*
  "Multimethod for rendering field types. Dispatches on field :input/type."
  (fn [component form field-name & params]
    (:input/type (field-config form field-name))))

(defmethod form-field* :default [component form field-name & params]
  (fail! (str "Cannot dispatch to form-field renderer on form " form " for field " field-name)))

(defn form-field
  "Function for rendering form fields. Call this to render, but `defmethod` on `form-field*`."
  [component form field-name & params]
  (apply form-field* component form field-name params))

(defn- render-input-field [component htmlProps form field-name type
                           field-value->input-value
                           input-value->field-value]
  (let [id          (form-ident form)
        input-value (field-value->input-value (current-value form field-name))
        attrs       (clj->js (merge htmlProps
                               {:type        type
                                :name        field-name
                                :value       input-value
                                :placeholder (placeholder form field-name)
                                :onBlur      (fn [_]
                                               (om/transact! component
                                                 `[(validate-field
                                                     ~{:form-id id :field field-name})
                                                   ~@(get-on-form-change-mutation form field-name :blur)
                                                   ~form-root-key]))
                                :onChange    (fn [event]
                                               (let [value      (input-value->field-value (.. event -target -value))
                                                     field-info {:form-id id
                                                                 :field   field-name
                                                                 :value   value}]
                                                 (om/transact! component
                                                   `[(set-field ~field-info)
                                                     ~@(get-on-form-change-mutation form field-name :edit)
                                                     ~form-root-key])))}))]
    (dom/input attrs)))

(defmethod form-field* ::text [component form field-name & {:keys [id className] :as params}]
  (let [i->f identity
        cls  (or className (css-class form field-name) "form-control")
        f->i identity]
    (render-input-field component {:id id :className cls} form field-name "text" f->i i->f)))

(defmethod form-field* ::integer [component form field-name & {:keys [id className] :as params}]
  (let [cls  (or className (css-class form field-name) "form-control")
        i->f #(when (seq (re-matches #"^[0-9]*$" %)) (int %))
        f->i identity]
    (render-input-field component {:id id :className cls} form field-name "number" f->i i->f)))

#?(:cljs (defmutation select-option
           "Om mutation: Select a sepecific option from a selection list. form-id is the ident of the object acting as
           a form. field is the select field, and value is the value to select."
           [{:keys [form-id field value]}]
           (action [{:keys [state]}] (let [value (.substring value 1)]
                                       (swap! state assoc-in
                                         (conj form-id field)
                                         (keyword value))))))

(defmethod form-field* ::dropdown [component form field-name & {:keys [id className onChange] :as params}]
  (let [form-id   (form-ident form)
        selection (current-value form field-name)
        cls       (or className (css-class form field-name) "form-control")
        field     (field-config form field-name)
        optional? (= ::none (:input/default-value field))
        options   (:input/options field)]
    (dom/select #js
        {:name      field-name
         :id        id
         :className cls
         :value     selection
         :onChange  (fn [event]
                      (let [value      (.. event -target -value)
                            field-info {:form-id form-id
                                        :field   field-name
                                        :value   value}]
                        (om/transact! component
                          `[(select-option ~field-info)
                            ~@(get-on-form-change-mutation form field-name :edit)
                            ~form-root-key])
                        (if (fn? onChange)
                          (onChange event)
                          (log/warn ":onChange is not a function"))))}
      (when optional?
        (dom/option #js {:value ::none} ""))
      (map (fn [{:keys [option/key option/label]}]
             (dom/option #js {:key key :value key} label))
        options))))

(defmethod form-field* ::checkbox [component form field-name & {:keys [id className] :as params}]
  (let [form-id    (form-ident form)
        cls        (or className (css-class form field-name) "")
        bool-value (current-value form field-name)]
    (dom/input #js
        {:type      "checkbox"
         :id        id
         :name      field-name
         :className cls
         :checked   bool-value
         :onChange  (fn [event]
                      (let [value      (.. event -target -value)
                            field-info {:form-id form-id
                                        :field   field-name
                                        :value   value}]
                        (om/transact! component
                          `[(toggle-field ~field-info)
                            ~@(get-on-form-change-mutation form field-name :edit)
                            ~form-root-key])))})))

(defn radio-button-id
  "Returns the generated ID of a form field component. Needed to label radio buttons"
  [form field choice]
  (str (second (form-ident form)) "-" field "-" choice))

(defmethod form-field* ::radio [component form field-name & {:keys [className choice label] :or {label "\u00a0"}}]
  (let [id          (form-ident form)
        cls         (or className "c-radio c-radio--expanded")
        field-id    (radio-button-id form field-name choice)
        current-val (current-value form field-name)]
    (dom/span nil
      (dom/input #js
          {:type      "radio"
           :id        field-id
           :name      field-name
           :className cls
           :value     (pr-str choice)
           :checked   (= current-val choice)
           :onChange  (fn [event]
                        (let [value      (.. event -target -value)
                              field-info {:form-id id
                                          :field   field-name
                                          :value   (reader/read-string value)}]
                          (om/transact! component
                            `[(set-field ~field-info)
                              ~@(get-on-form-change-mutation form field-name :edit)
                              ~form-root-key])))})
      (dom/label #js {:htmlFor field-id} label))))

(defmethod form-field* ::textarea [component form field-name & {:keys [id className] :as htmlProps}]
  (let [form-id (form-ident form)
        cls     (or className (css-class form field-name) "")
        value   (current-value form field-name)
        attrs   (clj->js (merge htmlProps
                           {:name      field-name
                            :id        id
                            :className cls
                            :value     value
                            :onBlur    (fn [_]
                                         (om/transact! component
                                           `[(validate-field ~{:form-id form-id :field field-name})
                                             ~@(get-on-form-change-mutation form field-name :blur)
                                             ~form-root-key]))
                            :onChange  (fn [event]
                                         (let [value      (.. event -target -value)
                                               field-info {:form-id form-id
                                                           :field   field-name
                                                           :value   value}]
                                           (om/transact! component
                                             `[(set-field ~field-info)
                                               ~@(get-on-form-change-mutation form field-name :edit)
                                               ~form-root-key])))}))]
    (dom/textarea attrs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOAD AND SAVE FORM TO/FROM ENTITY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol DBAdapter
  (commit! [this params]
    "Entry point for creating (& executing) a transaction,
     given params with the same shape as what diff-form returns.
     Example code for using `DBAdapter/commit!`:
     (defmethod your-mutate `forms/commit-to-entity [env k params]
       (commit! (:adapter env) params))")
  (transact! [this tx] "Execute a transaction!")
  (gen-tempid [this] "Generates a db tempid.")
  (parse-tx [this tx-type data]
    "Given a tx-type and data, transforms it into a db transaction.
     OR TODO: Should this be add-tx, set-tx, etc..."))

(defn- field-diff* [curr orig cfg]
  (case (:input/cardinality cfg)
    :many (let [[curr-set orig-set] (map set [curr orig])
                additions (set/difference curr-set orig-set)
                removals  (set/difference orig-set curr-set)]
            (cond-> {}
              (seq removals)  #_=> (assoc :form/remove-relations (vec removals))
              (seq additions) #_=> (assoc :form/add-relations (vec additions))))
    :one (cond-> {}
           curr #_=> (assoc :form/add-relations curr)
           orig #_=> (assoc :form/remove-relations orig))
    (when (not= curr orig) {:form/updates curr})))

(defn- field-diff [form diff field]
  (let [ident (form-ident form)
        cfg   (field-config form field)
        curr  (?normalize cfg (current-value form field))
        orig  (?normalize cfg (get-original-data form field))]
    (reduce (fn [diff [tx-type value]]
              (assoc-in diff [tx-type ident field] value))
      diff (field-diff* curr orig cfg))))

(defn diff-form
  "Returns the diff between the form's current state and its original data.
   The return value is a map where the keys are the idents of the forms that have changed,
   and the values are vectors of the keys for the fields that changed on that form.

   Return value:
   {:form/new-entities {[:phone/by-id #phone-id] {...}}
    :form/updates {[:phone/by-id 1] {:phone/number \"123-4567\"}}
    :form/add-relations {[:person/by-id 1] {:person/number #{phone-id-not-ident ...}}}
    :form/remove-relations {[:person/by-id 1] {:person/number #{4 5}}}}"
  [root-form]
  (form-reduce root-form {}
    (fn [diff form]
      (let [[_ id :as ident] (form-ident form)
            fields (element-names form)]
        (if (om/tempid? id)
          (reduce
            (partial field-diff form)
            (assoc-in diff [:form/new-entities ident] (select-keys form (into [] (comp (remove (partial ui-field? form))
                                                                                   (remove (partial is-subform? form))) fields)))
            (filter (partial is-subform? form) fields))
          (transduce (comp
                       (remove (partial ui-field? form))
                       (filter (partial dirty-field? form)))
            (completing (partial field-diff form))
            diff fields))))))

(defn entity-xform
  "Modify the form's (under `form-id`) using `update-forms` and a passed in transform `xf`"
  [state form-id xf]
  (update-forms state
    (get-in state form-id)
    (comp xf :form)))

#?(:cljs (defmutation commit-to-entity
           "Om Mutation: Commit the changes on the form. This will cause the state of the pristine cache to match what you see on the
           form. Note, until tempids are rewritten a form will appear modified (unsaved changes).

           `form` is the COMPLETE PROPS of a form. NOT AN IDENT.
           `remote` is true if you wish the form to submit changes to the server. If you do not use this option it will
           be up to you to define how you will persist any data to the server.
           "
           [{:keys [form remote]}]
           (action [{:keys [state]}] (swap! state entity-xform (form-ident form) commit-state))
           (remote [{:keys [state ast target]}]
             (when (and remote target)
               (assoc ast :params (diff-form form))))))

#?(:cljs (defmutation reset-from-entity
           "Om Mutation: Reset the entity back to its original state before editing. This will be the last state that
           the entity had just following initialization or the last commit.

           `form-id` is the ident of the entity acting as a form.
           "
           [{:keys [form-id]}]
           (action [{:keys [state]}] (swap! state entity-xform form-id reset-entity))))

(defn reset-from-entity!
  "Reset the form from a given entity in your application database using an Om transaction and update the validation state.
   You may compose your own Om transactions and use `(f/reset-from-entity {:form-id [:entity id]})` directly."
  [comp-or-reconciler form]
  (let [form-id (form-ident form)]
    (om/transact! comp-or-reconciler
      `[(reset-from-entity ~{:form-id form-id})
        ~form-root-key])))

(defn commit-to-entity!
  "Copy the given form state into the given entity. If remote is supplied, then it will optimistically update the app
   database and also post the entity to the server.

   IMPORTANT: This function checks the validity of the form. If it is invalid, it will NOT commit the changes, but will
   instead trigger an update of the form in the UI to show validation errors.

   For remotes to work you must implement `(f/commit-to-entity {:form-id [:id id] :value {...})`
   on the server. "
  [component & {:keys [remote rerender] :or {remote false}}]
  (let [form (om/props component)]
    (om/transact! component
      (reduce conj
        [(if (valid? (validate-fields form))
           `(commit-to-entity ~{:form form :remote remote})
           `(validate-form ~{:form-id (form-ident form)}))
         form-root-key]
        rerender))))

