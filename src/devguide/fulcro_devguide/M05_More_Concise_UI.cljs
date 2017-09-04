(ns fulcro-devguide.M05-More-Concise-UI
  (:require
    [devcards.core :as dc :include-macros true]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.core :as fc :refer [defsc]]
    [cljs.spec.alpha :as s]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]
    [fulcro.ui.forms :as f]))

(defsc Job
  "A job component"
  [this {:keys [db/id job/name]} computed children]
  {:table         :JOB/by-id
   :props         [:db/id :job/name]
   :initial-state {:db/id    :param/id                      ; expects to be called as `(get-initial-state Job {:id i :name n})`
                   :job/name :param/name}}
  (dom/span nil name))

(def ui-job (om/factory Job {:keyfn :db/id}))

(defsc Person
  "A person component"
  [this {:keys [db/id person/name person/job person/prior-jobs]} computed children]
  {:table         :PERSON/by-id
   :props         [:db/id :person/name]
   :children      {:person/job Job :person/prior-jobs Job}
   :initial-state {:db/id             :param/id             ; expects to be called as (get-initial-state Person {:id i :name n :job j :jobs [j1 j2]})
                   :person/name       :param/name
                   :person/job        :param/job            ; job and jobs are known children. Will be resolved by calling (get-initial-state Job j), etc.
                   :person/prior-jobs :param/jobs}}
  (dom/div nil
    name
    (dom/ul nil
      (when job
        (dom/li nil "Current Job: " (ui-job job)))
      (when prior-jobs
        (dom/li nil "Prior Jobs: "
          (dom/ul nil
            (map (fn [j] (dom/li #js {:key (:db/id j)} (ui-job j))) prior-jobs)))))))

(om.next/defui GeneratedPerson
  static fc/InitialAppState
  (initial-state
    [c params]
    (fc/make-state-map
      {:db/id             :param/id,
       :person/name       :param/name,
       :person/job        :param/job,
       :person/prior-jobs :param/jobs}
      {:person/job Job, :person/prior-jobs Job}
      params))
  static om.next/Ident
  (ident [this props] [:PERSON/by-id (:db/id props)])
  static om.next/IQuery
  (query [this] [:db/id :person/name {:person/job (om.next/get-query Job)}
                 {:person/prior-jobs (om.next/get-query Job)}])
  Object
  (render
    [this]
    (clojure.core/let
      [{:keys [db/id person/name person/job person/prior-jobs]} (om/props this)
       computed (om/get-computed this)
       children (om/children this)]
      (dom/div nil name (when job (dom/span nil ", " (ui-job job)))))))

(def ui-person (om/factory Person {:keyfn :db/id}))

(defsc Root
  [this {:keys [people ui/react-key]} _ _]
  {:props         [:ui/react-key]
   ; we know :people is a child of class Person, so initial values on the :people key will automatically get run
   ; through `(get-initial-state Person _)`. Person, in turn, will find the job and jobs parameter maps. See Person.
   :initial-state {:people [{:id 1 :name "Tony" :job {:id 1 :name "Consultant"}}
                            {:id 2 :name "Sam" :jobs [{:id 2 :name "boo"} {:id 4 :name "bah"}]}
                            {:id 3 :name "Sally"}]}
   :children      {:people Person}}
  (dom/div #js {:key react-key}
    (mapv ui-person people)))


(dc/defcard-doc "# The defsc Macro

  Fulcro includes a macro, `defsc`, that can build a sanity-checked component with the most common elements:
  ident (optional), query, render, and initial state (optional). The sanity checking prevents a lot of the most
  common errors when writing a component, and the concise syntax reduces boilerplate to the essential novelty.

  The parameter list can by used to do full Clojure destructuring on the props, computed, and children without
  having to write a separate `let`.

  For example, one could specify `Job` and `Person` like so:"
  (dc/mkdn-pprint-source Job)
  (dc/mkdn-pprint-source Person)
  "And the resulting generated person would look like this (but would have the name `Person`):"
  (dc/mkdn-pprint-source GeneratedPerson)
  "A root component (with no ident, but with query children) might look like this:"
  (dc/mkdn-pprint-source Root)
  "

  ## Initial State

  Notice in the code samples above that the initial state setup is actually quite powerful. We're passing parameters
  to the Root component and having them take effect all the way down to jobs. The rules are pretty simple:

  - If you're initializing a scalar value, you may use a scalar value, a `param` namespaced keyword, a map containing
  param-namespaced keywords as values, or a vector containing param-namespaced keywords as values. In all cases the
  parameters will be substituted by the name of that keyword, as obtained at runtime from the params of the call
  to the initial-state method.
  - If you're initializing a child, the macro already knows the class, and that it should call `get-initial-state` against it. Therefore,
  the value that you use in the initial-state is the parameters to *send* to that child's initial state function. To-one
  and to-many relations are implied by what you pass (a map is to-one, a vector is to-many). Nesting of param-namespaced
  keywords is supported, but realize that the params come from the declaring components initial state parameters.

  ## Form Support

  The form interface is also supported. Just add `:form-fields` as a vector to your options map. That will cause
  it to emit the correct `IForm` implementation, and if initial state is present it will properly wrap it
  in the `build-form` call behind the scenes!.

  ```
  (defsc MyForm
     [this props computed children]
     {:form-fields [(f/id-field :db/id) ...]
      :initial-state {:db/id :param/id ...} ; will automatically get the extra form support initial data
      ...}
     (dom/div nil ...))
  ```

  ## Additional Protocol Support

  The main options map covers all of the common built-in protocols that you would include on `defui`. If you need
  to include additional protocols (or lifecycle React methods) then you can use the `:protocols` option. It takes
  a list of forms that have the same shape as the body of the normal defui. `Object` methods will be properly
  combined with the generated `render`:

  ```
  (defsc MyComponent
     [this props computed children]
     {:protocols (Object
                  (shouldComponentUpdate [this next-props next-state] true)
                  static css/CSS
                  (local-rules [_] [])
                  (include-children [_] []))
      ...}
     (dom/div nil ...))
  ```

  This gives you the full features of `defui`, but you only need the extra protocol additions when you use
  methods and protocols beyond the central ones.

  ## Sanity Checking

  Feel free to edit the components in this source file and try out the sanity checking. For example, try:

  - Mismatching the name of a prop in options with a destructured name in props.
  - Destructuring a prop that isn't in props or children
  - Including initial state for a field that is not listed as a prop or child in options.
  - Using a scalar value for the initial value of a child (instead of a map or vector of maps)
  - Forget to query for the ID field of a component that is stored in a table (ident)

  See the docstring on the macro (or the source of this card file) for more details.")

(defcard-fulcro demo-card Root {} {:inspect-data false})

