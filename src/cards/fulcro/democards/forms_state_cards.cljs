(ns fulcro.democards.forms-state-cards
  (:require [devcards.core :refer-macros [defcard-doc]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.ui.elements :as ele]
            [fulcro.server :as server]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.ui.form-state :as f]
            [fulcro.ui.bootstrap3 :as bs]
            [fulcro-css.css :as css]
            [fulcro.client.primitives :as prim :refer [defui defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.ui.form-state :as fs]
            [clojure.spec.alpha :as s]
            [garden.core :as g]))

(server/defmutation submit-phone [params]
  (action [env]
    (js/console.log :server-received-form-delta params)))

(defcard-doc
  "# Fulcro Forms State Management

  This document uses the aliases:

  ```
  (ns your-namespace
    (:require [fulcro.client :as fc]
              [fulcro.client.primitives :as prim]
              [fulcro.ui.form-state :as f]))
  ```

  The forms state management is a simplified version of the original forms support. As its name indicates, it is
  primarily concerned with state management (saving, generating a delta, validating, detecting if it is dirty, etc.).
  It has no opinions or support for form rendering, and validation is done through clojure spec.

  This brings a few advantages over the other forms support:

  - It is less magical: it simply manages a pristine copy of your form's state, and gives you clean method
  of interacting with it.
  - The state management support makes it easier for you to build your own form rendering, or even reusable form
  components.
  - It includes pluggable support for validation, and includes Clojure spec support.

  The extra data that the form state management uses is placed in a normalized database entry in your app.
  This configuration data is normalized using the data from your entity's ident.

  ## Setting Up a Component for Form State Management

  Here is a checklist for setting up a component for use with state management:

  * Implement the protocol: fulcro.ui.form-state/IFormFields (use :form-fields on defsc).
  * Include fulcro.ui.form-state/form-config-join in your component's query.
  ** Include fields that are local props on the entity.
  ** Include keys for components that are joined in as subforms.
  * Add form configuration to the entity(s) with `add-form-config` or `add-form-config*`.

  ```
  (defsc MyForm [this props]
    {:query [:id :name f/form-config-join]
     :form-fields #{:name}
     :ident [:form/by-id :id]}
    ...)
  ```

  or with `defui`:

  ```
  (defui MyForm
    static prim/IQuery
    (query [this] [:id :name f/form-config-join])
    static prim/Ident
    (ident [this props] [:form/by-id (:id props)])
    static f/IFormFields
    (form-fields [this] #{:name})
    ...)
  ```

  ## Basic Operation

  The basic operation is for the state management to make a copy of your form fields, and keep track of which ones
  are complete (have been interacted with by the user).

  The form configuration carries along:

  * A copy of the pristine state of the form (db entity)
  * A list of the editable fields (all other fields are ignored)
  * A list of the keys in the query that are links to entities that should be considered sub-forms
  * Which fields are \"complete\". That is: the user has interacted with them, or you've marked them as such.
  ** Fields are not valid or invalid until they are complete (both return false).
  ** As soon as a field is marked complete, validation queries will start returning valid/invalid

  ## The Entity Query

  You build your UI component as usual, but add an additional join to
  your query:

  ```
  [:db/id ::my-form-prop {::f/config (prim/get-query f/FormConfig)}]
  ```

  there is a var `f/form-config-join` that has the form config join already in it, so you can simply include that
  instead:

  ```
  (defui MyForm
    static prim/IQuery
    (query [this] [:db/id ::my-form-prop f/form-config-join])
    ...
  ```

  ## The Form Configuration

  You must add the form configuration to each entity that you wish to use as a form. This
  is no different that any other state mutation you've ever done, and there are some helpers to make the correct
  thing for you.

  Typically forms work against something you've loaded from the server (or something you're creating in response to a
  user request). In either case: it won't be initial application state, but instead will be something that you've dynamically
  loaded or created.

  * f/add-form-config : Recursively adds form configuration to a *denormalized tree* (e.g. just arrived from the server,
  or was created by some construction functions).
  * f/add-form-config* : Recursively adds form configuration to *normalized state* (e.g. the entities are already in
  your state database, but are not yet working as forms). Can be easily used from within mutations.

  Neither of these will update an entity's config (unless you dissoc the ::f/config key first), so
  they are safe to call if you're unsure that the form state is set up.

  ## Validity

  Form fields start out as \"incomplete\". This allows you to decide when to show error messages against fields.
  The forms state support comes with Clojure Spec-based validation, but there it is also possible to provide
  a custom validation mechanism very easily.

  ### Validation with Spec

  The following functions can be used to validate your form using Clojure specs on the propery keys of the fields:

  * `(get-spec-validity form)` : Status of entire (recursive) form. Returns :valid, :invalid, or :unchecked.
  * `(get-spec-validity form field)` : Status of the given form field. Returns :valid, :invalid, or :unchecked. Not recursive. You
  must supply the (sub)form that contains the given field.
  * `(valid-spec? form)` : Returns true if and only if the entire form is valid (nothing unchecked).
  * `(valid-spec? form field)` : Returns true if and only if the specific field is valid (not unchecked).
  * `(invalid-spec? form)` : Returns true if *any* fields of the form is invalid AND nothing is unchecked. A form is not
  invalid until all fields are either valid or invalid.
  * `(invalid-spec? form field)` : Returns true if the field is invalid (not unchecked).
  * `(checked? form)` : Returns true if everything on the form is either valid or invalid.
  * `(checked? form field)` : Returns true if the given field is either valid or invalid.

  ### Custom Validator

  A custom validator can be created with a simple function that you supply. The function will receive the entire
  form and a field key (relative to the (sub)form). It returns true or false. For example, a form for taking
  user sign-up information might use a validation function like this:

  ```
  (defn new-user-field-valid? [new-user-form field]
     (let [v (get new-user-form field)
           pw (get new-user-form :password)]
       (case field
         :username (seq v) ; required
         :password (min-length v 8)
         :password-2 (= v pw))))  ; passwords match
  ```

  You'd then create a validator like so:

  ```
  (def new-user-validator (f/make-validator new-user-field-valid?))
  ```

  which gives you a function that can be called on the entire form or a field:

  ```
  (defsc [this {:keys [username password password-2] :as form}]
     { ... }
     (dom/div nil
        (dom/input #js {:value username ...})
        (when (= :invalid (new-user-validator form :username))
           (dom/span nil \"Username Required!\"))
        ...
        (dom/button #js {:disabled (not= :valid (new-user-validator form))} \"Submit\")
        ...
  ```

  ")

