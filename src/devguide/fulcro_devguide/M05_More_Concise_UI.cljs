(ns fulcro-devguide.M05-More-Concise-UI
  (:require
    [devcards.core :as dc :include-macros true]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.core :as fc]
    [cljs.spec.alpha :as s]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defui defsc]]
    [fulcro.ui.forms :as f]
    [fulcro-css.css :as css]))

(defsc Job
  "A job component"
  [this {:keys [db/id job/name]}]
  {:ident         (fn [] [:JOB/by-id id])
   :query         (fn [] [:db/id :job/name])
   :initial-state (fn [{:keys [id name] :as params}] {:db/id id :job/name name})}
  (dom/span nil name))

(def ui-job (prim/factory Job {:keyfn :db/id}))

(defsc Person
  "A person component"
  [this {:keys [db/id person/name person/job person/prior-jobs]} _ {:keys [namecls]} children]
  {:ident         (fn [] [:PERSON/by-id id])
   :css           (fn [] [[:.namecls {:color :blue}]])
   :query         (fn [] [:db/id :person/name
                          {:person/job (prim/get-query Job)}
                          {:person/prior-jobs (prim/get-query Job)}])
   :initial-state (fn [{:keys [id name job jobs]}]
                    (cond-> {:db/id       id
                             :person/name name}
                      job (assoc :person/job (prim/get-initial-state Job job))
                      jobs (assoc :person/prior-jobs (mapv #(prim/get-initial-state Job %) jobs))))}
  (dom/div nil
    (dom/span #js {:className namecls} name)
    (dom/ul nil
      (when-not (empty? job)
        (dom/li nil "Current Job: " (ui-job job)))
      (when-not (empty? prior-jobs)
        (dom/li nil "Prior Jobs: "
          (dom/ul nil
            (map (fn [j] (dom/li #js {:key (:db/id j)} (ui-job j))) prior-jobs)))))))

(comment
  (macroexpand-1 '(defsc Person
                    "A person component"
                    [this {:keys [db/id person/name person/job person/prior-jobs]} _ {:keys [namecls]} children]
                    {:ident         (fn [] [:PERSON/by-id id])
                     :css           (fn [] [[:.namecls {:color :blue}]])
                     :query         (fn [] [:db/id :person/name
                                            {:person/job (prim/get-query Job)}
                                            {:person/prior-jobs (prim/get-query Job)}])
                     :initial-state (fn [{:keys [id name job jobs]}]
                                      (cond-> {:db/id       id
                                               :person/name name}
                                        job (assoc :person/job (prim/get-initial-state Job job))
                                        jobs (assoc :person/prior-jobs (mapv #(prim/get-initial-state Job %) jobs))))}
                    (dom/div nil
                      (dom/span #js {:className namecls} name)
                      (dom/ul nil
                        (when-not (empty? job)
                          (dom/li nil "Current Job: " (ui-job job)))
                        (when-not (empty? prior-jobs)
                          (dom/li nil "Prior Jobs: "
                            (dom/ul nil
                              (map (fn [j] (dom/li #js {:key (:db/id j)} (ui-job j))) prior-jobs)))))))))


(fulcro.client.primitives/defui GeneratedPerson
  static fulcro-css.css/CSS
  (local-rules [this] [[:.namecls {:color :blue}]])
  (include-children [_] [])
  static fulcro.client.primitives/InitialAppState
  (initial-state
    [this {:keys [id name job jobs]}]
    (cond-> {:db/id id, :person/name name}
      job (assoc :person/job (prim/get-initial-state Job job))
      jobs (assoc :person/prior-jobs
                  (mapv
                    (fn* [p1__191012#] (prim/get-initial-state Job p1__191012#))
                    jobs))))
  static fulcro.client.primitives/Ident
  (ident [this {:keys [db/id person/name person/job person/prior-jobs]}]
    [:PERSON/by-id id])
  static fulcro.client.primitives/IQuery
  (query [this] [:db/id :person/name {:person/job (prim/get-query Job)} {:person/prior-jobs (prim/get-query Job)}])
  Object
  (render
    [this]
    (clojure.core/let
      [{:keys [db/id person/name person/job person/prior-jobs]} (fulcro.client.primitives/props this)
       _        (fulcro.client.primitives/get-computed this)
       {:keys [namecls]} (fulcro-css.css/get-classnames Person)
       children (fulcro.client.primitives/children this)]
      (dom/div nil
        (dom/span #js {:className namecls} name)
        (dom/ul nil
          (when-not (empty? job) (dom/li nil "Current Job: " (ui-job job)))
          (when-not (empty? prior-jobs)
            (dom/li nil
              "Prior Jobs: "
              (dom/ul nil
                (map (fn [j] (dom/li #js {:key (:db/id j)} (ui-job j))) prior-jobs)))))))))

(def ui-person (prim/factory Person {:keyfn :db/id}))

(defsc Root
  [this {:keys [people ui/react-key]} _ _]
  {:query         (fn [] [:ui/react-key {:people (prim/get-query Person)}])
   :css           (fn [] [[:.thing {:color "gray"}]])
   :css-include   [Person]
   ; protocols is for anything "extra" you need that you could normally list under defui
   :protocols     [Object
                   (componentDidMount [this]
                     ; make sure the CSS is on the document.
                     (css/upsert-css "the-css" Root))]
   ; we know :people is a child of class Person, so initial values on the :people key will automatically get run
   ; through `(get-initial-state Person _)`. Person, in turn, will find the job and jobs parameter maps. See Person.
   :initial-state (fn [params]
                    {:people (mapv #(prim/get-initial-state Person %)
                               [{:id 1 :name "Tony" :job {:id 1 :name "Consultant"}}
                                {:id 2 :name "Sam" :jobs [{:id 2 :name "Meat Packer"} {:id 4 :name "Butcher"}]}
                                {:id 3 :name "Sally"}])})}
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
  "

  ## Lambda vs. Template

  The core options (:query, :ident, :initial-state, :css, and :css-include) of `defsc` support both a lambda and a template form.
  The template form is shorter and enables some sanity checks; however, it is not expressive enough to cover all
  possible cases. The lambda form is slightly more verbose, but enables full flexibility at the expense of the sanity checks.

  IMPORTANT NOTE: In lambda mode the pattern for the lambda requires that you use `this` and `props` from the
  `defsc` argument list. So, for example, `(fn [] [:x])` is valid for query (`this` is added by the macro), and
  `(fn [] [:table/by-id db])` is valid for `ident` (this and the props destructuring are copied from the `defsc` arg list.

  ## Ident Generation

  If you include `:ident`, it can take two forms: a *template* or *lambda*.

  ### Template Idents

  A template ident is just a vector that patterns what goes in the ident. The first element is *always* literal,
  and the second is the *name* of the property to pull from props to get the ID.

  This is the most common case, and *if* you use the template mechanism you get some added sanity checks:
  it won't compile if your ID key isn't in your query, eliminating some possible frustration.

  ### Lambda Idents

  Template idents are great for the common case, but they don't work if you have a single instance ever (i.e. you
  want a literal second element), and they won't work at all for union queries. They also do *not* support embedded
  code. Therefore, if you want a more advanced ident, you'll need to spell out the code.

  `defsc` at least eliminates some of the boilerplate:

  ```
  (defsc UnionComponent [this {:keys [db/id component/type]}]
    {:ident (fn [] [type id])}
    ...)
  ```

  expands to:

  ```
  (defui UnionComponent
    static prim/Ident
    (ident [this {:keys [db/id component/type]}] [type id])
    ...)
  ```

  The first two parameters of `defsc` (this and props) are copied into ident and your render, so that you don't have
  to say (or destructure them) again!

  ## Query

  `defsc` also allows you to specify the query as a template or lambda.

  ### Template Query

  The template form is *strongly* recommended
  for most cases, because without it many of the sanity checks won't work.

  In template mode, the following sanity checks are enabled:

  - The props destructuring
  - The ident's id name is in the query (if ident is in template mode)
  - The initial app state only contains things that are queried for (if it is in template mode as well)

  ### Lambda Query

  This mode is necessary if you use more complex queries. The template mode currently *does not support* link
  queries (joins on an ident with `_` as the id) or union queries.

  To use this mode, specify your query as `(fn [] [:x])`. In lambda mode, `this` comes from the argument list
  of `defsc`.

  ## Initial State

  As with query and ident, :initial-state supports a template and lambda form. In this case the template form
  is kind of magical and complex, because it tries to sanity check your initial state.

  ### Initial State Template Mode

  In template mode the `defsc` macro converts incoming parameters (which must use simple keywords) into `:param/nm`
  keys. So, if `{:x 1}` were passed as params to `(get-initial-state Job {:x 1})` then the template
  initial state in `Job` would use `:param/x` as a stand-in for that parameter.

  It is even more powerful that that, because it analyzes your query and can deal with to-one and to-many
  join initialization as well!

  In the code samples above we're passing parameters
  to the Root component and having them take effect all the way down to jobs. The rules are pretty simple:

  - If you're initializing a simple prop value, then you may use any value, a `param` namespaced keyword, a map containing
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
