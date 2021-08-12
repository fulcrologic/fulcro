(ns com.fulcrologic.fulcro.raw.components
  "Fulcro base component functions. This namespace has no hard dependency on React, and includes all of the core routines
   found in `components` (that ns just aliases to this one). There is no support in this namespace for creating standard
   `defsc` components that work in React-based Fulcro, but instead this namespace includes support for building
   \"normalizing component\" from EQL and sample instances. This gives you all of the general data management power
   with no ties to React."
  (:require
    #?(:cljs [goog.object :as gobj])
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.walk :refer [prewalk]]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.algorithms.transit :as transit]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log])
  #?(:clj
     (:import (clojure.lang IDeref))))

(defonce ^:private component-registry (atom {}))

;; Used internally by get-query for resolving dynamic queries (was created to prevent the need for external API change in 3.x)
(def ^:dynamic *query-state* nil)

(defn isoget-in
  "Like get-in, but for js objects, and in CLJC. In clj, it is just get-in. In cljs it is
  gobj/getValueByKeys."
  ([obj kvs]
   (isoget-in obj kvs nil))
  ([obj kvs default]
   #?(:clj (get-in obj kvs default)
      :cljs
           (let [ks (mapv (fn [k] (some-> k name)) kvs)]
             (or (apply gobj/getValueByKeys obj ks) default)))))

(defn isoget
  "Like get, but for js objects, and in CLJC. In clj, it is just `get`. In cljs it is
  `gobj/get`."
  ([obj k] (isoget obj k nil))
  ([obj k default]
   #?(:clj  (get obj k default)
      :cljs (or (gobj/get obj (some-> k (name))) default))))

(defn register-component!
  "Add a component to Fulcro's component registry.  This is used by defsc to ensure that all Fulcro classes
  that have been compiled (transitively required) will be accessible for lookup by fully-qualified symbol/keyword.
  Not meant for public use, unless you're creating your own component macro that doesn't directly leverage defsc."
  [k component-class]
  (swap! component-registry assoc k component-class)
  component-class)

(defn newer-props
  "Returns whichever of the given Fulcro props were most recently generated according to `denormalization-time`. This
  is part of props 'tunnelling', an optimization to get updated props to instances without going through the root."
  [props-a props-b]
  (cond
    (nil? props-a) props-b
    (nil? props-b) props-a
    (> (or (fdn/denormalization-time props-a) 2) (or (fdn/denormalization-time props-b) 1)) props-a
    :else props-b))


(defn component-instance?
  "Returns true if the argument is a component. A component is defined as a *mounted component*.
   This function returns false for component classes, and also returns false for the output of a Fulcro component factory."
  #?(:cljs {:tag boolean})
  [x]
  (if-not (nil? x)
    #?(:clj  (true? (:fulcro$isComponent x))
       :cljs (true? (gobj/get x "fulcro$isComponent")))
    false))

(defn any->app
  "Attempt to coerce `x` to an app.  Legal inputs are a fulcro application, a mounted component,
  or an atom holding any of the above."
  [x]
  (letfn [(fulcro-app? [x] (and (map? x) (contains? x :com.fulcrologic.fulcro.application/state-atom)))]
    (cond
      (component-instance? x) (isoget-in x [:props :fulcro$app])
      (fulcro-app? x) x
      #?(:clj  (instance? IDeref x)
         :cljs (satisfies? IDeref x)) (any->app (deref x)))))

(defn shared
  "Return the global shared properties of the root. See :shared and
   :shared-fn app options. NOTE: Shared props only update on root render and by explicit calls to
   `app/update-shared!`.

   This version does not rely on the dynamic var *shared*, which is only available from the react-based components ns."
  ([comp-or-app] (shared comp-or-app []))
  ([comp-or-app k-or-ks]
   (let [shared (some-> (any->app comp-or-app) :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/shared-props)
         ks     (cond-> k-or-ks
                  (not (sequential? k-or-ks)) vector)]
     (cond-> shared
       (not (empty? ks)) (get-in ks)))))

(def component?
  "Returns true if the argument is a component instance.

   DEPRECATED for terminology clarity. Use `component-instance?` instead."
  component-instance?)

(defn component-class?
  "Returns true if the argument is a component class."
  #?(:cljs {:tag boolean})
  [x]
  #?(:clj  (boolean (and (map? x) (:com.fulcrologic.fulcro.components/component-class? x)))
     :cljs (boolean (gobj/containsKey x "fulcro$class"))))

(defn component-name
  "Returns a string version of the given react component's name. Works on component instances and classes."
  [class]
  (isoget class :displayName))

(defn class->registry-key
  "Returns the registry key for the given component class."
  [class]
  (isoget class :fulcro$registryKey))

