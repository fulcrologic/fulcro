(ns com.fulcrologic.fulcro.components
  #?(:cljs (:require-macros com.fulcrologic.fulcro.components))
  (:require
    #?@(:clj
        [[clojure.reflect :as reflect]
         [com.fulcrologic.fulcro.macros.defsc :refer [defsc*]]
         [cljs.analyzer :as ana]]
        :cljs
        [[goog.object :as gobj]])
    [fulcro.util :as util]
    [taoensso.timbre :as log]
    [clojure.walk :refer [prewalk]]
    [clojure.string :as str])
  #?(:clj
     (:import (clojure.lang Associative IDeref))))

(def options-key "fulcro$options")

(defonce ^:private component-registry (atom {}))
(def ^:dynamic *query-state* nil)

(defn -register-component!
  "Add a component to Fulcro's component registry.  This is used by defsc and defui to ensure that all Fulcro classes
  that have been compiled (transitively required) will be accessible for lookup by name.  Not meant for public use,
  unless you're creating your own component macro that doesn't directly leverage defsc/defui."
  [k component-class]
  (swap! component-registry assoc k component-class)
  component-class)

(defn newer-props
  "Returns whichever of the given Fulcro props were most recently generated."
  [props-a props-b]
  (cond
    (nil? props-a) props-b
    (nil? props-b) props-a
    (> (or (-> props-a meta ::time) 2) (or (-> props-b meta ::time) 1)) props-a
    :else props-b))

(defn component?
  "Returns true if the argument is a component. A component is defined as a *mounted React-based javascript component*.
   This function returns false for component classes, and also returns false for the output of a Fulcro component factory."
  #?(:cljs {:tag boolean})
  [x]
  (if-not (nil? x)
    #?(:clj  false                                          ; TODO
       :cljs (true? (gobj/get x "fulcro$isComponent")))
    false))

(declare props)

(defn computed
  "Add computed properties to props. Note will replace any pre-existing
   computed properties."
  [props computed-map]
  (when-not (nil? props)
    (if (vector? props)
      (cond-> props
        (not (empty? computed-map)) (vary-meta assoc :fulcro.client.primitives/computed computed-map))
      (cond-> props
        (not (empty? computed-map)) (assoc :fulcro.client.primitives/computed computed-map)))))

(defn get-computed
  "Return the computed properties on a component or its props."
  ([x]
   (get-computed x []))
  ([x k-or-ks]
   (when-not (nil? x)
     (let [props (cond-> x (component? x) props)
           ks    (into [:fulcro.client.primitives/computed]
                   (cond-> k-or-ks
                     (not (sequential? k-or-ks)) vector))]
       (if (vector? props)
         (-> props meta (get-in ks))
         (get-in props ks))))))

(defn get-extra-props
  "Get any data (as a map) that extensions have associated with the given Fulcro component."
  [this]
  #?(:clj  {}
     :cljs (do
             (js/console.log this)
             (or (gobj/getValueByKeys this "props" "fulcro$extra_props") {}))))

(defn props
  "Return a components props."
  [component]
  #?(:clj  nil                                              ;; FIXME
     :cljs (let [props-from-parent    (gobj/getValueByKeys component "props" "fulcro$value")
                 computed-from-parent (get-computed props-from-parent)
                 props-from-updates   (computed (gobj/getValueByKeys component "state" "fulcro$value") computed-from-parent)]
             (newer-props props-from-parent props-from-updates))))

#?(:clj
   (defn react-type
     "Returns the component type, regardless of whether the component has been
      mounted"
     [component]
     {:pre [(component? component)]}
     (let [[klass-name] (str/split (reflect/typename (type component)) #"_klass")
           last-idx-dot (.lastIndexOf klass-name ".")
           ns           (clojure.main/demunge (subs klass-name 0 last-idx-dot))
           c            (subs klass-name (inc last-idx-dot))]
       @(or (find-var (symbol ns c))
          (find-var (symbol ns (clojure.main/demunge c)))))))

#?(:cljs
   (defn react-type
     "Returns the component type, regardless of whether the component has been
      mounted"
     [x]
     (or (gobj/get x "type") (type x))))