(declare Root PhoneForm)

(defn render-field [component field renderer]
  (let [form         (prim/props component)
        entity-ident (prim/get-ident component form)
        id           (str (first entity-ident) "-" (second entity-ident))
        is-dirty?    (f/dirty? form field)
        clean?       (not is-dirty?)
        validity     (f/get-spec-validity form field)
        is-invalid?  (= :invalid validity)
        value        (get form field "")]
    (renderer {:dirty?   is-dirty?
               :ident    entity-ident
               :id       id
               :clean?   clean?
               :validity validity
               :invalid? is-invalid?
               :value    value})))

(defn input-with-label
  "A non-library helper function, written by you to help lay out your form."
  ([component field field-label validation-string input-element]
   (render-field component field
     (fn [{:keys [invalid? id dirty?]}]
       (bs/labeled-input {:error           (when invalid? validation-string)
                          :id              id
                          :warning         (when dirty? "(unsaved)")
                          :input-generator input-element} field-label))))
  ([component field field-label validation-string]
   (render-field component field
     (fn [{:keys [invalid? id dirty? value invalid ident]}]
       (bs/labeled-input {:value    value
                          :id       id
                          :error    (when invalid? validation-string)
                          :warning  (when dirty? "(unsaved)")
                          :onBlur   #(prim/transact! component `[(f/mark-complete! {:entity-ident ~ident
                                                                                    :field        ~field})])
                          :onChange #(m/set-string! component field :event %)} field-label)))))

(s/def ::phone-number #(re-matches #"\(?[0-9]{3}[-.)]? *[0-9]{3}-?[0-9]{4}" %))

(defmutation abort-phone-edit [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state (fn [s]
                   (-> s
                     ; stop editing
                     (dissoc :root/phone)
                     ; revert to the pristine state
                     (f/pristine->entity* [:phone/by-id id])))))
  (refresh [env] [:root/phone]))

(defmutation submit-phone [{:keys [id delta]}]
  (action [{:keys [state]}]
    (js/console.log delta)
    (swap! state (fn [s]
                   (-> s
                     ; stop editing
                     (dissoc :root/phone)
                     ; update the pristine state
                     (f/entity->pristine* [:phone/by-id id])))))
  (remote [env] true)
  (refresh [env] [:root/phone [:phone/by-id id]]))

(defsc PhoneForm [this {:keys [:db/id ::phone-type ::phone-number] :as props}]
  {:query       [:db/id ::phone-type ::phone-number
                 {(bs/dropdown-ident :phone-type) (prim/get-query bs/Dropdown)} ;reusable dropdown, queried directly from table
                 f/form-config-join]
   :form-fields #{::phone-number ::phone-type}
   :ident       [:phone/by-id :db/id]
   :css         [[:.modified {:color :red}]]}
  (let [{:keys [hidden]} (css/get-classnames Root)
        dropdown (get props (bs/dropdown-ident :phone-type))]
    (dom/div #js {:className "form"}
      (input-with-label this ::phone-number "Phone:" "10-digit phone number is required.")
      (input-with-label this ::phone-type "Type:" ""
        (fn [attrs]
          (bs/ui-dropdown dropdown
            :stateful? true
            :value phone-type
            :onSelect (fn [v]
                        (m/set-value! this ::phone-type v)))))
      (bs/button {:onClick #(prim/transact! this `[(abort-phone-edit {:id ~id})])} "Cancel")
      (bs/button {:disabled (or (not (f/checked? props)) (f/invalid-spec? props))
                  :onClick  #(prim/transact! this `[(submit-phone {:id ~id :delta ~(f/dirty-fields props true)})])} "Commit!"))))

(def ui-phone-form (prim/factory PhoneForm {:keyfn :db/id}))

(defsc PhoneNumber [this {:keys [:db/id ::phone-type ::phone-number]} {:keys [onSelect]}]
  {:query         [:db/id ::phone-number ::phone-type]
   :initial-state {:db/id :param/id ::phone-number :param/number ::phone-type :param/type}
   :ident         [:phone/by-id :db/id]}
  (dom/li nil
    (dom/a #js {:onClick (fn [] (onSelect id))}
      (str phone-number " (" (phone-type {:home "Home" :work "Work" nil "Unknown"}) ")"))))

(def ui-phone-number (prim/factory PhoneNumber {:keyfn :db/id}))

(defsc PhoneBook [this {:keys [:db/id ::phone-numbers]} {:keys [onSelect]}]
  {:query         [:db/id {::phone-numbers (prim/get-query PhoneNumber)}]
   :initial-state {:db/id          :main-phone-book
                   ::phone-numbers [{:id 1 :number "541-555-1212" :type :home}
                                    {:id 2 :number "541-555-5533" :type :work}]}
   :ident         [:phonebook/by-id :db/id]}
  (dom/div nil
    (dom/h4 nil "Phone Book (click a number to edit)")
    (dom/ul nil
      (map (fn [n] (ui-phone-number (prim/computed n {:onSelect onSelect}))) phone-numbers))))

(def ui-phone-book (prim/factory PhoneBook {:keyfn :db/id}))

(defn style-element
  "Returns a React Style element with the (recursive) CSS of the given component. Useful for directly embedding in your UI VDOM."
  [component]
  (dom/style (clj->js {:dangerouslySetInnerHTML {:__html (g/css (css/get-css component))}})))

(defmutation edit-phone-number [{:keys [id]}]
  (action [{:keys [state]}]
    (let [phone-type (get-in @state [:phone/by-id id ::phone-type])]
      (swap! state (fn [s]
                     (-> s
                       ; make sure the form config is with the entity
                       (f/add-form-config* PhoneForm [:phone/by-id id])
                       ; since we're editing an existing thing, we should start it out complete (validations apply)
                       (f/mark-complete* [:phone/by-id id])
                       (bs/set-dropdown-item-active* :phone-type phone-type)
                       ; tell the root UI that we're editing a phone number by linking it in
                       (assoc :root/phone [:phone/by-id id])))))))

(defsc Root [this {:keys [:ui/react-key :root/phone :root/phonebook]}]
  {:query         [:ui/react-key
                   {:root/dropdowns (prim/get-query bs/Dropdown)}
                   {:root/phonebook (prim/get-query PhoneBook)}
                   {:root/phone (prim/get-query PhoneForm)}]
   :css           [[:.hidden {:display "none"}]]
   :css-include   [PhoneForm]
   :initial-state (fn [params]
                    {:root/dropdowns [(bs/dropdown :phone-type "Type" [(bs/dropdown-item :work "Work")
                                                                       (bs/dropdown-item :home "Home")])]
                     :root/phonebook (prim/get-initial-state PhoneBook {})})}
  (ele/ui-iframe {:frameBorder 0 :width 500 :height 200}
    (dom/div #js {:key react-key}
      (dom/link #js {:rel "stylesheet" :href "bootstrap-3.3.7/css/bootstrap.min.css"})
      (style-element Root)
      (if (contains? phone ::phone-number)
        (ui-phone-form phone)
        (ui-phone-book (prim/computed phonebook {:onSelect (fn [id] (prim/transact! this `[(edit-phone-number {:id ~id})]))}))))))

(def mock-server (server/new-server-emulator))

(defcard-fulcro form-state-card-1
  Root
  {}
  {:inspect-data true
   :fulcro       {:networking {:remote mock-server}}})