(defn registry-key->class
  "Look up the given component in Fulcro's global component registry. Will only be able to find components that have
  been (transitively) required by your application.

  `classname` can be a fully-qualified keyword or symbol."
  [classname]
  (cond
    (keyword? classname) (get @component-registry classname)
    (symbol? classname) (let [k (keyword (namespace classname) (name classname))]
                          (get @component-registry k))
    (and (string? classname)
      (str/includes? classname "/")) (let [[nspc nm] (str/split classname #"/")
                                           k (keyword nspc nm)]
                                       (get @component-registry k))
    :otherwise nil))

(declare props)

(defn computed
  "Add computed properties to props. This will *replace* any pre-existing computed properties. Computed props are
  necessary when a parent component wishes to pass callbacks or other data to children that *have a query*. This
  is not necessary for \"stateless\" components, though it will work properly for both.

  Computed props are \"remembered\" so that a targeted update (which can only happen on a component with a query
  and ident) can use new props from the database without \"losing\" the computed props that were originally passed
  from the parent. If you pass things like callbacks through normal props, then targeted updates will seem to \"lose
  track of\" them.
  "
  [props computed-map]
  (when-not (nil? props)
    (if (vector? props)
      (cond-> props
        (not (empty? computed-map)) (vary-meta assoc :fulcro.client.primitives/computed computed-map))
      (cond-> props
        (not (empty? computed-map)) (assoc :fulcro.client.primitives/computed computed-map)))))

(defn get-computed
  "Return the computed properties on a component or its props. Note that it requires that the normal properties are not nil."
  ([x]
   (get-computed x []))
  ([x k-or-ks]
   (when-not (nil? x)
     (let [props (cond-> x (component-instance? x) props)
           ks    (into [:fulcro.client.primitives/computed]
                   (cond-> k-or-ks
                     (not (sequential? k-or-ks)) vector))]
       (if (vector? props)
         (-> props meta (get-in ks))
         (get-in props ks))))))

(defn props
  "Return a component's props."
  [component]
  (let [props-from-parent    (isoget-in component [:props :fulcro$value])
        computed-from-parent (get-computed props-from-parent)
        props-from-updates   (computed (isoget-in component [:state :fulcro$value]) computed-from-parent)]
    (newer-props props-from-parent props-from-updates)))

