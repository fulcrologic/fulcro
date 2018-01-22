(ns fulcro.democards.forms-state-cards
  (:require [devcards.core :refer-macros [defcard-doc]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.ui.elements :as ele]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.ui.form-state :as f]
            [fulcro.ui.bootstrap3 :as bs]
            [fulcro-css.css :as css]
            [fulcro.client.primitives :as prim :refer [defui defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.ui.form-state :as fs]
            [clojure.spec.alpha :as s]
            [garden.core :as g]))

(defcard-doc
  "# Fulcro Forms State Management

  This document uses the aliases:

  ```
  (ns your-namespace
    (:require [fulcro.client :as fc]
              [fulcro.client.primitives :as prim]
              [fulcro.ui.form-state :as f]))
  ```

  The Forms State Management is a simplified and less intrusive set of forms helpers. Fulcro 1.0 forms are still available,
  but you may want to try the state management first. It has a few advantages over the older forms support:

  - It leverages Clojure spec for validation
  - It is less magical: it simply manages a pristine copy of your form's state, and gives you clean method
  of interacting with it
  - The state management support makes it easier for you to build your own form rendering, or even reusable form
  components.

  This means the forms support is largely just doing some data manipulation for you so you don't have to write that part
  of it.

  The extra data the the form state management uses is placed in a normalized database entry in your app. This
  prevents the clutter you experienced in 1.0 forms. This does require that you give each form a unique ID, but since
  forms are typically initialized on the fly it will be easy to do so (you can base the ID off of the entity being
  edited).

  ## Basic Operation

  The basic operation is for the state management to make a copy of your form fields, and keep track of which ones
  are complete (have been interacted with by the user). The system has the following elements:

  - A copy of the pristine state of the form (db entity)
  - Editable fields on the db entity (all other fields are ignored)
  - Which fields are \"complete\". That is: the user has interacted with them, or you've marked them as such.
     - Fields are not valid or invalid until they are complete (both return false)
     - As soon as a field is marked complete, validation queries will start returning valid/invalid
  - If any of the keys in the entity are links to entities that should be linked as sub-forms

  ## The Entity Query

  You build your UI component as usual, but add an additional join to
  your query:

  ```
  [:db/id ::my-form-prop {::f/form-config (prim/get-query f/FormConfig)}]
  ```

  there is a function `f/get-form-query` that will return that join to you so you can just write:

  ```
  (defui MyForm
    static prim/IQuery
    (query [this] [:db/id ::my-form-prop (f/get-form-query)])
    ...
  ```

  ## The Form Configuration

  You must add the form configuration to each entity that you wish to use as a form (as a normalized bit of state). This
  is no different that any other state mutation you've ever done, and there are some helpers to make the correct
  thing for you.

  Typically forms work against something you've loaded from the server (or something you're creating in response to a
  user request). In either case: it won't be initial application state. It will be something that you've dynamically
  loaded or created.

  The `f/init-form` function takes your entity's state and some form configuration parameters, and returns your entity
  as a *denormalized* thing. It can be used in InitialAppState. This is rarely what you need, but is good for demos.

  The `f/init-form*` function is for use within mutations. This is usually what you want. It can initialize the form
  configuration for one entity at a time. Nested forms must use it on each entity in a form set.

  Neither of these will re-init an entity's form config (unless you dissoc the ::f/form-config key first), so
  they are safe to call if you're unsure that the form state is set up.


  ")

(declare Root PhoneForm)

(defn render-field [component field renderer]
  (let [form         (prim/props component)
        entity-ident (prim/get-ident component form)
        id           (str (first entity-ident) "-" (second entity-ident))
        is-dirty?    (f/dirty? form field)
        clean?       (not is-dirty?)
        validity     (f/validity form field)
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
                          :onBlur   #(prim/transact! component `[(f/validate! {:entity-ident ~ident
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
                     (f/reset-form* [:phone/by-id id])))))
  (refresh [env] [:root/phone]))

(defmutation submit-phone [{:keys [id delta]}]
  (action [{:keys [state]}]
    (js/console.log delta)
    (swap! state (fn [s]
                   (-> s
                     ; stop editing
                     (dissoc :root/phone)
                     ; update the pristine state
                     (f/commit-form* [:phone/by-id id])))))
  (remote [env] true)
  (refresh [env] [:root/phone [:phone/by-id id]]))

(defmutation init-form! [{:keys [ident config]}]
  (action [{:keys [state]}]
    (swap! state f/init-form* ident config)))

(defsc PhoneForm [this {:keys [:db/id ::phone-type ::phone-number] :as props}]
  {:query [:db/id ::phone-type ::phone-number
           {(bs/dropdown-ident :phone-type) (prim/get-query bs/Dropdown)} ;reusable dropdown, queried directly from table
           (f/get-form-query)]
   :ident [:phone/by-id :db/id]
   :css   [[:.modified {:color :red}]]}
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
      (bs/button {:disabled (or (not (f/checked? props)) (f/invalid? props))
                  :onClick  #(prim/transact! this `[(submit-phone {:id ~id :delta ~(f/dirty-fields props true)})])} "Commit!"))))

#_(prim/transact! this `[(init-form! {:config {::f/id "phone-form" ::f/fields #{::phone-number ::phone-type}}
                                      :ident  ~(prim/get-ident this (prim/props this))})])

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
    (swap! state (fn [s]
                   (-> s
                     ; make sure the form config is with the entity
                     (f/init-form* [:phone/by-id id] {::f/id     (str "phone-form-" id)
                                                      ::f/fields #{::phone-number ::phone-type}})
                     ; since we're editing an existing thing, we should start it out validated
                     (f/validate* [:phone/by-id id])
                     ; tell the root UI that we're editing a phone number by linking it in
                     (assoc :root/phone [:phone/by-id id]))))))

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

(defcard-fulcro form-state-card-1
  Root
  {}
  {:inspect-data true})
