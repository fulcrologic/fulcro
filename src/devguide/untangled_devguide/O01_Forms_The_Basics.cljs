(ns untangled-devguide.O01-Forms-The-Basics
  (:require
    [clojure.string :as str]
    [com.stuartsierra.component :as component]
    [devcards.core :as dc :refer-macros [defcard defcard-doc]]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]
    [untangled.client.core :as uc]
    [untangled.client.mutations :as m :refer [defmutation]]
    [untangled.client.cards :refer [untangled-app]]
    [untangled.ui.forms :as f :refer [defvalidator]]
    [untangled.i18n :refer [tr]]))

(declare ValidatedPhoneForm)

;; Sample validator that requires there be at least two words
(f/defvalidator name-valid? [_ value args]
  (let [trimmed-value (str/trim value)]
    (str/includes? trimmed-value " ")))

(defvalidator us-phone?
  [sym value args]
  (seq (re-matches #"[(][0-9][0-9][0-9][)] [0-9][0-9][0-9]-[0-9][0-9][0-9][0-9]" value)))

(defn field-with-label
  "A non-library helper function, written by you to help lay out your form."
  ([comp form name label] (field-with-label comp form name label nil))
  ([comp form name label validation-message]
   (dom/div #js {:className (str "form-group" (if (f/invalid? form name) " has-error" ""))}
     (dom/label #js {:className "col-sm-2" :htmlFor name} label)
     ;; THE LIBRARY SUPPLIES f/form-field. Use it to render the actual field
     (dom/div #js {:className "col-sm-10"} (f/form-field comp form name))
     (when (and validation-message (f/invalid? form name))
       (dom/span #js {:className (str "col-sm-offset-2 col-sm-10" name)} validation-message)))))

(defn checkbox-with-label
  "A helper function to lay out checkboxes."
  ([comp form name label] (field-with-label comp form name label nil))
  ([comp form name label validation-message]
   (dom/div #js {:className "checkbox"}
     (dom/label nil (f/form-field comp form name) label))))

(defui ^:once PhoneForm
  static uc/InitialAppState
  (initial-state [this params] (f/build-form this (or params {})))
  static f/IForm
  (form-spec [this] [(f/id-field :db/id)                    ; Mark which thing is the ID of this entity
                     (f/text-input :phone/number :className "form-control")
                     (f/dropdown-input :phone/type [(f/option :home "Home") (f/option :work "Work")])])
  static om/IQuery
  (query [this] [:db/id :phone/type :phone/number f/form-key]) ; Don't forget f/form-key!
  static om/Ident
  (ident [this props] [:phone/by-id (:db/id props)])
  Object
  (render [this]
    (let [form (om/props this)]
      (dom/div #js {:className "form-horizontal"}
        (field-with-label this form :phone/type "Phone type:") ; Use your own helpers to render out the fields
        (field-with-label this form :phone/number "Number:")))))

(def ui-phone-form (om/factory PhoneForm {:keyfn :db/id}))

(defmutation add-phone [{:keys [id person]}]
  (action [{:keys [state]}]
    (let [new-phone    (f/build-form ValidatedPhoneForm {:db/id id :phone/type :home :phone/number ""})
          person-ident [:people/by-id person]
          phone-ident  (om/ident ValidatedPhoneForm new-phone)]
      (swap! state assoc-in phone-ident new-phone)
      (uc/integrate-ident! state phone-ident :append (conj person-ident :person/phone-numbers)))))

(defui PhoneRoot
  static om/IQuery
  (query [this] [{:phone (om/get-query PhoneForm)}])
  static uc/InitialAppState
  (initial-state [this params]
    (let [phone-number {:db/id 1 :phone/type :home :phone/number "555-1212"}]
      {:phone (f/build-form PhoneForm phone-number)}))
  Object
  (render [this]
    (let [{:keys [phone]} (om/props this)]
      (dom/div nil
        (ui-phone-form phone)))))

(defcard-doc
  "# Forms – The Basics

  Generic form support is a primary rapid appliation development feature. Fortunately, the overall structure of Om Next
  and Untangled makes it relatively simple to write form support in a general-purpose, composeable manner. This library
  defines form support that has:

  - Declarative forms
  - An extensible set of form fields
  - Extensible validation
  - Separation of form UI from form logic
  - Remote integration with form <-> entity
  - Local integration with entities in the browser database

  The following `requires` define the namespacing used in the examples:

  ```
  (ns your-ns
    (:require
      [untangled.client.cards :refer [untangled-app]]
      [clojure.string :as str]
      [com.stuartsierra.component :as component]
      [om.dom :as dom]
      [om.next :as om :refer [defui]]
      [untangled.client.core :as uc]
      [untangled.client.mutations :as m]
      [untangled.ui.forms :as f]
      [untangled.i18n :refer [tr]]))
  ```

  **IMPORTANT NOTE**: When we use the parameter `form` or the word 'form' in the descriptions below, we mean the data
  of the entire entity from an Om table that normally represents something in your application (like a person, phone number, etc).
  This library *augments* your database entry with form support data (your 'person' becomes a 'person' AND a 'form'). In
  raw technical terms, the `build-form` function takes a map, and adds a `f/form-key { ... }` entry *to it*. The only
  implication for your UI is that your component queries must be expanded to include queries for this additional support
  data.

  ## Your Component as a Form

  Components that wish to act as forms must meet the following requirements (here `f` is an alias for the forms namespace):

  - They must implement `f/IForm`
      - The fields method must return a list of fields that includes an `id-field`
  - They must implement `om/Ident`
  - They must implement `om/IQuery`, and include extra bits of form query (the key `f/form-key`)
  - The app state of the entity acting as a form must be augmented via `f/build-form` (e.g. using a mutation or app initialization)
  - They render the form fields using `f/form-field`.

  ### Step 1: Declare the Form Fields

  Form fields are declared on the ui component that will render the form via the `f/IForm` protocol. The fields themselves
  are declared with function calls that correspond to the field type:

  - `id-field` : A (meant-to-be-hidden) form field that corresponds to the attribute that uniquely identifies the entity being edited. Required for much of the interesting support.
  - `text-input` : An optionally validated input for strings.
  - `dropdown-input` : A menu that allows the user to choose from a set of values.
  - `checkbox-input` : A boolean control
  - your-input-here! : Form support is extensible. Whatever interaction you can imagine can be added to a form.

  Form fields are really just simple maps of attributes that describe the configuration of the specified input.

  The built-in support for doing form logic expects the fields to be declared on the component that will
  render the form.

  ```
  (om/defui MyForm
    static f/IForm
    (form-spec [this] [(f/id-field :db/id)
                       (f/text-input :person/name)
                       ...]
    ...
  ```

  ## Step 2: Rendering the Form Fields

  The form fields themselves are rendered by calling `(f/form-field form field-name)`. This method **only** renders
  the simple input itself.

  `(f/form-field my-form :name)` --- outputs ---> `(dom/input #js { ... })`

  This is the minimum we can do to ensure that the logic is correctly connected, while not interfering with your
  ability to render the form however you please.

  You'll commonly write some functions of your own that combine other DOM markup with this, such as the function
  `field-with-label` shown in the example. Additional functions like `f/invalid?` can be used to make decisions about
  showing/hiding validation messages.

  "
  (dc/mkdn-pprint-source field-with-label)
  "

  **The rendering of the form is pretty much up to you! Thus, your forms can be as pretty (or ugly) as you care to make
  them. No worrying about figuring out how we render them, and then trying to make *that* look good.**

  That said, there is nothing preventing you (or us) from supplying a library function that can produce reasonable looking
  reusable form rendering.

  ## Step 3: Setting Up the Form State

  A form can augment any entity in an app database table in your client application. The `f/build-form` function
  can take any such entity and add form support to it. The result is perfectly compatible with the original entity. The
  example shown above is doing this on application start, but it is trivial to compose `f/build-form` into a mutation
  (for example, a mutation that is changing the UI to display the form can simultaneously initialize the entity to-be-edited
  at the same time.

  ## A Complete Form Component

  If we compose the above form into this UI root:
  "
  (dc/mkdn-pprint-source PhoneForm)
  (dc/mkdn-pprint-source PhoneRoot)
  "We can embed it into an active dev card to play with it (you may edit the devcard options to include :")

(defcard phone-form
  "A Sample Form (edit this card and set `:inspect-data` to `true` to see the augmented data)"
  (untangled-app PhoneRoot)
  {}
  #_{:inspect-data true})

(defui ^:once ValidatedPhoneForm
  static uc/InitialAppState
  (initial-state [this params] (f/build-form this (or params {})))
  static f/IForm
  (form-spec [this] [(f/id-field :db/id)
                     (f/text-input :phone/number :validator `us-phone?) ; Addition of validator
                     (f/dropdown-input :phone/type [(f/option :home "Home") (f/option :work "Work")])])
  static om/IQuery
  (query [this] [:db/id :phone/type :phone/number f/form-key])
  static om/Ident
  (ident [this props] [:phone/by-id (:db/id props)])
  Object
  (render [this]
    (let [form (om/props this)]
      (dom/div #js {:className "form-horizontal"}
        (field-with-label this form :phone/type "Phone type:")
        ;; One more parameter to give the validation error message:
        (field-with-label this form :phone/number "Number:" "Please format as (###) ###-####")))))

(def ui-vphone-form (om/factory ValidatedPhoneForm))

(defui ValidatedPhoneRoot
  static om/IQuery
  (query [this] [f/form-key {:phone (om/get-query ValidatedPhoneForm)}])
  static uc/InitialAppState
  (initial-state [this params]
    (let [phone-number {:db/id 1 :phone/type :home :phone/number "555-1212"}]
      {:phone (f/build-form ValidatedPhoneForm phone-number)}))
  Object
  (render [this]
    (let [{:keys [phone]} (om/props this)]
      (dom/div nil
        (ui-vphone-form phone)))))

(defcard-doc
  "

  ## Validation

  There is a multimethod `(f/form-field-valid? [symbol value args])`
  that dispatches on symbol (symbols are allowed in app state, lambdas are not). Form fields that support validation
  will run that validation at their configured time (typically on blur).
  Validation is therefore completely extensible. You need only supply a dispatch for your own validation symbol, and
  declare it as the validator on a field (by symbol).

  Validation is tri-state. The allowed states are `:valid` (checked and correct), `:invalid` (checked and incorrect),
  and `:unchecked`.

  You can trigger full-form validation (which you should do as part of your interaction with the form) by calling
TODO: remove the need to pass the component? The form is just om/props of the component.
  `(f/validate-entire-form! component form)`. This function invokes a transaction that will update the validation
  markings on all declared fields (which in turn will re-render your UI).

  If you want to check if a form is valid (without updating the markings in the app state...e.g. you want an inline
  answer), then use `(f/valid? (f/validate-fields form))` to get an immediate answer. This is more computationally
  expensive, but allows you to check the validity of the form without triggering an actual validation transaction against
  the application state.

  For example, the definition of a validator for US phone numbers could be:

  ```
  (defvalidator us-phone?
    [sym value args]
    (seq (re-matches #\"[(][0-9][0-9][0-9][)] [0-9][0-9][0-9]-[0-9][0-9][0-9][0-9]\" value)))
  ```

  The only change in your UI would be to add the validator to the form field declaration, along with a validation message:
  "
  (dc/mkdn-pprint-source ValidatedPhoneForm))

(defcard validated-phone-number
  "Edit the phone field and then set the phone type. The blur will trigger validation"
  (untangled-app ValidatedPhoneRoot))

(defui ^:once PersonForm
  static uc/InitialAppState
  (initial-state [this params] (f/build-form this (or params {})))
  static f/IForm
  (form-spec [this] [(f/id-field :db/id)
                     (f/subform-element :person/phone-numbers ValidatedPhoneForm :many)
                     (f/text-input :person/name :validator `name-valid?)
                     (f/integer-input :person/age :validator `f/in-range?
                       :validator-args {:min 1 :max 110})
                     (f/checkbox-input :person/registered-to-vote?)])
  static om/IQuery
  ; NOTE: f/form-root-key so that sub-forms will trigger render here
  (query [this] [f/form-root-key f/form-key
                 :db/id :person/name :person/age
                 :person/registered-to-vote?
                 {:person/phone-numbers (om/get-query ValidatedPhoneForm)}])
  static om/Ident
  (ident [this props] [:people/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [person/phone-numbers] :as props} (om/props this)]
      (dom/div #js {:className "form-horizontal"}
        (field-with-label this props :person/name "Full Name:" "Please enter your first and last name.")
        (field-with-label this props :person/age "Age:" "That isn't a real age!")
        (checkbox-with-label this props :person/registered-to-vote? "Registered?")
        (when (f/current-value props :person/registered-to-vote?)
          (dom/div nil "Good on you!"))
        (dom/div nil
          (mapv ui-vphone-form phone-numbers))
        (when (f/valid? props)
          (dom/div nil "All fields have had been validated, and are valid"))
        (dom/div #js {:className "button-group"}
          (dom/button #js {:className "btn btn-primary"
                           :onClick   #(om/transact! this
                                         `[(add-phone ~{:id     (om/tempid)
                                                        :person (:db/id props)})])}
            "Add Phone")
          (dom/button #js {:className "btn btn-default" :disabled (f/valid? props)
                           :onClick   #(f/validate-entire-form! this props)}
            "Validate")
          (dom/button #js {:className "btn btn-default", :disabled (not (f/dirty? props))
                           :onClick   #(f/reset-from-entity! this props)}
            "UNDO")
          (dom/button #js {:className "btn btn-default", :disabled (not (f/dirty? props))
                           :onClick   #(f/commit-to-entity! this)}
            "Submit"))))))

(def ui-person-form (om/factory PersonForm))

(defui ^:once Root
  static uc/InitialAppState
  (initial-state [this params]
    {:ui/person-id 1
     :person       (uc/initial-state PersonForm
                     {:db/id                      1
                      :person/name                "Tony Kay"
                      :person/age                 23
                      :person/registered-to-vote? false
                      :person/phone-numbers       [(uc/initial-state ValidatedPhoneForm
                                                     {:db/id        22
                                                      :phone/type   :work
                                                      :phone/number "(123) 412-1212"})
                                                   (uc/initial-state ValidatedPhoneForm
                                                     {:db/id        23
                                                      :phone/type   :home
                                                      :phone/number "(541) 555-1212"})]})})
  static om/IQuery
  (query [this] [:ui/person-id {:person (om/get-query PersonForm)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key ui/person-id person]} (om/props this)]
      (dom/div #js {:key react-key}
        (when person
          (ui-person-form person))))))

(defcard-doc
  "
  ## State Evolution

  A form will initially record the pristine state of field values during `build-form`. As you interact with
  the form the entity data will change (locally only). This allows the library to support:

  - The ability to compare the original entity state with the current (edited) state
  - Reset the entity state from the pristine condition
  - Commit *just* the actual changes to the entity to a remote

  **This, combined with a little server code, makes the form support full stack!**

  You can trigger the following operations on a form:

  - `(f/commit-to-entity! comp)` : Commit the current edits to the entity (no-op if the form doesn't validate)
  - `(f/commit-to-entity! comp true)` : Commit the current edits to the entity AND the server (is a no-op if the form doesn't validate)
  - `(f/reset-from-entity! comp)` : Undo the changes on the form (back to the pristine state of the original), (triggers validation after the reset)
  - More coming...

  ### State evolution within your own transactions

  Any changes you make to your entity after `build-form` are technically considered form edits (and make the form *dirty*
  and possibly *invalid*).  The built-in form fields just change the state of the entity, and you can too.

  Commits will copy the entity state into the form's pristine holding area, and resets will copy from this pristine area
  back to your entity.

  The primary concern is that any custom fields that you create should be careful to only populate the value of fields
  with things that are serializable via transit, since their updated values will need to be transmitted across the wire
  for full-stack operation.

  ## Composition

  Form support augments normalized entities in your app database, so they can be easily composed! They are UI components, and have nothing special
  about them other than the `f/form-key` state that is added to the entity (through your call of `build-form`).
  You can convert any entity in your database to a form using the `build-form` function, meaning that you can load
  entities as normal, and as you want to edit them
  in a form, first mutate them into form-compatible entities with `build-form` (which will not touch the original
  properties of the entity, just add `f/form-key`). Then render them with a UI component that shares your entity Ident,
  but has a render method that renders the form fields with `form-field`.

  Here is the source for an application that renders a Person form, where the person can have any nubmer of phone numbers,
  each represented by a nested phone number entity/form. Note the use of `InitialAppState` in Root to build out sample
  data.
  "
  (dc/mkdn-pprint-source ValidatedPhoneForm)
  (dc/mkdn-pprint-source PersonForm)
  (dc/mkdn-pprint-source Root)
  "

  ### Composition and Rendering Refresh

  The one caveat is that when forms are nested the mutations on the nested fields cannot (due to the design of Om) refresh
  the parent automatically. To work around this, all built-in form mutations will trigger follow-on reads of
  the special property `f/form-root-key`. So, if you add that to your parent form's query, rendering of the top-level
  form elements (e.g. buttons that control submission) will properly update when any element of a subform changes.

  ### Adding Sub-form Elements

  Adding a phone number (which acts as a sub-form) is done via the `add-phone` mutation, which looks like this:

  ```
  (defmutation add-phone [{:keys [id person]}]
    (action [{:keys [state]}]
      (let [new-phone    (f/build-form ValidatedPhoneForm {:db/id id :phone/type :home :phone/number " "})
            person-ident [:people/by-id person]
            phone-ident  (om/ident ValidatedPhoneForm new-phone)]
        (swap! state assoc-in phone-ident new-phone)
        (uc/integrate-ident! state phone-ident :append (conj person-ident :person/phone-numbers)))))
  ```

  Notice that there is nothing really special going on here. Just add an additional item to the database (which is
  augmented with `f/build-form`) and integrate it's ident!

  If you look carefully at `PersonForm` you'll see the button to trigger adding a phone number, where we're using
  `(om/tempid)` to generate a temporary ID for the new phone number.

  ### Compositional Dirty-Checking, Validation, and Submission

  The code also shows how you would compose the checks. The `dirty?` function combines the results of the nested forms
  together with the top form. You could do the same for validations.

  The `Save` button does a similar thing: it submits the phone numbers, and then the top. Note that Untangled combines
  mutations that happen in the same thread sequence (e.g. you have not given up the thread for rendering). So, all of
  those commits will be sent to the server as a single transaction (if you include the remote parameter).
  ")

(defcard sample-form-1
  "This card shows a very simple form in action. (Edit the code and set :inspect-data to true to watch app state)"
  (untangled-app Root)
  {}
  {:inspect-data false})

(defcard-doc
  "## Adding Form Field Types

  Adding a new kind of form field is simple:

  - Create a method that returns a map of input configuration values
  - Add a multimethod that can render your field with appropriate hooks into the logic

  The text input field is implemented like this:

  "
  (dc/mkdn-pprint-source f/text-input)
  "

  The keys in an input's configuration map are:

  - `:input/name` : Required. What you want to call the field. Must match an entity property (e.g. :person/name).
  - `:input/type` : Required. Usually namespaced. This should be a unique key that indicates what kind of input you're making
  - `:input/validator` : Optional. Specifies a symbol (dispatch of the form-field-valid? multimethod).
  - `:input/validator-args` : Optional. If there is a validator, it is called with the validator symbol, the questionable value, and these args.
  - Any you want to define : This is a map. Put whatever else you want in this map to help with rendering (e.g. placeholder text,
   class names, style, etc).

  and its renderer looks like this:

  "
  (dc/mkdn-pprint-source f/render-text-field)
  "
  ```
  (defmethod form-field* ::text [component form name] (render-text-field component form name))
  ```

  You can retrieve a field's current form value with `(f/current-value form field-name)`, and you can obtain
  your field's configuration (map of :input/??? values) with `(f/field-config form field-name)`.

  The `form-field*` multimethod should, in general, return as little as possible, but you are allowed to do whatever you want.
  You are free to make form field renderers that render much more complex DOM, an SVG, etc.

  The following built-in mutations can (and should) be used in your event handlers:

  - `(untangled.ui.form/validate-field {:form-id [:ident/by-x n] :field :field-name})` - Run validation on the given form/field. Marks the form state for the field to `:invalid` or `:valid`. Fields without validators
  will be marked `:valid`.
  - `(untangled.ui.form/set-field {:form-id [:ident/by-x n] :field :field-name :value raw-value})` - Set the raw-value (you can use any type) onto the form's placeholder state (not on the entity)
  - Others listed elsewhere, like those that commit, validate, etc.

  ## Other Functions of Interest

  Since the `form` is also your entity, you may of course pull any entity data from the `form` map. (E.g. you can
  for example directly access `(:person/name person-form)`). The form attributes are stored under the `f/form-key` key
  and are intended to be opaque. Do not sneak access into the data structure, since we may choose to change the structure
  in future versions. Instead, use these:

  - `f/current-value` : Get the most recent value of a field from a form
  - `f/current-validity` : Get the most recent result of validation on a field
  - `f/valid?` : Test if the form (or a field) is currently marked valid (must run validation separately)
  - `f/invalid?` : Test if the form (or a field) is currently marked invalid (must run validation separately)
  - `f/field-names` : Get the field names on a form
  - `f/form-id` : returns the Om Ident of the form (which is also the ident of the entity)
  - `f/validate-fields` : returns a new version of the form with the fields marked with validation. Pure function.
  - `f/validate-entire-form!` : Transacts a mutation that runs and sets validation markers on the form (which will update UI)
   ")
