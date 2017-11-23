(ns fulcro-devguide.O03-Forms-Server-Integration
  (:require
    [clojure.string :as str]
    [com.stuartsierra.component :as component]
    [devcards.core :as dc :refer-macros [defcard defcard-doc]]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defui]]
    [fulcro.client.cards :refer [fulcro-app]]
    [fulcro.client.core :as fc]
    [fulcro.client.mutations :as m]
    [fulcro.ui.forms :as f]
    [fulcro.i18n :refer [tr]]))

(defcard-doc
  "# Server Integration

  A form (and associated subforms) have support for saving state to the
  server. This support takes the best view it possibly can of the possible
  things a user can do to an entity via a form:

  A user should be able to:

  1. Change the value of one or more fields.
  2. Add a completely new entity (with tempid) to the database (e.g. new form or new subform)
  3. Link an existing form to an instance of a subform. The subform might have been looked up (by your code)
  or created (via 2).
  4. Remove the linkage from a form to a subform.

  ## Handling Submission of a Form

  Form submission has some general helpers. In general, it is recommended that you write your own
  custom mutation to do a form submission, but there is a general-purpose mutation that can also do it
  for you.

  The advantage of using the general-purpose mutation is that it is already written, but the disadvantage is that
  all of your form submissions become centralized into a single point of entry in the server and more significantly
  become difficult to distinguish from each other in the actual transaction logs.

  Thus, when getting started you might choose to play with the built-in mechanism, but as you progress we highly
  recommend you customize form submission, which is actually quite simple to do.

  ### Built-in Form Submisssion – `commit-to-entity`

  A form submission can be done via the `commit-to-entity` function/mutation with the inclusion
  of a `:remote` flag. The function version `commit-to-entity!` is a simple wrapper of a
  `transact!` that invokes the `f/commit-to-entity` mutation (where `f` is the forms namespace).
  The former is a convenience, and the latter is more useful when you want to compose commit with
  other transaction actions (such as navigation away from the form).

  The wrapper function must be called using the `this` of the component that is the top-level of the form,
  and the mutation must be called with the `props` of the top-level form.

  Both default to a local commit (where you can deal with persistence in some other way), but if you supply
  them with a remote argument they will send the *changes* to the data in the form to the server. Of course,
  new entities will be completely sent.

  Of course to handle this mutation you must implement a server-side mutation with the fully-namespaces
  `fulcro.ui.forms/commit-to-entity` name.

  ### Custom Form Submission

  Custom form submission allows you to do a number of custom things:

  - Choose optimistic *or* response-driven form submission.
  - Combine submission with other form checking logic and UI updates.
  - Name your submit mutation so that you can easily keep them separate.

  The primary utility functions in `fulcro.ui.forms` for implementing such mutations are:

  - `(f/diff-form form-root-props)`: This function calculates a diff of the form (and subforms). Only new and changed
  data will be included (see later sections). You must pass this function the tree version of a form (e.g. pass the form-props
  as an arg to the mutation). It is important to calculate the diff outside of the mutation, because of the multi-pass nature
  of mutation bodies (e.g. you'll need the delta in remote, but action may have already done an optimistic local commit).
  - `(f/entity-xform state-map ident f)` Recursively walks a form set starting at `ident` in `state-map` running `f` on each (sub)form.
  - `(f/commit-state form-props)`: This is a non-recursive function that copies form data from the edited area to the
  pristine area locally. Typically called as `(swap! state f/entity-xform form-ident commit-state)`.
  (which must be sent from the UI invocation of transact as a parameter or reconstituted as a tree with `db->tree`). It
  copies the new state to the pristine state, making the form appear complete and clean (submitted) on the client.
  - `(f/reset-entity form-props)`: The opposite of `commit-state`, but called via `entity-xform` as well.
  - `(f/would-be-valid form-props)` : Requires a tree of form props (e.g. from the UI or `db->tree`). Returns true if
  the given form would be considered valid if a validation mutation were run on it. Useful in form submission logic where
  pushing validation data to the UI can be bypassed because you can tell it is OK.
  - `(f/dirty? form-props)`: Requires tree of props (from UI). Returns true if the form has changes (needs a submit)
  - `(f/validate-forms state-map form-ident)`: Used from *within* mutations. Causes form validation mutation on the
  recursive form, which will cause UI updates. Use this if you decide not to submit the form and want to show why on the UI.

  Notice that a lot of these functions are meant to be usable at the UI (they work on a tree of form props). The reason
  there are not two versions is that because of optimistic updates and how mutations work, it is advisable to pass your
  form's tree of props through to the mutation, so that your mutation closes over the form state at the time of user
  interaction. This allows you to properly calculate your form delta and have a consistent snapshot for the remote
  interaction.

  It is useful to study the code of the built-in commit, and mimic parts of the behavior for your own mutations:

  The `commit-to-entity!` function calls transact, but note how it pulls props and constructs the mutation to
  include that tree. It also triggers validation, to ensure that the UI shows updated validation messages (clearing
  errors or adding new ones). It also includes the `f/form-root-key` to ensure the entire form set will re-render:
  "
  (dc/mkdn-pprint-source f/commit-to-entity!)
  "

  The mutation itself now has all of the data it needs to calculate a diff and such. Here is its implementation:

  ```
  (defmutation commit-to-entity
    [{:keys [form remote]}]
    (action [{:keys [state]}] (swap! state entity-xform (form-ident form) commit-state))
    (remote [{:keys [state ast target]}]
      (when (and remote target)
        (assoc ast :params (diff-form form)))))
  ```

  Note that the local optimistic update just copies the edited state over top of the pristine (this mutation
  isn't run if the form doesn't validate...see the earlier function).

  The remote side modified the parameters so that instead of sending the form's UI tree to the server, it instead
  sends a calculated diff. See below for how to deal with this diff.

  ## What Your Server Must Do

  On the server, you must provide a mutation handler for your mutation or the `f/commit-to-entity` symbol (where `f` is the
  forms namespace). If you're using multimethods on the server, this might look like:

  ```
  (ns amazing-server.mutations
  (:require
  [fulcro.client.primitives :as prim]
  [fulcro.ui.forms :as f]))

  (defmulti my-mutate prim/dispatch)

  ;; NOTE: the syntax quote will honor the `f` aliasing in the ns.
  (defmethod my-mutate `f/commit-to-entity [env k params]
  {:action (fn [] ...)})
  ```

  The complete form delta will be in the incoming `params`. The description of the entries in `params`
  is below.

  ## Processing a Form's Diff

  The incoming parameters is a map. This map will contain up to four different keys to indicate
  what changed on the client.

  ### Form Field Updates

  Field updates are sent under the following conditions:

  - The entity of the form has a REAL ID
  - One or more fields have values different from those at the time of `build-form` or the last commit.

  The parameters sent to the `commit-to-entity` mutation on the server will include the key
  `:form/updates` whose value will be a map. The map's keys will be client-side idents of
  the entities that changed, and the values will be maps of k-v pairs of the data the changed.

  Examples:

  ```
  {:form/updates { [:thing 1] {:field-a 1 }
            [:thing 2] {:field-b 55 }}}
  ```

  NOTES:

  - Updates will *never* include referential updates (e.g. A references subform element B). See
  New Relations and Removed Relations below.
  - Fields on the entity in the UI that are *not* declared as form fields *will never* appear in
  an update.

  ### New Entities

  When a form (and/or subforms) is submitted that has a primary ID whose value is a tempid then
  the incoming commit parameters will include the `:form/new-entities` key. The value of this entry is just like
  that of `:form/updates`, but the ID in the ident will be a tempid (which you must send remaps
  back for), and the map of data will include all attributes of that entity that were declared as part
  of the form.

  ```
  {:form/new-entities { [:thing tempid-1] {:field-a 1 :field-b 55 }
                 [:thing tempid-2] {:field-a 8 :field-b 42 }}}
  ```

  It is important that you remember to return a map to remap the incoming tempids:

  ```
  (defmethod my-mutate `f/commit-to-entity [env k params]
  {:action (fn []
         ...
         {:tempids {tempid-1 realid-1 tempid-2 realid-2}})})
  ```

  NOTES:
  - New entity properties include only the columns declared in the form support. Remember that you can
  declare fields without rendering them.
  - New entity entries *do not include* references! Any reference changes are always expressed
  with linkage change entries, as described below.

  ### New Relations

  If a subform is explicitly declared, then new linkage between a form and the subforms will
  be expressed via the `:form/add-relations` entry. The value will be a map whose keys are idents of the
  referring object and whose values are a single ident (in the to-one case) or vectors of the idents (in the to-many
  case) of the new targets. This is a delta. This is
  not meant to be interpreted as all of them, just the ones that were added since the form was considered
  clean.

  Examples:

  Two different to-one relationship additions:

  ```
  {:form/add-relations { [:thing tempid-1] {:thing/child [:thing tempid-2] }
                  [:thing tempid-2] {:thing/parent [:thing tempid-1] }}}
  ```

  A to-many parent-child relationship with two new children:

  ```
  {:form/add-relations { [:people/by-id 1] {:person/number [[:phone/by-id 2] [:phone/by-id 3]] }}}
  ```

  ### Removed Relations

  If a subform is explicitly declared, then removal of linkage between a form and the subforms will
  be expressed via the `:form/remove-relations` entry. The value will be a map whose keys are idents of the
  referring object and whose values just like in new linkage. This is also a delta.

  Examples:

  Removal of a to-one relation:

  ```
  {:form/remove-relations { [:thing 1] {:thing/child [:thing 2] }}}
  ```

  Removal of a single child in a to-many relation:

  ```
  {:form/remove-relations { [:people/by-id 1] {:person/number [[:phone/by-id 3]] }}}
  ```

  # Updating a forms-based Entity From the Server

  Since your app state is normalized, any reads of an entity will end up being merged over top of
  the entity you already have. This means that your active form fields on such an entity would
  update.

  There are some caveats to doing this, since the *remembered* state of your form will now be out
  of sync with what you read (or pushed) from the server.

  Typically what you'll want to do when (re)reading an entity that is being actively used on a form is:

  1. Issue a Fulcro load for that entity. The incoming state will cause the UI of the form to update
  (since you're always editing/rendering active state of the entity). Unfortunately, the pristine state
  of the form now thinks the newly loaded entity is *dirty*!
  2. Include a post mutation, which should:
  - `dissoc` the form state via `(update-in app-state form-ident dissoc f/form-key)`
  - Run `build-form` on the form
  - Optionally use the `validate-fields` or `validate-forms` function to update the validation markers.

  A non-remote (local-only) commit-to-entity (still as a post-mutation) could also be used to accomplish (2).
  ")
