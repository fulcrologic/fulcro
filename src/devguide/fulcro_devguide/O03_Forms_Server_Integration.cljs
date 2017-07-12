(ns fulcro-devguide.O03-Forms-Server-Integration
  (:require
    [clojure.string :as str]
    [com.stuartsierra.component :as component]
    [devcards.core :as dc :refer-macros [defcard defcard-doc]]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]
    [fulcro.client.cards :refer [fulcro-app]]
    [fulcro.client.core :as uc]
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

  A form submission is done via the `commit-to-entity` function/mutation with the inclusion
  of a `:remote` flag. The function version `commit-to-entity!` is a simple wrapper of an
  Om `transact!` that invokes the Om `f/commit-to-entity` mutation (where `f` is the forms namespace).
  The former is a convenience, and the latter is more useful when you want to compose commit with
  other transaction actions (such as navigation away from the form).

  The wrapper function must be called using the `this` of the component that is the top-level of the form,
  and the mutation must be called with the `props` of the top-level form.

  Both default to a local commit (where you can deal with persistence in some other way), but if you supply
  them with a remote argument they will send the *changes* to the data in the form to the server. Of course,
  new entities will be completely sent.

  ## What Your Server Must Do

  On the server, you must provide a mutation handler for the `f/commit-to-entity` symbol (where `f` is the
  forms namespace). If you're using multimethods on the server, this might look like:

  ```
  (ns amazing-server.mutations
    (:require
      [om.next :as om]
      [fulcro.ui.forms :as f]))

  (defmulti my-mutate om/dispatch)

  ;; NOTE: the syntax quote will honor the `f` aliasing in the ns.
  (defmethod my-mutate `f/commit-to-entity [env k params]
     {:action (fn [] ...)})
  ```

  The complete form delta will be in the incoming `params`. The description of the entries in `params`
  is below.

  ## Incoming Server Parameters for `commit-to-entity`

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

  When a form (and/or subforms) is submitted that has a primary ID whose value is an Om tempid then
  the incoming commit parameters will include the `:form/new-entities` key. The value of this entry is just like
  that of `:form/updates`, but the ID in the ident will be an Om tempid (which you must send remaps
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

  1. Issue an fulcro load for that entity. The incoming state will cause the UI of the form to update
  (since you're always editing/rendering active state of the entity). Unfortunately, the pristine state
  of the form now thinks the newly loaded entity is *dirty*!
  2. Include a post mutation, which should:
      - `dissoc` the form state via `(update-in app-state form-ident dissoc f/form-key)`
      - Run `build-form` on the form
      - Optionally use the `validate-fields` or `validate-forms` function to update the validation markers.

  A non-remote (local-only) commit-to-entity (still as a post-mutation) could also be used to accomplish (2).
  ")
