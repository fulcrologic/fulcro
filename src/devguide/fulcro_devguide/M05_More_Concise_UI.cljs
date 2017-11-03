(ns fulcro-devguide.M05-More-Concise-UI
  (:require
    [devcards.core :as dc :include-macros true]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.core :as fc :refer [defsc]]
    [cljs.spec.alpha :as s]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defui]]
    [fulcro.ui.forms :as f]
    [fulcro-css.css :as css]))

(defsc Job
  "A job component"
  [this {:keys [db/id job/name]} computed children]
  {:ident         [:JOB/by-id :db/id]
   :query         [:db/id :job/name]
   ; expects to be called as `(get-initial-state Job {:id i :name n})`
   :initial-state {:db/id    :param/id
                   :job/name :param/name}}
  (dom/span nil name))

(def ui-job (prim/factory Job {:keyfn :db/id}))

(defsc Person
  "A person component"
  [this {:keys [db/id person/name person/job person/prior-jobs]} computed children]
  {:ident         [:PERSON/by-id :db/id]
   :css           [[:.namecls {:font-weight :bold}]]
   :query         [:db/id :person/name
                   {:person/job (prim/get-query Job)}
                   {:person/prior-jobs (prim/get-query Job)}]
   ; expects to be called as (get-initial-state Person {:id i :name n :job j :jobs [j1 j2]})
   :initial-state {:db/id             :param/id
                   :person/name       :param/name
                   ; job and jobs are known children. Will be resolved by calling (get-initial-state Job j), etc.
                   :person/job        :param/job
                   :person/prior-jobs :param/jobs}}
  (let [{:keys [namecls]} (css/get-classnames Person)]
    (dom/div nil
      (dom/span #js {:className namecls} name)
      (dom/ul nil
        (when job
          (dom/li nil "Current Job: " (ui-job job)))
        (when prior-jobs
          (dom/li nil "Prior Jobs: "
            (dom/ul nil
              (map (fn [j] (dom/li #js {:key (:db/id j)} (ui-job j))) prior-jobs))))))))

(comment
  (macroexpand-1 '(defsc Person
                    "A person component"
                    [this {:keys [db/id person/name person/job person/prior-jobs]} computed children]
                    {:ident         [:PERSON/by-id :db/id]
                     :css           [[:.namecls {:font-weight :bold}]]
                     :query         [:db/id :person/name
                                     {:person/job (prim/get-query Job)}
                                     {:person/prior-jobs (prim/get-query Job)}]
                     ; expects to be called as (get-initial-state Person {:id i :name n :job j :jobs [j1 j2]})
                     :initial-state {:db/id             :param/id
                                     :person/name       :param/name
                                     ; job and jobs are known children. Will be resolved by calling (get-initial-state Job j), etc.
                                     :person/job        :param/job
                                     :person/prior-jobs :param/jobs}}
                    (let [{:keys [namecls]} (css/get-classnames Person)]
                      (dom/div nil
                        (dom/span #js {:className namecls} name)
                        (dom/ul nil
                          (when job
                            (dom/li nil "Current Job: " (ui-job job)))
                          (when prior-jobs
                            (dom/li nil "Prior Jobs: "
                              (dom/ul nil
                                (map (fn [j] (dom/li #js {:key (:db/id j)} (ui-job j))) prior-jobs))))))))))

(fulcro.client.primitives/defui GeneratedPerson
  static fulcro-css.css/CSS
  (local-rules [_] [[:.namecls {:font-weight :bold}]])
  (include-children [_] [])
  static fulcro.client.core/InitialAppState
  (initial-state [c params]
    (fulcro.client.core/make-state-map
      {:db/id             :param/id,
       :person/name       :param/name,
       :person/job        :param/job,
       :person/prior-jobs :param/jobs}
      #:person{:job Job, :prior-jobs Job}
      params))
  static fulcro.client.primitives/Ident
  (ident [this props] [:PERSON/by-id (:db/id props)])
  static fulcro.client.primitives/IQuery
  (query [this] [:db/id :person/name #:person{:job (prim/get-query Job)} #:person{:prior-jobs (prim/get-query Job)}])
  Object
  (render
    [this]
    (clojure.core/let
      [{:keys [db/id person/name person/job person/prior-jobs]} (fulcro.client.primitives/props this)
       computed (fulcro.client.primitives/get-computed this)
       children (fulcro.client.primitives/children this)]
      (let [{:keys [namecls]} (css/get-classnames Person)]
        (dom/div nil
          (dom/span #js {:className namecls} name)
          (dom/ul nil
            (when job (dom/li nil "Current Job: " (ui-job job)))
            (when prior-jobs
              (dom/li nil "Prior Jobs: "
                (dom/ul nil
                  (map (fn [j] (dom/li #js {:key (:db/id j)} (ui-job j))) prior-jobs))))))))))


(def ui-person (prim/factory Person {:keyfn :db/id}))

(defsc Root
  [this {:keys [people ui/react-key]} _ _]
  {:query         [:ui/react-key {:people (prim/get-query Person)}]
   :css           [[:.thing {:color "gray"}]]               ; define colocated CSS classes
   :css-include   [Person]
   ; protocols is for anything "extra" you need that you could normally list under defui
   :protocols     [Object
                   (componentDidMount [this]
                     ; make sure the CSS is on the document.
                     (css/upsert-css "the-css" Root))]
   ; we know :people is a child of class Person, so initial values on the :people key will automatically get run
   ; through `(get-initial-state Person _)`. Person, in turn, will find the job and jobs parameter maps. See Person.
   :initial-state {:people [{:id 1 :name "Tony" :job {:id 1 :name "Consultant"}}
                            {:id 2 :name "Sam" :jobs [{:id 2 :name "Meat Packer"} {:id 4 :name "Butcher"}]}
                            {:id 3 :name "Sally"}]}}
  (let [{:keys [thing]} (css/get-classnames Root)]          ;localized classname
    (dom/div #js {:key react-key :className thing}
      (mapv ui-person people))))


(dc/defcard-doc "# The defsc Macro

  Fulcro includes a macro, `defsc`, that can build a `defui` that is sanity-checked for the most common elements:
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

  ## Ident Generation

  The (optional) `:ident` parameter should be a vector of two elements. The first is the literal table name, and the
  second is the ID field that will exist in props.

  For example, `:ident [:person/by-id :person/id]` will turn into `prim/Ident (ident [this props] [:person/by-id (:person/id props)])`
  on the resulting component.

  ## Query

  `defsc` uses any valid Om Next Query at the :query option. The query will be validated against the prop destructuring
  to help you make fewer mistakes.

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

  ## CSS Support

  Support for using `fulcro-css` is built-in, *but* you must include the `fulcr-css` library in your project
  dependencies *and* require the `fulcro-css.css` namespace in any file
  that uses this support. The keys in the options map are:

  - `:css` - The items to put in protocol method `css/local-rules`
  - `:css-include` - The items to put in protocol method `css/include-children`

  Both are optional. If you use neither, then your code will not incur a dependency on the fulcro-css library.

  See [Fulcro CSS](https://github.com/fulcrologic/fulcro-css) for more information.

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

  This gives you the full protocol capabilities of `defui`, but you only need the extra protocol additions when you use
  methods and protocols beyond the central ones.

  ## Sanity Checking

  Feel free to edit the components in this source file and try out the sanity checking. For example, try:

  - Mismatching the name of a prop in options with a destructured name in props.
  - Destructuring a prop that isn't in the query
  - Including initial state for a field that is not listed as a prop or child in options.
  - Using a scalar value for the initial value of a child (instead of a map or vector of maps)
  - Forget to query for the ID field of a component that is stored at an ident

  See the docstring on the macro (or the source of this card file) for more details.")

(defcard-fulcro demo-card Root {} {:inspect-data false})
