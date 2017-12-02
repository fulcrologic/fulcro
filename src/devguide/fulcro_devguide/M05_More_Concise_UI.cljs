(ns fulcro-devguide.M05-More-Concise-UI
  (:require
    [devcards.core :as dc :include-macros true]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client :as fc]
    [cljs.spec.alpha :as s]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defui defsc]]
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
  [this {:keys [db/id person/name person/job person/prior-jobs]} _ {:keys [namecls]}]
  {:ident         [:PERSON/by-id :db/id]                    ; or (fn [] [:PERSON/by-id id])
   :css           [[:.namecls {:color :blue}]]              ; or (fn [] [[:.namecls {:color :blue}]]
   :query         [:db/id :person/name                      ; or wrapped in (fn [] ...)
                   {:person/job (prim/get-query Job)}
                   {:person/prior-jobs (prim/get-query Job)}]
   :initial-state {:db/id             :param/id
                   :person/name       :param/name
                   ; job and jobs are known children. Will be resolved by calling (get-initial-state Job j), etc.
                   :person/job        :param/job
                   :person/prior-jobs :param/jobs}
   ;; OR as a lambda
   ;:initial-state (fn [{:keys [id name job jobs]}]
   ;                 (cond-> {:db/id id :person/name name}
   ;                   job (assoc :person/job (prim/get-initial-state Job job))
   ;                   jobs (assoc :person/prior-jobs (mapv #(prim/get-initial-state Job %) jobs))))
   }
  (dom/div nil
    (dom/span #js {:className namecls} name)
    (dom/ul nil
      (when-not (empty? job)
        (dom/li nil "Current Job: " (ui-job job)))
      (when-not (empty? prior-jobs)
        (dom/li nil "Prior Jobs: "
          (dom/ul nil
            (map (fn [j] (dom/li #js {:key (:db/id j)} (ui-job j))) prior-jobs)))))))



(fulcro.client.primitives/defui GeneratedPerson
  static fulcro-css.css/CSS
  (local-rules [_] [[:.namecls {:color :blue}]])
  (include-children [_] [])
  static fulcro.client.primitives/InitialAppState
  (initial-state [c params]
    (fulcro.client.primitives/make-state-map
      {:db/id             :param/id,
       :person/name       :param/name,
       :person/job        :param/job,
       :person/prior-jobs :param/jobs}
      {:person/job Job, :person/prior-jobs Job}
      params))
  static fulcro.client.primitives/Ident
  (ident [this props] [:PERSON/by-id (:db/id props)])
  static fulcro.client.primitives/IQuery
  (query [this] [:db/id :person/name {:person/job (prim/get-query Job)} {:person/prior-jobs (prim/get-query Job)}])
  Object
  (render [this]
    (clojure.core/let
      [{:keys [db/id person/name person/job person/prior-jobs]} (fulcro.client.primitives/props this)
       _ (fulcro.client.primitives/get-computed this)
       {:keys [namecls]} (fulcro-css.css/get-classnames Person)]
      (dom/div nil
        (dom/span #js {:className namecls} name)
        (dom/ul nil
          (when-not (empty? job) (dom/li nil "Current Job: " (ui-job job)))
          (when-not (empty? prior-jobs)
            (dom/li nil "Prior Jobs: "
              (dom/ul nil
                (map
                  (fn [j] (dom/li #js {:key (:db/id j)} (ui-job j)))
                  prior-jobs)))))))))


(def ui-person (prim/factory Person {:keyfn :db/id}))

(defsc Root
  [this {:keys [people ui/react-key]} comp {:keys [thing]}]
  {:query         [:ui/react-key {:people (prim/get-query Person)}]
   :css           [[:.thing {:color "gray"}]]
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
  (dom/div #js {:key react-key :className thing}
    (mapv ui-person people)))

(comment
  (macroexpand-1 '(defsc Root
                    [this {:keys [people ui/react-key]} comp {:keys [thing]}]
                    {:query         [:ui/react-key {:people (prim/get-query Person)}]
                     :css           [[:.thing {:color "gray"}]]
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
                    (dom/div #js {:key react-key :className thing}
                      (mapv ui-person people)))))

(fulcro.client.primitives/defui GeneratedRoot
  static fulcro-css.css/CSS
  (local-rules [_] [[:.thing {:color "gray"}]])
  (include-children [_] [Person])
  static fulcro.client.primitives/InitialAppState
  (initial-state [c params]
    (fulcro.client.primitives/make-state-map
      {:people
       [{:id 1, :name "Tony", :job {:id 1, :name "Consultant"}}
        {:id   2,
         :name "Sam",
         :jobs [{:id 2, :name "Meat Packer"} {:id 4, :name "Butcher"}]}
        {:id 3, :name "Sally"}]}
      {:people Person}
      params))
  static fulcro.client.primitives/IQuery
  (query [this] [:ui/react-key {:people (prim/get-query Person)}])
  Object
  (render [this]
    (clojure.core/let
      [{:keys [people ui/react-key]} (fulcro.client.primitives/props this)
       comp (fulcro.client.primitives/get-computed this)
       {:keys [thing]} (fulcro-css.css/get-classnames Root)]
      (dom/div #js {:key react-key, :className thing}
        (mapv ui-person people))))
  (componentDidMount [this] (css/upsert-css "the-css" Root)))

(defsc DestructuredExample [this
                            {:keys [db/id] :as props}
                            {:keys [onClick] :as computed :or {onClick identity}}
                            {:keys [my-css-class] :as css-name-map}
                            [first-child second-child :as children]]
  {:query         [:db/id]
   :initial-state {:db/id 22}
   :css           [[:.my-css-class {:color :black}]]}
  (dom/div #js {:className my-css-class}
    (str "Component: " id)
    first-child
    second-child))

(dc/defcard-doc "# The defsc Macro

  Fulcro includes a macro, `defsc`, that can build a `defui` that is sanity-checked for the most common elements:
  ident (optional), query, render, and initial state (optional). The sanity checking prevents a lot of the most
  common errors when writing a component, and the concise syntax reduces boilerplate to the essential novelty.
  The name means \"define stateful component\" and is intended to be used with components that have queries (though
  that is not a requirement).

  ## Argument List

  The primary argument list contains the common elements you might need to use in the body of you
  ```
  (defsc [this props computed children]
   { ...options... }
   (dom/div #js {:onClick (:onClick computed)} (:db/id props)))
  ```

  Only the first two parameters are required, so you can even write:

  ```
  (defsc [this props]
   { ...options... }
   (dom/div nil (:db/id props)))
  ```

  It has direct support for the `fulcro-css` library. When you use it with CSS rules, the argument list changes
  slightly:

  ```
  (defsc [this props computed css-name-map children] ; NOTE: children slides over iff `:css` is present in options
   { :css [[:.name {:color :red}]] }
   (dom/div nil ...))
  ```

  and all parameters except the first two continue to be optional. (Side note: As far as `children` sliding over: This was a matter of debate in design. This was chosen over other options
  because `children` are much less frequently used (when using fulcro-css) than CSS.)

  ## Argument Destructuring

  The parameter list fully supports Clojure destructuring on the props, computed, css-name-map, and children without
  having to write a separate `let`:

  "
  (dc/mkdn-pprint-source DestructuredExample)
  "

  ## Lambda vs. Template

  The core options (:query, :ident, :initial-state, :css, and :css-include) of `defsc` support both a lambda and a template form.
  The template form is shorter and enables some sanity checks; however, it is not expressive enough to cover all
  possible cases. The lambda form is slightly more verbose, but enables full flexibility at the expense of the sanity checks.

  IMPORTANT NOTE: In lambda mode use `this` and `props` from the
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
    {:ident (fn [] (union-ident type id))} ; id and type are destructured into the method for you.
    ...)
  ```

  expands to:

  ```
  (defui UnionComponent
    static prim/Ident
    (ident [this {:keys [db/id component/type]}] (union-ident type id))
    ...)
  ```

  The first two parameters of `defsc` (this and props) are copied into ident and your render, so that you don't have
  to say (or destructure them) again!

  ## Query

  `defsc` also allows you to specify the query as a template or lambda.

  ### Template Query

  The template form is *strongly* recommended for most cases, because without it many of the sanity checks won't work.

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

  As with query and ident, :initial-state supports a template and lambda form.

  The template form for initial state is a bit magical, because it tries to sanity check your initial state, but
  also has to support relations through joins. Finally it tries to eliminate typing for you by auto-wrapping nested
  relation initializations in `get-initial-state` for you by deriving the correct class to use from the query. This
  further reduces the chances of error; however, you may find the terse result more difficult to read and instead
  choose to write it yourself. Both ways are supported:

  ### Lambda mode

  This is simple to understand, and all you need to see is a simple example:

  ```
  (defsc Component [this props]
   {:initial-state (fn [params] ...exactly what you'd write in defui...)}
   ...)
  ```

  The only difference is that `this` comes in from the top argument list behind the scenes.

  ### Initial State Template Mode

  In template mode it converts incoming parameters (which must use simple keywords) into `:param/X`
  keys. So,

  ```
  (defsc Person [this props]
    {:initial-state {:db/id :param/id}}
    ...)
  ```

  means

  ```
  (defui Person
    static prim/InitialAppState
    (initial-state [this params] {:db/id (:params id)}}
    ...)
  ```

  It is even more powerful that that, because it analyzes your query and can deal with to-one and to-many
  join initialization as well:

  ```
  (defsc Person [this props]
    {:query [{:person/job (prim/get-query Job)}]
    :initial-state {:person/job {:job/name \"Welder\"}}
    ...)
  ```

  means (in simplified terms):

  ```
  (defui Person
    static prim/InitialAppState
    (initial-state [this params] {:person/job (prim/get-initial-state Job {:job/name \"Welder\"})})
    ...)
  ```

  Notice the magic there. `Job` was pulled from the query by looking for joins.

  To-many relations are also auto-derived:

  ```
  (defsc Person [this props]
    {:query [{:person/prior-jobs (prim/get-query Job)}]
    :initial-state {:person/prior-jobs [{:job/name \"Welder\"} {:job/name \"Cashier\"]}
    ...)
  ```

  means (in simplified terms):

  ```
  (defui Person
    static prim/InitialAppState
    (initial-state [this params]
      {:person/prior-jobs [(prim/get-initial-state Job {:job/name \"Welder\"})
                           (prim/get-initial-state Job {:job/name \"Cashier\"})]})
    ...)
  ```

  The internal steps for processing this template are:

  1. Replace all uses of `:param/nm` with `(get params :nm)`
  2. The query is analyzed for joins on keywords (ident joins are not supported).
  3. If a key in the initial state matches up with a join, then the value in initial state *must* be a map
  or a vector. In that case `(get-initial-state JoinClass p)` will be called for each map (to-one) or mapped across
  the vector (to-many).

  *REMEMBER*: the value that you use in the initial-state for children is the *parameter map* to use against that child's
  initial state function. To-one and to-many relations are implied by what you pass (a map is to-one, a vector is to-many).

  Step (1) means that nesting of param-namespaced
  keywords is supported, but realize that the params come from the *declaring* component'ss initial state parameters, they
  are substituted before being passed to the child.

  ## CSS Support

  Support for using `fulcrologic/fulcro-css` is built-in, *but* it is a dynamic dependency, so to use it you must
  include the `fulcrologic/fulcro-css` library in your project
  dependencies *and* require the `fulcro-css.css` namespace in any file
  that uses this support.

  The keys in the options map are:

  - `:css` - The items to put in protocol method `css/local-rules`
  - `:css-include` - The items to put in protocol method `css/include-children`

  Both are optional. If you use neither, then your code will not incur a dependency on the fulcro-css library.

  See [Fulcro CSS](https://github.com/fulcrologic/fulcro-css) for more information.

  ## Additional Protocol Support

  The main options covers all of the common built-in protocols that you would include on `defui`. If you need
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

  The sanity checking mentioned in the earlier sections causes compile errors. The errors are intended to be
  self-explanatory. They will catch common mistakes (like forgetting to query for data that you're pulling out
  of props, or misspelling a property).

  Feel free to edit the components in this source file and try out the sanity checking. For example, try:

  - Mismatching the name of a prop in options with a destructured name in props.
  - Destructuring a prop that isn't in the query
  - Including initial state for a field that is not listed as a prop or child in options.
  - Using a scalar value for the initial value of a child (instead of a map or vector of maps)
  - Forget to query for the ID field of a component that is stored at an ident

  In some cases the sanity checking is more aggressive that you might desire. To get around it simply use the lambda
  style.

  ## Full Example

  We can specify a full application like so:"
  (dc/mkdn-pprint-source Job)
  (dc/mkdn-pprint-source Person)
  (dc/mkdn-pprint-source Root)
  "And the resulting generated person would look like this (but would have the name `Person`):"
  (dc/mkdn-pprint-source GeneratedPerson)
  "And the resulting generated root would look like this (but would have the name `Person`):"
  (dc/mkdn-pprint-source GeneratedRoot)
  "

  See the docstring on the macro (or the source of this card file) for more details.")

(defcard-fulcro demo-card
  "Running Example

  This is a running example of the code above.
  "
  Root)
