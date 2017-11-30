(ns fulcro-devguide.O02-Forms-State-Lifecycle
  (:require
    [clojure.string :as str]
    [com.stuartsierra.component :as component]
    [devcards.core :as dc :refer-macros [defcard-doc]]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defui defsc]]
    [fulcro.client.core :as fc]
    [fulcro.client.mutations :as m]
    [fulcro.ui.forms :as f]
    [fulcro.i18n :refer [tr]]))

(defcard-doc
  "# Form State and Lifecycle

  Form support is meant to track the state of one or more entities through
  the process of editing. It is important to remember the general
  model of Fulcro is one where the application moves from
  state to state over time, and the components themselves have (ideally)
  no local state.

  This means that your form will not change states on you unless a mutation
  of some sort runs.

  Many of the form fields *do* run mutations on events, which in turn
  can change the state of the form or the fields.

  A form can have any number of child forms (which themselves can dynamically change
  over time).

  The lifecycle is as follows:

  ```
  Regular Persisted Entity
        |
        | build-form
        v
  Pristine Form <------------+
        |                    |
        | edits/additions    ^
        v                   /|
   Dirty Form -------------/ |
              reset/commit>  +------+
                                    |
  Locally Created (tempid)          |
        |                           |
        | build-form                |
        v                           | (server tempid remap)
   Dirty Form                       |
    ^    |                         _|_
    |    | edits/additions/reset/commit
    +----+
  ```

  ## Is my Form Dirty?

  A form is considered `dirty?` when:

  - Any field of the form or its declared subforms has a value different from
  the initial (or most recently committed) value.
  - Any form or subform in a set has a tempid (e.g. server remaps have not
  yet taken effect)

  NOTE: If you're writing forms on a UI that has
  no server interaction then you will probably want to generate your own
  numeric unique IDs for any new entities to prevent permanently dirty forms.

  ## Is my Form/Field Valid?

  Form fields that support validation will typically run validation on the field when that
  field is manipulated. Full-form validation can be done at any time by composing `validate-fields`
  into your own mutation (see also `on-form-change`). The system is fully flexible, and for the
  most part validation is composable, extensible, configurable, and happens at transaction
  boundaries in whatever ways you define.

  Validation is tri-state. All fields start out `:unchecked`. If you wish your form to start out
  `:valid` then you can compose a call to `f/valiate-fields`:

  ```
  ;; NOTE: non-recursive validation. You'd have to use this explicitly on each declared subform state as well.
  initial-form (f/validate-fields (f/build-form MyForm my-entity-props))
  ```

  The functions `valid?` and `invalid?` honor the tri-state nature of forms (e.g. `invalid?` returns
  true iff at least one field is `:invalid`, and `valid?` returns true iff all fields are
  `:valid`). The `:unchecked` state thus allows you to prevent error messages from
  appearing on fields until you're actually ready to validate them:

  ```
  ;; only emits if the field is actually marked :invalid (not :unchecked)
  (when (invalid? this-form :field) (dom/span nil \"Invalid!\"))

  ;; Disables submit button unless all fields are marked `:valid` (none are :unchecked or :invalid)
  (dom/button #js {:disabled (not (valid? form)) :onClick submit} \"Submit!\")
  ```

  The tricky part is that \"global\" validation is not ever triggered by built-in iteraction
  support with fields.

  Thus, you have a few ways of dealing with checking if a form is valid:

  1. Trigger a `f/validate-form` mutation. Such a mutation will recursively walk your form and
  subforms and mark all fields with `:invalid` or `:valid`. This will have the effect of
  showing validation messages that are defined in the examples above.
  2. Compose the `f/validate-forms` helper function into your own mutation. This function works against
  an app state map and recursively updates a form/subforms. (see the source for `defmutation validate-form`)
  3. Use the `would-be-valid?` function on the forms props (e.g. in the UI). This function returns true
  if the supplied form (and subforms) would be valid if validation was run on it. It essentially runs
  validation in a pure functional way.

  If using (1) or (2), then the methods `valid?` and `invalid?` can recursively test the validity. Note that
  as fields are changed the state of those fields may return to unchecked (which is neither valid or invalid).
  ")