(defn component-options
  ([this & ks]
   (let [c               (react-type this)
         options #?(:cljs (or (gobj/get this options-key) (gobj/get c options-key))
                    ;; FIXME
                    :clj nil)]
     (get-in options (vec ks))))
  ([this]
   (let [c               (react-type this)
         options #?(:cljs (or (gobj/get this options-key) (gobj/get c options-key))
                    ;; FIXME
                    :clj nil)]
     options)))

(defn has-feature? #?(:cljs {:tag boolean}) [component option-key] (contains? (component-options component) option-key))
(defn has-initial-app-state? #?(:cljs {:tag boolean}) [component] (has-feature? component :initial-state))
(defn has-ident? #?(:cljs {:tag boolean}) [component] (has-feature? component :ident))
(defn has-query? #?(:cljs {:tag boolean}) [component] (has-feature? component :query))
(defn has-pre-merge? #?(:cljs {:tag boolean}) [component] (has-feature? component :pre-merge))
(defn ident [this props] (when (has-feature? this :ident) ((component-options this :ident) this props)))
(defn query [this] (when (has-feature? this :query) ((component-options this :query) this)))
(defn initial-state [clz params] (when (has-feature? clz :initial-state) ((component-options clz :initial-state) clz params)))
(defn pre-merge [this data] (when (has-feature? this :pre-merge) ((component-options this :pre-merge) this data)))

(defn get-raw-react-prop
  "GET a RAW react prop"
  [c k]
  #?(:clj  nil                                              ;; FIXME
     :cljs (gobj/getValueByKeys c "props" k)))

(defn fulcro-app? [x] (and (map? x) (contains? x ::state-atom) (contains? x ::runtime-atom)))

(defn any->app
  "Attempt to coerce `x` to a reconciler.  Legal inputs are a fulcro application, reconciler, a mounted component, a
  map with a :reconciler key, or an atom holding any of the above."
  [x]
  (cond
    (component? x) (get-raw-react-prop x #?(:clj  :fulcro$reconciler
                                            :cljs "fulcro$reconciler"))
    (fulcro-app? x) x
    #?(:clj  (instance? IDeref x)
       :cljs (satisfies? IDeref x)) (any->app (deref x))))

(defn index-component! [this]
  (let [{:keys [:com.fulcrologic.fulcro.application/runtime-atom]} (any->app this)
        ident (component-options this :ident)]
    (when (and ident runtime-atom)
      (log/info "Adding component with ident " (ident this (props this)) "to index")
      (swap! runtime-atom update-in
        [:com.fulcrologic.fulcro.application/indexes :ident->components (ident this (props this))]
        (fnil conj #{})
        this))))

(defn drop-index!
  ([this ident]
   (let [{:keys [:com.fulcrologic.fulcro.application/runtime-atom]} (any->app this)]
     (when (and ident runtime-atom)
       (log/info "Dropping component with ident " ident "from index")
       (swap! runtime-atom update-in
         [:com.fulcrologic.fulcro.application/indexes :ident->components ident]
         disj
         this))))
  ([this]
   (let [{:keys [:com.fulcrologic.fulcro.application/runtime-atom]} (any->app this)
         {:keys [ident]} (component-options this)]
     (when (and ident runtime-atom)
       (log/info "Dropping component with ident " (ident this (props this)) "from index")
       (swap! runtime-atom update-in
         [:com.fulcrologic.fulcro.application/indexes :ident->components (ident this (props this))]
         disj
         this)))))

(defn raw->newest-props
  "Using raw react props/state returns the newest Fulcro props."
  [raw-props raw-state]
  #?(:clj  raw-props
     :cljs (let [next-props (gobj/get raw-props "fulcro$value")
                 opt-props  (gobj/get raw-state "fulcro$value")]
             (newer-props next-props opt-props))))

(letfn
  [(wrap-props-state-handler
     ([handler]
      (wrap-props-state-handler handler true))
     ([handler check-for-fresh-props-in-state?]
      #?(:clj (fn [& args] (apply handler args))
         :cljs
              (fn [raw-props raw-state]
                (this-as this
                  (let [props (if check-for-fresh-props-in-state?
                                (raw->newest-props raw-props raw-state)
                                (gobj/get raw-props "fulcro$props"))
                        state (gobj/get raw-state "fulcro$state")]
                    (handler this props state)))))))
   (static-wrap-props-state-handler
     [handler]
     #?(:clj (fn [& args] (apply handler args))
        :cljs
        (fn [raw-props raw-state]
          (let [props (raw->newest-props raw-props raw-state)
                state (gobj/get raw-state "fulcro$state")]
            (handler props state)))))
   (should-component-update?
     [raw-next-props raw-next-state]
     #?(:clj true
        :cljs (this-as this
                (let [current-props     (props this)
                      next-props        (raw->newest-props raw-next-props raw-next-state)
                      next-state        (gobj/get raw-next-state "fulcro$state")
                      current-state     (gobj/get (.-state this) "fulcro$state")
                      props-changed?    (not= current-props next-props)
                      state-changed?    (not= current-state next-state)
                      next-children     (.-children next-props)
                      children-changed? (not= (.. this -props -children) next-children)]
                  (or props-changed? state-changed? children-changed?)))))
   (component-did-update
     [raw-prev-props raw-prev-state snapshot]
     #?(:cljs
        (this-as this
          (let [{:keys [ident componentDidUpdate]} (component-options this)
                prev-props (gobj/get raw-prev-props "fulcro$value")
                prev-state (gobj/get raw-prev-state "fulcro$state")]
            (when componentDidUpdate
              (componentDidUpdate this prev-props prev-state snapshot))
            (when ident
              (let [old-ident  (ident this prev-props)
                    next-ident (ident this (props this))]
                (when (not= old-ident next-ident)
                  (drop-index! this old-ident)
                  (index-component! this))))))))
   (component-did-mount
     []
     #?(:cljs
        (this-as this
          (gobj/set this "fulcro$mounted" true)
          (let [{:keys [componentDidMount]} (component-options this)]
            (index-component! this)
            (when componentDidMount
              (componentDidMount this))))))
   (component-will-unmount []
     #?(:cljs
        (this-as this
          (let [{:keys [componentWillUnmount]} (component-options this)]
            (when componentWillUnmount
              (componentWillUnmount this))
            (gobj/set this "fulcro$mounted" false)
            (drop-index! this)))))
   (wrap-this
     [handler]
     #?(:clj (fn [& args] (apply handler args))
        :cljs
        (fn [& args] (this-as this (apply handler this args)))))
   (wrap-props-handler
     ([handler]
      (wrap-props-handler handler true))
     ([handler check-for-fresh-props-in-state?]
      #?(:clj #(handler %1)
         :cljs
              (fn [raw-props]
                (this-as this
                  (let [raw-state (.-state this)
                        props     (if check-for-fresh-props-in-state?
                                    (raw->newest-props raw-props raw-state)
                                    (gobj/get raw-props "fulcro$props"))]
                    (handler this props)))))))]

  (defn configure-component!
    "Configure the given `cls` to act as a react component."
    [cls fqkw options]
    #?(:clj
       cls
       :cljs
       ;; This user-supplied versions will expect `this` as first arg
       (let [{:keys [getDerivedStateFromProps shouldComponentUpdate getSnapshotBeforeUpdate render
                     initLocalState componentDidCatch getDerivedStateFromError
                     componentWillUpdate componentWillMount componentWillReceiveProps
                     UNSAFE_componentWillMount UNSAFE_componentWillUpdate UNSAFE_componentWillReceiveProps
                     instance-props static-props]} options
             name              (str/join "/" [(namespace fqkw) (name fqkw)])
             constructor       ^js cls
             js-instance-props (clj->js
                                 (-> {:componentDidMount     component-did-mount
                                      :componentWillUnmount  component-will-unmount
                                      :componentDidUpdate    component-did-update
                                      :shouldComponentUpdate (if shouldComponentUpdate
                                                               (wrap-props-state-handler shouldComponentUpdate)
                                                               should-component-update?)
                                      :fulcro$isComponent    true
                                      :displayName           name}
                                   (cond->
                                     render (assoc :render (wrap-this render))
                                     getSnapshotBeforeUpdate (assoc :getSnapshotBeforeUpdate (wrap-props-state-handler getSnapshotBeforeUpdate))
                                     componentDidCatch (assoc :componentDidCatch (wrap-this componentDidCatch))
                                     UNSAFE_componentWillMount (assoc :UNSAFE_componentWillMount (wrap-this UNSAFE_componentWillMount))
                                     UNSAFE_componentWillUpdate (assoc :UNSAFE_componentWillUpdate (wrap-props-state-handler UNSAFE_componentWillUpdate))
                                     UNSAFE_componentWillReceiveProps (assoc :UNSAFE_componentWillReceiveProps (wrap-props-handler UNSAFE_componentWillReceiveProps))
                                     componentWillMount (assoc :componentWillMount (wrap-this componentWillMount))
                                     componentWillUpdate (assoc :componentWillUpdate (wrap-this componentWillUpdate))
                                     componentWillReceiveProps (assoc :componentWillReceiveProps (wrap-props-handler componentWillReceiveProps))
                                     initLocalState (assoc :initLocalState (wrap-this initLocalState)))))
             statics           (cond-> {:displayName            name
                                        :fulcro$isComponent     true
                                        :cljs$lang$type         true
                                        :cljs$lang$ctorStr      name
                                        :cljs$lang$ctorPrWriter (fn [_ writer _] (cljs.core/-write writer name))}
                                 getDerivedStateFromError (assoc :getDerivedStateFromError getDerivedStateFromError)
                                 getDerivedStateFromProps (assoc :getDerivedStateFromProps (static-wrap-props-state-handler getDerivedStateFromProps)))]
         (gobj/extend (.-prototype constructor) js/React.Component.prototype js-instance-props #js {"fulcro$options" options})
         (gobj/extend constructor (clj->js statics) #js {"fulcro$options" options})
         (-register-component! fqkw name)))))