(defn children
  "Get the sequence of react children of the given component."
  [component]
  (let [cs #?(:clj (get-in component [:children])
              :cljs (gobj/getValueByKeys component "props" "children"))]
    (if (or (coll? cs) #?(:cljs (array? cs))) cs [cs])))

(defn component-type
  "Returns the component type, regardless of whether the component has been
   mounted"
  [x]
  #?(:clj  (if (component-class? x) x (:fulcro$class x))
     :cljs (or (gobj/get x "type") (type x))))

(defn get-class
  "Returns the react type (component class) of the given React element (instance). Is identity if used on a class."
  [instance]
  (component-type instance))

(defn component-options
  "Returns the map of options that was specified (via `defsc`) for the component class."
  ([instance-or-class & ks]
   (let [c       (component-type instance-or-class)
         options (or (isoget instance-or-class :fulcro$options) (isoget c :fulcro$options))]
     (if (seq options)
       (get-in options (vec ks))
       options))))

(defn has-feature? #?(:cljs {:tag boolean}) [component option-key] (contains? (component-options component) option-key))
(defn has-initial-app-state? #?(:cljs {:tag boolean}) [component] (has-feature? component :initial-state))
(defn has-ident? #?(:cljs {:tag boolean}) [component] (has-feature? component :ident))
(defn has-query? #?(:cljs {:tag boolean}) [component] (has-feature? component :query))
(defn has-pre-merge? #?(:cljs {:tag boolean}) [component] (has-feature? component :pre-merge))
(defn ident [this props] (when (has-feature? this :ident) ((component-options this :ident) this props)))
(defn query [this] (when (has-feature? this :query) ((component-options this :query) this)))
(defn initial-state [clz params] (when (has-feature? clz :initial-state) ((component-options clz :initial-state) params)))
(defn pre-merge [this data] (when (has-feature? this :pre-merge) ((component-options this :pre-merge) data)))

(defn configure-anonymous-component!
  "Make a given `render-fn` (a plain fn) act like a a Fulcro component with the given component options map. Registers the
  new component in the component-registry. Component options MUST contain :componentName as be a fully-qualified
  keyword to name the component in the registry.

  component-options *must* include a unique `:componentName` (keyword) that will be used for registering the given
  function as the faux class in the component registry.

  IMPORTANT: In CLJS this function adds extra things to the mutable js fn. In CLJ, components are just maps, and this
  side-effect cannot modify it. As such it returns the configured component so you can use it in CLJ."
  [render-fn component-options]
  (let [k              (:componentName component-options)
        faux-classname (if k
                         (str/join "/" [(namespace k) (name k)])
                         "anonymous")
        result #?(:clj {:com.fulcrologic.fulcro.components/component-class? true
                        :fulcro$options                                     component-options
                        :fulcro$registryKey                                 k
                        :displayName                                        faux-classname}
                  :cljs (gobj/extend render-fn
                          #js {:fulcro$options         component-options
                               :displayName            faux-classname
                               :fulcro$class           render-fn
                               :type                   render-fn
                               :cljs$lang$type         true
                               :cljs$lang$ctorStr      faux-classname
                               :cljs$lang$ctorPrWriter (fn [_ writer _] (cljs.core/-write writer faux-classname))
                               :fulcro$registryKey     (:componentName component-options)}))]
    (when k
      (register-component! k #?(:cljs render-fn :clj result)))
    #?(:cljs render-fn :clj result)))

(defn get-initial-state
  "Get the declared :initial-state value for a component."
  ([class]
   (some-> (initial-state class {}) (with-meta {:computed true})))
  ([class params]
   (some-> (initial-state class params) (with-meta {:computed true}))))

(defn get-ident
  "Get the ident for a mounted component OR using a component class.

  That arity-2 will return the ident using the supplied props map.

  The single-arity version should only be used with a mounted component (e.g. `this` from `render`), and will derive the
  props that were sent to it most recently."
  ([x]
   {:pre [(component-instance? x)]}
   (if-let [m (props x)]
     (ident x m)
     (when #?(:clj false :cljs goog.DEBUG)
       (log/warn "get-ident was invoked on " (component-name x) " with nil props (this could mean it wasn't yet mounted): " x "See https://book.fulcrologic.com/#warn-get-ident-with-nil-props"))))
  ([class props]
   (when #?(:clj false :cljs (and goog.DEBUG (not (has-ident? class))))
     (log/warn "get-ident called with something that does not implement ident: " class "See https://book.fulcrologic.com/#warn-get-ident-invalid-class"))
   (if-let [id (ident class props)]
     (do
       (when (and #?(:clj false :cljs goog.DEBUG) (not (eql/ident? id)))
         (log/warn (component-name class) "get-ident returned invalid ident:" id "See https://book.fulcrologic.com/#warn-get-ident-invalid-ident"))
       (if (= :com.fulcrologic.fulcro.algorithms.merge/not-found (second id)) [(first id) nil] id))
     nil)))

(defn is-factory?
  "Returns true if the given argument is a component factory."
  [class-or-factory]
  (and (fn? class-or-factory)
    (-> class-or-factory meta (contains? :qualifier))))

(defn query-id
  "Returns a string ID for the query of the given class with qualifier."
  [class qualifier]
  (if (nil? class)
    (when #?(:clj false :cljs goog.DEBUG)
      (log/error "Query ID received no class (if you see this warning, it probably means metadata was lost on your query) See https://book.fulcrologic.com/#err-comp-query-id-no-class" (ex-info "" {})))
    (when-let [classname (component-name class)]
      (str classname (when qualifier (str "$" qualifier))))))

(defn denormalize-query
  "Takes a state map that may contain normalized queries and a query ID. Returns the stored query or nil."
  [state-map ID]
  (let [get-stored-query (fn [id]
                           (let [{:keys [query component-key]} (get-in state-map [:com.fulcrologic.fulcro.components/queries id])
                                 component (registry-key->class component-key)]
                             (when-not component (get-in state-map [:com.fulcrologic.fulcro.components/queries id]))
                             (some-> query (vary-meta assoc :component component :queryid id))))]
    (when-let [normalized-query (get-stored-query ID)]
      (prewalk (fn [ele]
                 (if-let [q (and (string? ele) (get-stored-query ele))]
                   q
                   ele)) normalized-query))))

(defn- get-query-id
  "Get the query id that is cached in the component's props."
  [component]
  (isoget-in component #?(:clj  [:props :fulcro$queryid]
                          :cljs [:props "fulcro$queryid"])))

(defn get-query-by-id [state-map class queryid]
  (let [query (or (denormalize-query state-map queryid) (query class))]
    (with-meta query {:component class
                      :queryid   queryid})))

(defn get-query
  "Get the query for the given class or factory. If called without a state map, then you'll get the declared static
  query of the class. If a state map is supplied, then the dynamically set queries in that state will result in
  the current dynamically-set query according to that state."
  ([class-or-factory]
   (if (= "anonymous" (component-name class-or-factory))    ; anonymous classes are not in the registry and do not support dyn queries
     (query class-or-factory)
     (get-query class-or-factory *query-state*)))
  ([class-or-factory state-map]
   (when (nil? class-or-factory)
     (throw (ex-info "nil passed to get-query" {})))
   (binding [*query-state* state-map]
     (let [class     (cond
                       (is-factory? class-or-factory) (-> class-or-factory meta :class)
                       (component-instance? class-or-factory) (component-type class-or-factory)
                       :else class-or-factory)
           ;; Hot code reload. Avoid classes that were cached on metadata using the registry.
           class     (if #?(:cljs goog.DEBUG :clj false)
                       (or (-> class class->registry-key registry-key->class) class)
                       class)
           qualifier (if (is-factory? class-or-factory)
                       (-> class-or-factory meta :qualifier)
                       nil)
           queryid   (if (component-instance? class-or-factory)
                       (get-query-id class-or-factory)
                       (query-id class qualifier))]
       (when (and class (has-query? class))
         (get-query-by-id state-map class queryid))))))

(def ^:dynamic *after-render*
  "Dynamic var that affects the activation of transactions run via `transact!`. Defaults to false. When set to true
   this option prevents a transaction from running until after the next render is complete. This typically should not be set
   to true in scenarios where you are unsure if a render will occur, since that could make the transaction appear to
   \"hang\"."
  false)

(defn transact!
  "Submit a transaction for processing.

  The underlying transaction system is pluggable, but the *default* supported options are:

  - `:optimistic?` - boolean. Should the transaction be processed optimistically?
  - `:ref` - ident. The ident of the component used to submit this transaction. This is set automatically if you use a component to call this function.
  - `:component` - React element. Set automatically if you call this function using a component.
  - `:refresh` - Vector containing idents (of components) and keywords (of props). Things that have changed and should be re-rendered
    on screen. Only necessary when the underlying rendering algorithm won't auto-detect, such as when UI is derived from the
    state of other components or outside of the directly queried props. Interpretation depends on the renderer selected:
    The ident-optimized render treats these as \"extras\".
  - `:only-refresh` - Vector of idents/keywords.  If the underlying rendering configured algorithm supports it: The
    components using these are the *only* things that will be refreshed in the UI.
    This can be used to avoid the overhead of looking for stale data when you know exactly what
    you want to refresh on screen as an extra optimization. Idents are *not* checked against queries.
  - `:abort-id` - An ID (you make up) that makes it possible (if the plugins you're using support it) to cancel
    the network portion of the transaction (assuming it has not already completed).
  - `:compressible?` - boolean. Check compressible-transact! docs.
  - `:synchronous?` - boolean. When turned on the transaction will run immediately on the calling thread. If run against
  a component then the props will be immediately tunneled back to the calling component, allowing for React (raw) input
  event handlers to behave as described in standard React Forms docs (uses setState behind the scenes). Any remote operations
  will still be queued as normal. Calling `transact!!` is a shorthand for this option. WARNING: ONLY the given component will
  be refreshed in the UI. If you have dependent data elsewhere in the UI you must either use `transact!` or schedule
  your own global render using `app/schedule-render!`.
  ` `:after-render?` - Wait until the next render completes before allowing this transaction to run. This can be used
  when calling `transact!` from *within* another mutation to ensure that the effects of the current mutation finish
  before this transaction takes control of the CPU. This option defaults to `false`, but `defmutation` causes it to
  be set to true for any transactions run within mutation action sections. You can affect the default for this value
  in a dynamic scope by binding `*after-render*` to true

  NOTE: This function calls the application's `tx!` function (which is configurable). Fulcro 2 'follow-on reads' are
  supported by the default version and are added to the `:refresh` entries. Your choice of rendering algorithm will
  influence their necessity.

  Returns the transaction ID of the submitted transaction.
  "
  ([app-or-component tx options]
   (when-let [app (any->app app-or-component)]
     (let [tx!     (ah/app-algorithm app :tx!)
           options (cond-> options
                     (and (not (contains? options :after-render?)) (true? *after-render*)) (assoc :after-render? true)
                     (and (nil? (:ref options)) (has-ident? app-or-component)) (assoc :ref (get-ident app-or-component))
                     (and (nil? (:component options)) (component-instance? app-or-component)) (assoc :component app-or-component))]
       (tx! app tx options))))
  ([app-or-comp tx]
   (transact! app-or-comp tx {})))

(defn transact!!
  "Shorthand for exactly `(transact! component tx (merge options {:synchronous? true}))`.

  Runs a synchronous transaction, which is an optimized mode where the optimistic behaviors of the mutations in the
  transaction run on the calling thread, and new props are immediately made available to the calling component via
  \"props tunneling\" (a behind-the-scenes mechanism using js/setState).

  This mode is meant to be used in form input event handlers, since React is designed to only work properly with
  raw DOM inputs via component-local state. This prevents things like the cursor jumping to the end of inputs
  unexpectedly.

  WARNING: Using an `app` instead of a component in synchronous transactions makes no sense. You must pass a component
  that has an ident.

  If you're using this, you can also set the compiler option:

  ```
  :compiler-options {:external-config {:fulcro     {:wrap-inputs? false}}}
  ```

  to turn off Fulcro DOM's generation of wrapped inputs (which try to solve this problem in a less-effective way).

  WARNING: Synchronous rendering does *not* refresh the full UI, only the component.
  "
  ([component tx] (transact!! component tx {}))
  ([component tx options]
   (transact! component tx (merge options {:synchronous? true}))))

(declare normalize-query)

(defn link-element
  "Part of internal implementation of dynamic queries."
  [element]
  (prewalk (fn link-element-helper [ele]
             (let [{:keys [queryid]} (meta ele)]
               (if queryid queryid ele))) element))

(defn normalize-query-elements
  "Part of internal implementation of dynamic queries.

  Determines if there are query elements in the `query` that need to be normalized. If so, it does so.

  Returns the new state map containing potentially-updated normalized queries."
  [state-map query]
  (reduce
    (fn normalize-query-elements-reducer [state ele]
      (try
        (let [parameterized? (seq? ele)
              raw-element    (if parameterized? (first ele) ele)]
          (cond
            (util/union? raw-element) (let [union-alternates            (first (vals raw-element))
                                            union-meta                  (-> union-alternates meta)
                                            normalized-union-alternates (-> (into {} (map link-element union-alternates))
                                                                          (with-meta union-meta))
                                            union-query-id              (-> union-alternates meta :queryid)
                                            union-component-key         (-> union-alternates meta :component class->registry-key)]
                                        (assert union-query-id "Union query has an ID. Did you use extended get-query?")
                                        (util/deep-merge
                                          {:com.fulcrologic.fulcro.components/queries {union-query-id {:query         normalized-union-alternates
                                                                                                       :component-key union-component-key
                                                                                                       :id            union-query-id}}}
                                          (reduce (fn normalize-union-reducer [s [_ subquery]]
                                                    (normalize-query s subquery)) state union-alternates)))
            (and
              (util/join? raw-element)
              (util/recursion? (util/join-value raw-element))) state
            (util/join? raw-element) (normalize-query state (util/join-value raw-element))
            :else state))
        (catch #?(:clj Exception :cljs :default) e
          (when #?(:clj false :cljs goog.DEBUG)
            (log/error e "Query normalization failed. Perhaps you tried to set a query with a syntax error? See https://book.fulcrologic.com/#err-comp-q-norm-failed")))))
    state-map query))

(defn link-query
  "Part of dyn query implementation. Find all of the elements (only at the top level) of the given query and replace them
  with their query ID."
  [query]
  (let [metadata (meta query)]
    (if (map? query)
      (with-meta
        (enc/map-vals (fn [ele] (let [{:keys [queryid]} (meta ele)] queryid)) query)
        metadata)
      (with-meta
        (mapv link-element query)
        metadata))))

(defn normalize-query
  "Given a state map and a query, returns a state map with the query normalized into the database. Query fragments
  that already appear in the state will not be added.  Part of dynamic query implementation."
  [state-map query]
  (let [queryid       (some-> query meta :queryid)
        component-key (class->registry-key (some-> query meta :component))
        query'        (vary-meta query dissoc :queryid :component)
        new-state     (normalize-query-elements state-map query')
        new-state     (if (nil? (:com.fulcrologic.fulcro.components/queries new-state))
                        (assoc new-state :com.fulcrologic.fulcro.components/queries {})
                        new-state)
        top-query     (link-query query')]
    (if (and queryid component-key)
      (util/deep-merge {:com.fulcrologic.fulcro.components/queries {queryid {:query top-query :id queryid :component-key component-key}}} new-state)
      new-state)))

(defn set-query*
  "Put a query in app state.

  NOTE: Indexes must be rebuilt after setting a query, so this function should primarily be used to build
  up an initial app state."
  [state-map class-or-factory {:keys [query] :as args}]
  (let [queryid   (cond
                    (nil? class-or-factory)
                    nil

                    (some-> class-or-factory meta (contains? :queryid))
                    (some-> class-or-factory meta :queryid)

                    :otherwise (query-id class-or-factory nil))
        component (or (-> class-or-factory meta :class) class-or-factory)
        setq*     (fn [state]
                    (normalize-query
                      (update state :com.fulcrologic.fulcro.components/queries dissoc queryid)
                      (vary-meta query assoc :queryid queryid :component component)))]
    (if (string? queryid)
      (cond-> state-map
        (contains? args :query) (setq*))
      (do
        (when #?(:clj false :cljs goog.DEBUG)
          (log/error "Set query failed. There was no query ID. Use a class or factory for the second argument. See https://book.fulcrologic.com/#err-comp-set-q-failed"))
        state-map))))

(defn set-query!
  "Public API for setting a dynamic query on a component. This function alters the query and rebuilds internal indexes.

  * `x` : is anything that any->app accepts.
  * `class-or-factory` : A component class or factory for that class (if using query qualifiers)
  * `opts` : A map with `query` and optionally `params` (substitutions on queries)
  "
  [x class-or-factory {:keys [query params] :as opts}]
  (let [app        (any->app x)
        state-atom (:com.fulcrologic.fulcro.application/state-atom app)
        queryid    (cond
                     (string? class-or-factory) class-or-factory
                     (some-> class-or-factory meta (contains? :queryid)) (some-> class-or-factory meta :queryid)
                     :otherwise (query-id class-or-factory nil))]
    (if (and (string? queryid) (or query params))
      (let [index-root!      (ah/app-algorithm app :index-root!)
            schedule-render! (ah/app-algorithm app :schedule-render!)]
        (swap! state-atom set-query* class-or-factory {:queryid queryid :query query :params params})
        (when index-root! (index-root! app))
        (util/dev-check-query (get-query class-or-factory @state-atom) component-name)
        (when schedule-render! (schedule-render! app {:force-root? true})))
      (when #?(:clj false :cljs goog.DEBUG)
        (log/error "Unable to set query. Invalid arguments. See https://book.fulcrologic.com/#err-comp-unable-set-q")))))

(letfn [(--set-query! [app class-or-factory {:keys [query] :as params}]
          (let [state-atom (:com.fulcrologic.fulcro.application/state-atom app)
                queryid    (cond
                             (string? class-or-factory) class-or-factory
                             (some-> class-or-factory meta (contains? :queryid)) (some-> class-or-factory meta :queryid)
                             :otherwise (query-id class-or-factory nil))]
            (if (and (string? queryid) (or query params))
              (swap! state-atom set-query* class-or-factory {:queryid queryid :query query :params params})
              (when #?(:clj false :cljs goog.DEBUG)
                (log/error "Unable to set query. Invalid arguments. See https://book.fulcrologic.com/#err-comp-unable-set-q")))))]
  (defn refresh-dynamic-queries!
    "Refresh the current dynamic queries in app state to reflect any updates to the static queries of the components.

     This can be used at development time to update queries that have changed but that hot code reload does not
     reflect (because there is a current saved query in state). This is *not* always what you want, since a component
     may have a custom query whose prop-level elements are set to a particular thing on purpose.

     An component that has `:preserve-dynamic-query? true` in its component options will be ignored by
     this function."
    ([app-ish cls force?]
     (let [app (any->app app-ish)]
       (let [preserve? (and (not force?) (component-options cls :preserve-dynamic-query?))]
         (when-not preserve?
           (set-query! app cls {:query (get-query cls {})})))))
    ([app-ish]
     (let [{:com.fulcrologic.fulcro.application/keys [state-atom] :as app} (any->app app-ish)
           state-map  @state-atom
           queries    (get state-map :com.fulcrologic.fulcro.components/queries)
           classnames (keys queries)]
       (doseq [nm classnames
               :let [cls       (registry-key->class nm)
                     preserve? (component-options cls :preserve-dynamic-query?)]]
         (when-not preserve?
           (--set-query! app cls {:query (get-query cls {})})))
       (let [index-root!      (ah/app-algorithm app :index-root!)
             schedule-render! (ah/app-algorithm app :schedule-render!)]
         (when index-root! (index-root! app))
         (when schedule-render! (schedule-render! app {:force-root? true})))))))

(defn compressible-transact!
  "Identical to `transact!` with `:compressible? true` option. This means that if more than one
  adjacent history transition edge is compressible, only the more recent of the sequence of them is kept. This
  is useful for things like form input fields, where storing every keystoke in history is undesirable. This
  also compress the transactions in Fulcro Inspect.

  NOTE: history events that trigger remote interactions are not compressible, since they may be needed for
  automatic network error recovery handling."
  ([app-ish tx]
   (transact! app-ish tx {:compressible? true}))
  ([app-ish ref tx]
   (transact! app-ish tx {:compressible? true
                          :ref           ref})))

(defn external-config
  "Get any custom external configuration that was added to the app at creation-time."
  [app-ish k]
  (some-> app-ish (any->app) (get-in [:com.fulcrologic.fulcro.application/config :external-config k])))

(defn check-component-registry!
  "Walks the complete list of components in the component registry and looks for problems. Used during dev mode to
   detect common problems that can cause runtime misbehavior."
  []
  (when #?(:clj false :cljs goog.DEBUG)
    (let [components (vals @component-registry)]
      (doseq [c components]
        (let [ident           (and (has-ident? c) (get-ident c {}))
              query           (get-query c)
              constant-ident? (and (vector? ident) (second ident))]
          (when (and constant-ident?
                  (not (has-initial-app-state? c))
                  (not= "com.fulcrologic.fulcro.algorithms.form-state/FormConfig" (component-name c)))
            (log/warn "Component" (component-name c) "has a constant ident (id in the ident is not nil for empty props),"
              "but it has no initial state. This could cause this component's props to"
              "appear as nil unless you have a mutation or load that connects it to the graph after application startup. See https://book.fulcrologic.com/#warn-constant-ident-no-initial-state"))
          (when-let [initial-state (and (has-initial-app-state? c) (get-initial-state c {}))]
            (when (map? initial-state)
              (let [initial-keys (set (keys initial-state))
                    join-map     (into {}
                                   (comp
                                     (filter #(and (= :join (:type %)) (keyword (:key %))))
                                     (map (fn [{:keys [key component]}] [key component])))
                                   (some->> query (eql/query->ast) :children))
                    join-keys    (set (keys join-map))]
                (when-let [missing-initial-keys (seq (set/difference join-keys initial-keys))]
                  (doseq [k missing-initial-keys
                          :let [target (get join-map k)]]
                    (when (and (has-initial-app-state? target)
                            (not= (component-name target) "com.fulcrologic.fulcro.algorithms.form-state/FormConfig"))
                      (log/warn "Component" (component-name c) "does not INCLUDE initial state for" (component-name target)
                        "at join key" k "; however, " (component-name target) "HAS initial state. This probably means your initial state graph is incomplete"
                        "and props on" (component-name target) "will be nil. See https://book.fulcrologic.com/#warn-initial-state-incomplete"))))))))))))

(defn id-key
  "Returns the keyword of the most likely ID attribute in the given props (the first one with the `name` \"id\").
  Returns nil if there isn't one. This is useful when trying to derive an ident from a sample tree of data, for example."
  [props]
  (first (filter #(= "id" (name %)) (keys props))))

(defn ast-id-key
  "Returns the first child from a list of EQL AST nodes that looks like an entity ID key."
  [children]
  (:key
    (first
      (filter (fn [{:keys [type key]}]
                (and
                  (keyword? key)
                  (= :prop type)
                  (= "id" (name key))))
        children))))

(defn- normalize* [{:keys [children type] :as original-node} {:keys [componentName] :as top-component-options}]
  (let [detected-id-key (ast-id-key children)
        real-id-key     (or detected-id-key)
        component       (fn [& args])
        new-children    (mapv
                          (fn [{:keys [type] :as node}]
                            (if (and (= type :join) (not (:component node)))
                              (normalize* node {})
                              node))
                          children)
        qatom           (atom nil)
        component       (configure-anonymous-component! component
                          (cond-> (with-meta
                                    (merge
                                      {:initial-state (fn [& args] {})}
                                      top-component-options
                                      {:query  (fn [& args] @qatom)
                                       "props" {"fulcro$queryid" :anonymous}})
                                    {:query-id :anonymous})

                            componentName (assoc :componentName componentName)

                            (and real-id-key
                              (not (contains? top-component-options :ident)))
                            (assoc :ident (fn [_ props] [real-id-key (get props real-id-key)]))))
        updated-node    (assoc original-node :children new-children :component component)
        query           (if (= type :join)
                          (eql/ast->query (assoc updated-node :type :root))
                          (eql/ast->query updated-node))
        _               (reset! qatom query)]
    updated-node))

(defn nc
  "Create an anonymous normalizing query component. By default the normalization will be auto-detected based on there being a prop at each
   entity level that has (any) namespace, and a name of `id`. For example:

   ```
   [:list/id :list/name {:list/items [:item/id :item/complete? :item/label]}]
   ```

   will create a normalizing query that expects the top-level values to be normalized by `:list/id` and the nested
   items to be normalized by `:item/id`. If there is more than one ID in your props, make sure the *first* one is
   the one to use for normalization.

   The `top-component-options` becomes the options map of the component.

   You can include :componentName to push the resulting anonymous component definition into the component registry, which
   is needed by some parts of Fulcro, like UISM.

   NOTE: `nc` is recursive, and *does* compose if you want to name the components at various levels. It can be used with queries from
   other defsc components:

   ```
   (def query (nc [:user/id
                   :user/name
                   ;; Generate an anonymous component that is available in the registry under ::Session
                   {:user/session-details (nc [:session/id :session/last-login] {:componentName ::Session})}
                   ;; Use a defsc query as the source
                   {:user/settings (comp/get-query Settings)}
                   ;; Autogenerates an anonymous address query component that has no name
                   {:user/address [:address/id :address/street]}]))
   ```
   "
  ([query] (nc query {}))
  ([query {:keys [componentName] :as top-component-options}]
   (let [ast (eql/query->ast query)]
     (:component (normalize* ast top-component-options)))))

(defn entity->component
  "Creates a normalizing component from an entity tree. Every sub-element of the tree provided will generate an anonymous
   normalizing component if that element has an ID field. For to-many relations only the first item is used for query/ident
   generation.

   The returned anonymous component will have initial state that matches the provided entity data tree.

   This means you can use a sample tree to generate both the initial state for a subtree of your app and the components
   necessary to do I/O on that tree.

   This kind of component will *not* be registered in the component registry unless you pass a :componentName
   via the top-level-options. A registry entry is necessary for things that
   require the registry, such as dynamic queries and UI state machines).
   "
  ([entity-data-tree]
   (entity->component entity-data-tree {}))
  ([entity-data-tree top-level-options]
   (let [{:keys [joins initial-state attrs]} (reduce-kv
                                               (fn [result k v]
                                                 (cond
                                                   (and (vector? v) (every? map? v))
                                                   (let [c (entity->component (first v))]
                                                     (-> result
                                                       (update :initial-state assoc k v)
                                                       (update :joins assoc k (query c))))
                                                   (map? v) (let [c (entity->component v)]
                                                              (-> result
                                                                (update :initial-state assoc k v)
                                                                (update :joins assoc k (query c))))
                                                   :else (-> result
                                                           (update :initial-state assoc k v)
                                                           (update :attrs conj k))))
                                               {:attrs         #{}
                                                :initial-state {}
                                                :joins         {}}
                                               entity-data-tree)
         query (into (vec attrs)
                 (map (fn build-subquery* [[join-key subquery]] {join-key subquery}))
                 joins)]
     (nc query (merge
                 {:initial-state (fn [& args] initial-state)}
                 top-level-options)))))

(letfn [(get-subquery-component*
          [c ast-nodes query-path]
          (if (empty? ast-nodes)
            c
            (let [k  (first query-path)
                  ks (rest query-path)
                  {:keys [component children] :as node} (first (filter #(= k (:key %)) ast-nodes))]
              (if (seq ks)
                (recur component children ks)
                component))))]

  (defn get-subquery-component
    "Obtains the normalizing component that is associated with the given query path on the given component.

    For example `(get-subquery-component Person [:person/addresses])` would return the component for
    the `:person/addresses` join. If state-map is supplied then dynamic query support is possible; otherwise it
    will be the original static query."
    ([component query-path]
     (get-subquery-component component query-path {}))
    ([component query-path state-map]
     (let [query     (get-query component state-map)
           ast-nodes (-> query eql/query->ast :children)]
       (get-subquery-component* component ast-nodes query-path)))))

(defn get-traced-props
  "Uses `fdn/traced-db->tree` to get the props of the component at `ident`, and leverages those optimizations to return
   `prior-props` if they are not stale.

   A subsequent call (e.g. on next render frame) of this function with the prior return value (as `prior-props`)
   thus gives you an efficient non-react replacement for `shouldComponentUpdate`, etc.
   "
  [state-map component ident prior-props]
  (let [query (get-query component state-map)]
    (if (fdn/possibly-stale? state-map prior-props)
      (fdn/traced-db->tree state-map ident query)
      prior-props)))

(defn has-active-state?
  "Returns true if there is already data at a component's `ident`"
  [state-map ident]
  (let [current-value (get-in state-map ident)]
    (and (map? current-value) (seq current-value))))

(comment
  (def Person (entity->component
                {:person/id        1
                 :ui/checked?      true
                 :person/name      "Bob"
                 :person/addresses [{:ui/autocomplete ""
                                     :address/id      11
                                     :address/street  "111 Main St"}
                                    {:ui/autocomplete ""
                                     :address/id      12
                                     :address/street  "222 Main St"}]}
                {:componentName ::MyThing}))

  (def Address (get-subquery-component Person [:person/addresses]))

  (get-ident Address {:address/id 99})
  )