(defn set-state!
  ([component new-state callback]
   #?(:cljs
      (.setState ^js component
        (fn [prev-state props]
          #js {"fulcro$state" (merge (gobj/get prev-state "fulcro$state") new-state)})
        callback)))
  ([component new-state]
   (set-state! component new-state nil)))

(defn get-state
  "Get a component's local state. May provide a single key or a sequential
   collection of keys for indexed access into the component's local state."
  ([component]
   (get-state component []))
  ([component k-or-ks]
   (let [cst #?(:clj @(:state component)
                :cljs (when-let [state (. component -state)] (gobj/get state "fulcro$state")))]
     (get-in cst (if (sequential? k-or-ks) k-or-ks [k-or-ks])))))

(let [update-fn (fn [component f args]
                  #?(:cljs (.setState component
                             (fn [prev-state props]
                               #js {"fulcro$state" (apply f (gobj/get prev-state "fulcro$state") args)}))))]
  (defn update-state!
    "Update a component's local state. Similar to Clojure(Script)'s swap!

    This function affects a managed cljs map maintained in React state.  If you want to affect the low-level
    js state itself use React's own `.setState` on the component."
    ([component f]
     (update-fn component f []))
    ([component f & args]
     (update-fn component f args))))

(defn get-initial-state
  "Get the initial state of a component. Needed because calling the protocol method from a defui component in clj will not work as expected."
  [class params]
  (some-> (initial-state class params) (with-meta {:computed true})))

(defn computed-initial-state?
  "Returns true if the given initial state was computed from a call to get-initial-state."
  [s]
  (and (map? s) (some-> s meta :computed)))

(defn get-ident
  "Get the ident for a mounted component OR using a component class.

  That arity-2 will return the ident using the supplied props map.

  The single-arity version should only be used with a mounted component (e.g. `this` from `render`), and will derive the
  props that were sent to it most recently."
  ([x]
   {:pre [(component? x)]}
   (if-let [m (props x)]
     (ident x m)
     (log/warn "get-ident was invoked on component with nil props (this could mean it wasn't yet mounted): " x)))
  ([class props]
   (if-let [id (ident class props)]
     (do
       (when-not (util/ident? id)
         (log/warn "get-ident returned an invalid ident for class:" class))
       (if (= :com.fulcrologic.fulcro.algorithms.merge/not-found (second id)) [(first id) nil] id))
     (log/warn "get-ident called with something that is either not a class or does not implement ident: " class))))

(defn component-name
  "Returns a string version of the given react component's name."
  [class]
  #?(:clj  (str (-> class meta :component-ns) "/" (-> class meta :component-name))
     :cljs (.-displayName ^js class)))

(defn is-factory?
  [class-or-factory]
  (and (fn? class-or-factory)
    (-> class-or-factory meta (contains? :qualifier))))

(defn query-id
  "Returns a string ID for the query of the given class with qualifier"
  [class qualifier]
  (if (nil? class)
    (log/error "Query ID received no class (if you see this warning, it probably means metadata was lost on your query)" (ex-info "" {}))
    (when-let [classname (component-name class)]
      (str classname (when qualifier (str "$" qualifier))))))

(defn denormalize-query
  "Takes a state map that may contain normalized queries and a query ID. Returns the stored query or nil."
  [state-map ID]
  (let [get-stored-query (fn [id] (get-in state-map [::queries id :query]))]
    (when-let [normalized-query (get-stored-query ID)]
      (prewalk (fn [ele]
                 (if-let [q (and (string? ele) (get-stored-query ele))]
                   q
                   ele)) normalized-query))))

(defn- get-query-id
  "Get the query id that is cached in the component's props."
  [component]
  (get-raw-react-prop component #?(:clj  :fulcro$queryid
                                   :cljs "fulcro$queryid")))

(defn get-query-by-id [state-map class queryid]
  (let [query (or (denormalize-query state-map queryid) (query class))]
    (with-meta query {:component class
                      :queryid   queryid})))

(defn get-query
  "Get the query for the given class or factory. If called without a state map, then you'll get the declared static
  query of the class. If a state map is supplied, then the dynamically set queries in that state will result in
  the current dynamically-set query according to that state."
  ([class-or-factory] (get-query class-or-factory *query-state*))
  ([class-or-factory state-map]
   (binding [*query-state* state-map]
     (let [class     (cond
                       (is-factory? class-or-factory) (-> class-or-factory meta :class)
                       (component? class-or-factory) (react-type class-or-factory)
                       :else class-or-factory)
           qualifier (if (is-factory? class-or-factory)
                       (-> class-or-factory meta :qualifier)
                       nil)
           queryid   (if (component? class-or-factory)
                       (get-query-id class-or-factory)
                       (query-id class qualifier))]
       (when (and class (has-query? class))
         (get-query-by-id state-map class queryid))))))

(defn make-state-map
  "Build a component's initial state using the defsc initial-state-data from
  options, the children from options, and the params from the invocation of get-initial-state."
  [initial-state children-by-query-key params]
  (let [join-keys (set (keys children-by-query-key))
        init-keys (set (keys initial-state))
        is-child? (fn [k] (contains? join-keys k))
        value-of  (fn value-of* [[isk isv]]
                    (let [param-name    (fn [v] (and (keyword? v) (= "param" (namespace v)) (keyword (name v))))
                          substitute    (fn [ele] (if-let [k (param-name ele)]
                                                    (get params k)
                                                    ele))
                          param-key     (param-name isv)
                          param-exists? (contains? params param-key)
                          param-value   (get params param-key)
                          child-class   (get children-by-query-key isk)]
                      (cond
                        ; parameterized lookup with no value
                        (and param-key (not param-exists?)) nil

                        ; to-one join, where initial state is a map to be used as child initial state *parameters* (enforced by defsc macro)
                        ; and which may *contain* parameters
                        (and (map? isv) (is-child? isk)) [isk (get-initial-state child-class (into {} (keep value-of* isv)))]

                        ; not a join. Map is literal initial value.
                        (map? isv) [isk (into {} (keep value-of* isv))]

                        ; to-many join. elements MUST be parameters (enforced by defsc macro)
                        (and (vector? isv) (is-child? isk)) [isk (mapv (fn [m] (get-initial-state child-class (into {} (keep value-of* m)))) isv)]

                        ; to-many join. elements might be parameter maps or already-obtained initial-state
                        (and (vector? param-value) (is-child? isk)) [isk (mapv (fn [params]
                                                                                 (if (computed-initial-state? params)
                                                                                   params
                                                                                   (get-initial-state child-class params))) param-value)]

                        ; vector of non-children
                        (vector? isv) [isk (mapv (fn [ele] (substitute ele)) isv)]

                        ; to-one join with parameter. value might be params, or an already-obtained initial-state
                        (and param-key (is-child? isk) param-exists?) [isk (if (computed-initial-state? param-value)
                                                                             param-value
                                                                             (get-initial-state child-class param-value))]
                        param-key [isk param-value]
                        :else [isk isv])))]
    (into {} (keep value-of initial-state))))

(defn wrapped-render [this real-render]
  #?(:clj
     (real-render)
     :cljs
     (let [{:com.fulcrologic.fulcro.specifications.application-specs/keys [middleware] :as app} (gobj/getValueByKeys this "props" "fulcro$reconciler")
           {:keys [render-middleware]} middleware]
       (log/info :app app)
       (if (log/spy :info render-middleware)
         (render-middleware this real-render)
         (real-render)))))

#?(:clj
   (defmacro ^{:doc      "Define a stateful component. This macro emits a React UI class with a query,
   optional ident (if :ident is specified in options), optional initial state, optional css, lifecycle methods,
   and a render method. It can also cause the class to implement additional protocols that you specify. Destructuring is
   supported in the argument list.

   The template (data-only) versions do not have any arguments in scope
   The lambda versions have arguments in scope that make sense for those lambdas, as listed below:

   ```
   (defsc Component [this {:keys [db/id x] :as props} {:keys [onSelect] :as computed} extended-args]
     {
      ;; stateful component options
      ;; query template is literal. Use the lambda if you have ident-joins or unions.
      :query [:db/id :x] ; OR (fn [] [:db/id :x]) ; this in scope
      ;; ident template is table name and ID property name
      :ident [:table/by-id :id] ; OR (fn [] [:table/by-id id]) ; this and props in scope
      ;; initial-state template is magic..see dev guide. Lambda version is normal.
      :initial-state {:x :param/x} ; OR (fn [params] {:x (:x params)}) ; this in scope
      ;; pre-merge, use a lamba to modify new merged data with component needs
      :pre-merge (fn [tree] (merge {:ui/default-value :start} tree))

      ; React Lifecycle Methods (this in scope)
      :initLocalState            (fn [this] ...) ; CAN BE used to call things as you might in a constructor. Return value is initial state.
      :shouldComponentUpdate     (fn [this next-props next-state] ...)

      :componentDidUpdate        (fn [this prev-props prev-state snapshot] ...) ; snapshot is optional, and is 16+. Is context for 15
      :componentDidMount         (fn [this] ...)
      :componentWillUnmount      (fn [this] ...)

      ;; DEPRECATED IN REACT 16 (to be removed in 17):
      :componentWillReceiveProps        (fn [this next-props] ...)
      :componentWillUpdate              (fn [this next-props next-state] ...)
      :componentWillMount               (fn [this] ...)

      ;; Replacements for deprecated methods in React 16.3+
      :UNSAFE_componentWillReceiveProps (fn [this next-props] ...)
      :UNSAFE_componentWillUpdate       (fn [this next-props next-state] ...)
      :UNSAFE_componentWillMount        (fn [this] ...)

      ;; ADDED for React 16:
      :componentDidCatch         (fn [this error info] ...)
      :getSnapshotBeforeUpdate   (fn [this prevProps prevState] ...)

      ;; static
      :getDerivedStateFromProps  (fn [props state] ...)

      ;; ADDED for React 16.6:
      :getDerivedStateFromError  (fn [error] ...)  **NOTE**: OVERWRITES entire state. This differs slightly from React.

      NOTE: shouldComponentUpdate should generally not be overridden other than to force it false so
      that other libraries can control the sub-dom. If you do want to implement it, then old props can
      be obtained from (prim/props this), and old state via (gobj/get (. this -state) \"fulcro$state\").

      ; BODY forms. May be omitted IFF there is an options map, in order to generate a component that is used only for queries/normalization.
      (dom/div #js {:onClick onSelect} x))
   ```

   See the Developer's Guide at book.fulcrologic.com for more details.
   "
               :arglists '([this dbprops computedprops]
                           [this dbprops computedprops extended-args])}
     defsc
     [& args]
     (let [location (str *ns* ":" (:line (meta &form)))]
       (try
         (defsc* &env args)
         (catch Exception e
           (if (contains? (ex-data e) :tag)
             (throw e)
             (throw (ana/error &env "Unexpected internal error while processing defsc. Please check your syntax." e))))))))
