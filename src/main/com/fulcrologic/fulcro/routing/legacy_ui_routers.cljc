(ns com.fulcrologic.fulcro.routing.legacy-ui-routers
  "Routers from Fulcro 2. These are a bit harder to use than the new dynamic router, and should probably not be used
   in new applications; however, they will be supported for the forseeable future."
  #?(:cljs (:require-macros com.fulcrologic.fulcro.routing.legacy-ui-routers))
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    #?@(:clj  [[cljs.analyzer :as ana]]
        :cljs [[cljsjs.react]
               [cljs.loader :as loader]])
    [taoensso.timbre :as log]))

#?(:clj
   (s/def ::mutation-args
     (s/cat
       :sym symbol?
       :doc (s/? string?)
       :arglist vector?
       :body (s/+ (constantly true)))))

(defn bad-route [page]
  #?(:cljs (js/React.createElement "div" nil (str "Cannot route: Unknown Screen " page))
     :clj  (str "Bad route " page)))

#?(:clj
   (defn- emit-union-element [sym ident-arg kws-and-screens]
     (try
       (let [query         (reduce (fn [q {:keys [kw sym]}] (assoc q kw `(comp/get-query ~sym))) {} kws-and-screens)
             first-screen  (-> kws-and-screens first :sym)
             screen-render (fn [cls] `((comp/factory ~cls {:keyfn (fn [_] ~(name cls))}) (comp/props ~'this)))
             render-stmt   (reduce (fn [cases {:keys [kw sym]}]
                                     (-> cases
                                       (conj kw (screen-render sym)))) [] kws-and-screens)]
         `(comp/defsc ~sym [~'this ~'props]
            {:initial-state (fn [~'params] (comp/get-initial-state ~first-screen ~'params))
             :ident         ~ident-arg
             :query         (fn [~'this] ~query)}
            (let [page# (first (comp/get-ident ~'this))]
              (case page#
                ~@render-stmt
                (bad-route page#)))))
       (catch Exception e `(def ~sym (log/error "BROKEN ROUTER!"))))))

#?(:clj
   (defn- emit-router [router-id sym union-sym]
     `(comp/defsc ~sym [~'this ~'props ~'computed]
        {:initial-state (fn [~'params] {::id ~router-id ::current-route (comp/get-initial-state ~union-sym ~'params)})
         :ident         (fn [~'this ~'props] [:fulcro.client.routing.routers/by-id ~router-id])
         :query         [::id {::current-route (comp/get-query ~union-sym)}]}
        (let [props#               (::current-route (comp/props ~'this))
              props-with-computed# (comp/computed props# ~'computed)]
          ((comp/factory ~union-sym) props-with-computed#)))))

#?(:clj
   (s/def ::router-args (s/cat
                          :sym symbol?
                          :router-id keyword?
                          :ident-fn (s/or :method list? :template (s/coll-of keyword? :min-count 2 :max-count 2 :kind vector?))
                          :kws-and-screens (s/+ (s/cat :kw keyword? :sym symbol?)))))

(def routing-tree-key ::routing-tree)
(def routers-table :fulcro.client.routing.routers/by-id)    ; NOTE: needed in macro, but hand-coded

(defn make-route
  "Make a route name that executes the provided routing instructions to change which screen in on the UI. routing-instructions
  must be a vector. Returns an item that can be passed to `routing-tree` to generate your overall application's routing
  plan.

  `(make-route :route/a [(router-instruction ...) ...])`

  "
  [name routing-instructions]
  {:pre [(vector? routing-instructions)]}
  {:name name :instructions routing-instructions})

(defn routing-tree
  "Generate initial state for your application's routing tree. The return value of this should be merged into your overall
  app state in your Root UI component

  ```
  (defsc Root [this props]
    {:initial-state (fn [params]  (merge {:child-key (comp/get-initial-state Child)}
                                    (routing-tree
                                      (make-route :route/a [(router-instruction ...)])
                                      ...)))
    ...
  ```
  "
  [& routes]
  {routing-tree-key (reduce (fn [tree {:keys [name instructions]}] (assoc tree name instructions)) {} routes)})

(defn router-instruction
  "Return the definition of a change-route instruction."
  [router-id target-screen-ident]
  {:target-router router-id
   :target-screen target-screen-ident})

(defn current-route
  "Get the current route (an ident) from the router with the given id. You can pass the entire app database, the routers table,
  or the props of a component that has queried for the router table as the first argument to this function.
  Thus, it can be used easily from within a mutation or in a component to find (and display) the current route:

  ```
  (defmutation do-something-with-routes [params]
    (action [{:keys [state]}]
      (let [current (r/current-route state :top-router)]
      ...)))

  (defsc NavBar [this props]
    {:query (fn [] [[r/routers-table '_]])
     :initial-state (fn [params] {})}
    (let [current (r/current-route props :top-router)]
      ...))
  ```
  "
  [state-map-or-router-table router-id]
  (if (contains? state-map-or-router-table routers-table)
    (get-in state-map-or-router-table [routers-table router-id ::current-route])
    (get-in state-map-or-router-table [router-id ::current-route])))

(defmulti coerce-param (fn [param-keyword v] param-keyword))
(defmethod coerce-param :default [k v]
  (cond
    (and (string? v) (seq (re-seq #"^[0-9][0-9]*$" v))) #?(:clj  (Integer/parseInt v)
                                                           :cljs (js/parseInt v))
    (and (string? v) (seq (re-seq #"^[a-zA-Z]" v))) (keyword v)
    :else v))

(defn set-ident-route-params
  "Replace any keywords of the form :params/X with the value of (get route-params :X) in the given ident. By default the value
  of the parameter (which comes in as a string) will be converted to an int if it is all digits, and will be
  converted to a keyword if it is all letters. If you want to customize the coercion, just:

  ```
  (defmethod r/coerce-param :param/NAME [k v] (transform-it v))
  ```
  "
  [ident route-params]
  (mapv (fn [element]
          (if (and (keyword? element) (= "param" (namespace element)))
            (coerce-param element (get route-params (keyword (name element)) element))
            element))
    ident))

(defn set-route*
  "Set the given screen-ident as the current route on the router with the given ID. Returns a new application
  state map."
  [state-map router-id screen-ident]
  (assoc-in state-map [routers-table router-id ::current-route] screen-ident))

(defmutation set-route
  "Mutation: Explicitly set the route of a given router to the target screen ident."
  [{:keys [router target]}]
  (action [{:keys [state]}]
    (swap! state set-route* router target))
  (refresh [_] [::current-route]))

(declare DynamicRouter get-dynamic-router-target)

(defn- set-routing-query
  "Change the given router's query iff it is a dynamic router. Returns the updated state."
  [state app router-id [target-kw _]]
  (let [router   (-> state :fulcro.client.routing.routers/by-id router-id)
        dynamic? (-> router ::dynamic boolean)
        query    (when dynamic?
                   (some-> target-kw
                     get-dynamic-router-target
                     (comp/get-query state)))]
    (if query
      (do
        (when app (app/schedule-render! app))
        (comp/set-query* state (comp/factory DynamicRouter {:qualifier router-id})
          {:query [::id ::dynamic {::current-route query}]}))
      state)))

(defn -update-routing-queries
  "PRIVATE.

  Given the reconciler, state, and a routing tree route: finds and sets all of the dynamic queries needed to
  accomplish that route. Returns the updated state. reconciler can be nil, in which case UI refresh may not
  happen, but that is useful for SSR."
  [state app {:keys [handler route-params]}]
  (let [routing-instructions (get-in state [routing-tree-key handler])]
    (if-not (or (nil? routing-instructions) (vector? routing-instructions))
      (do
        (log/error "Routing tree does not contain a vector of routing-instructions for handler " handler)
        state)
      (reduce (fn [ongoing-state {:keys [target-router target-screen]}]
                (set-routing-query ongoing-state app target-router target-screen))
        state routing-instructions))))

(defn update-routing-links
  "Given the app state map, returns a new map that has the routing graph links updated for the given route/params
  as a bidi match.

  ***This function should only be used if you only use static UI routing.***

  If you use DynamicRouter then you must use `route-to-impl!` instead."
  [state-map {:keys [handler route-params]}]
  (let [routing-instructions (get-in state-map [routing-tree-key handler])]
    (if-not (or (nil? routing-instructions) (vector? routing-instructions))
      (log/error "Routing tree does not contain a vector of routing-instructions for handler " handler)
      (reduce (fn [m {:keys [target-router target-screen]}]
                (let [parameterized-screen-ident (set-ident-route-params target-screen route-params)]
                  (set-route* m target-router parameterized-screen-ident))) state-map routing-instructions))))

(defmulti get-dynamic-router-target
  "Get the component that renders the given screen type. The parameter is simply the keyword of the module/component.
  Note that all have to match: the module name in the compiler that contains the code for the component,
  the first element of the ident returned by the component, and the keyword passed to this multimethod to retrieve
  the component."
  (fn [k] k))

(defmethod get-dynamic-router-target :default [k] nil)

(defn add-route-state [state-map target-kw component]
  (let [tree-state       {:tmp/new-route (comp/get-initial-state component)}
        query            [{:tmp/new-route (comp/get-query component)}]
        normalized-state (-> (fnorm/tree->db query tree-state true (merge/pre-merge-transform state-map))
                           (dissoc :tmp/new-route))]
    (util/deep-merge state-map normalized-state)))

(defn install-route*
  "Implementation of mutation. Useful for SSR setup."
  [state-map target-kw component]
  (add-route-state state-map target-kw component))

(defn- install-route-impl [state target-kw component]
  (defmethod get-dynamic-router-target target-kw [k] component)
  (swap! state add-route-state target-kw component))

(defmutation install-route
  "Fulcro mutation: Install support for a dynamic route. `target-kw` is the keyword that represents the table name of
  the target screen (first elemenet of the ident of the component), which must also match internal data in the
  state of that component at fulcro.client.routing/dynamic-route-key. `component` is the *class* of the UI component that will be
  shown by the router. It *must* implement InitialAppState to provide at least the value of `target-kw` at the
  predefined fulcro.client.routing/dynamic-route-key key.

  An example would be that you've defined a component like this:

  (ns app.component
    (:require fulcro.client.routing))

  (def target-kw :my-component)

  (defsc Component [this props]
    {:initial-state (fn [p] {fulcro.client.routing/dynamic-route-key target-kw})
     :ident (fn [this props] [target-kw :singleton])}
    ...)

  and during startup you install this route as:

  (transact! this `[(install-route {:target-kw :my-component :component Component})])
  "
  [{:keys [target-kw component]}]
  (action [{:keys [state]}]
    (install-route-impl state target-kw component)))

(def dynamic-route-key ::dynamic-route)

(defsc DynamicRouter [this props]
  {:initial-state (fn [{:keys [id]}] {::id id ::dynamic true ::current-route {}})
   :ident         (fn [] [routers-table (::id props)])
   :query         (fn [] [::id ::dynamic ::current-route])}
  (let [{:keys [::id ::current-route]} (comp/props this)
        target-key (get current-route dynamic-route-key)
        c          (get-dynamic-router-target target-key)
        factory    (when c (comp/factory c {:keyfn dynamic-route-key :qualifier id}))]
    (when factory
      (factory current-route))))

(defn ui-dynamic-router [props]
  (let [ui-factory (comp/factory DynamicRouter {:qualifier (get props ::id) :keyfn ::id})]
    (ui-factory props)))

(defn get-dynamic-router-query
  "Get the query for the router with the given router-id."
  [router-id]
  (comp/get-query (comp/factory DynamicRouter {:qualifier router-id})))

(defn -process-pending-route!
  "Finish doing the routing after a module completes loading"
  [{:keys [state app] :as env}]
  (let [target (::pending-route @state)]
    (swap! state
      (fn [s]
        (cond-> (dissoc s ::pending-route)
          :always (-update-routing-queries app target)
          (contains? target :handler) (update-routing-links target))))))

(defn -route-target-missing?
  "Returns true iff the given ident has no component loaded into the dynamic routing multimethod."
  [ident]
  (let [screen (first ident)
        c      (get-dynamic-router-target screen)]
    (nil? c)))

(defn -is-dynamic-router?
  "Returns true if the given component (instance) is a DynamicRouter."
  [component]
  (instance? DynamicRouter component))

(defn -get-missing-routes
  "Returns a sequence of routes that need to be loaded in order for routing to succeed."
  [app state-map {:keys [handler route-params] :as params}]
  #?(:clj  []
     :cljs (let [routing-instructions (get-in state-map [routing-tree-key handler])]
             (if-not (or (nil? routing-instructions) (vector? routing-instructions))
               (do
                 (log/error "Routing tree does not contain a vector of routing-instructions for handler " handler)
                 [])
               (reduce
                 (fn [routes {:keys [target-router target-screen]}]
                   (let [router (comp/ident->any app [routers-table target-router])]
                     (if (and (-is-dynamic-router? router) (-route-target-missing? target-screen))
                       (conj routes (first target-screen))
                       routes)))
                 []
                 routing-instructions)))))

(defn -load-dynamic-route
  "Triggers the actual load of a route, and retries if the networking is down. If the pending route (in state) has changed
  between retries, then no further retries will be attempted. Exponential backoff with a 10 second max is used as long
  as retries are being done."
  ([state-atom pending-route-handler route-to-load finish-fn]
   (-load-dynamic-route state-atom pending-route-handler route-to-load finish-fn 0 0))
  ([state-atom pending-route-handler route-to-load finish attempt delay]
   #?(:cljs (js/setTimeout
              (fn []
                (let [current-pending-route (get @state-atom ::pending-route)]
                  (when (and pending-route-handler (= current-pending-route pending-route-handler))
                    ; if the load succeeds, finish will be called to finish the route instruction
                    (let [deferred-result (loader/load route-to-load)
                          ;; see if the route is no longer needed (pending has changed)
                          next-delay      (min 10000 (* 2 (max 1000 delay)))]
                      ; if the load fails, retry
                      (.addCallback deferred-result finish)
                      (.addErrback deferred-result
                        (fn [_]
                          (log/error (str "Route load failed for " route-to-load ". Attempting retry."))
                          ; TODO: We're tracking attempts..but I don't see a reason to stop trying if the route is still pending...
                          (-load-dynamic-route state-atom pending-route-handler route-to-load finish (inc attempt) next-delay)))))))
              delay))))

(defn -load-routes [{:keys [state] :as env} routes]
  #?(:clj (log/info "Dynamic loading of routes is not done on the server itself.")
     :cljs
          (let [loaded        (atom 0)
                pending-route (get @state ::pending-route)
                to-load       (count routes)
                finish        (fn [k]
                                (fn []
                                  (swap! loaded inc)
                                  (when (= @loaded to-load)
                                    (swap! state add-route-state k (get-dynamic-router-target k))
                                    (-process-pending-route! env))))]
            (doseq [r routes]
              (-load-dynamic-route state pending-route r (finish r))))))

(defn route-to-impl!
  "Mutation implementation, for use as a composition into other mutations. This function can be used
  from within mutations. If a DynamicRouter is used in your routes, then this function may trigger
  code loading. Once the loading is complete (if any is needed), it will trigger the actual UI routing.

  If routes are being loaded, then the root property in your app state :fulcro.client.routing/pending-route
  will be your `bidi-match`. You can use a link query to pull this into your UI to show some kind of indicator.

  NOTE: this function updates application state and *must not* be used from within a swap on that state."
  [{:keys [state app] :as env} bidi-match]
  (if-let [missing-routes (seq (-get-missing-routes app @state bidi-match))]
    (if (= bidi-match (get @state ::pending-route))
      ; TODO: This could be the user clicking again, or a legitimate failure...Not much more I can do yet.
      (log/error "Attempt to trigger a route that was pending, but that wasn't done loading (or failed to load).")
      (do
        (swap! state assoc ::pending-route bidi-match)
        (-load-routes env missing-routes)))
    (do
      (swap! state #(-> %
                      (-update-routing-queries app bidi-match)
                      (dissoc ::pending-route)
                      (update-routing-links bidi-match))))))

(defn route-to*
  "Implementation of routing tree data manipulations on app state. Returns an updated app state.

  WARNING: This function will not trigger dynamic module loading, as it is
  only responsible for returning a state-map that has been set (as far as is possible) to the given route. You typically
  do *not* want to use this on a client, but exists a separate function for server-side rendering to be easily able
  to route, since no dynamic code loading will be needed."
  [state-map bidi-match]
  (-> state-map
    (-update-routing-queries nil bidi-match)
    (dissoc ::pending-route)
    (update-routing-links bidi-match)))

(m/defmutation route-to
  "Mutation (use in transact! only):

  Change the application's overall UI route to the given route by handler. Handler must be a single keyword that
  indicates an entry in your routing tree (which must be in the initial app state of your UI root). route-params
  is a map of key-value pairs that will be substituted in the target screen idents of the routing tree.

  If any of the routers are dynamic, then this mutation will check to see if the target routes are loaded. If any
  are not present, then module load(s) will be triggered for them, and the route will be pending until the code arrives.

  If a new route-to is run before pending routes are installed, then the pending route will be cancelled, but the code
  loading will continue.

  You may use a link query to get [:fulcro.client.routing/pending-route '_] in your application. If it is not nil
  then a route is pending, and you can show UI indicators of this.

  Server-side rendering should require all dynamic portions of the UI and use `route-to*`."
  [{:keys [handler route-params] :as params}]
  (action [env]
    (try
      (route-to-impl! env params)
      (catch #?(:clj Throwable :cljs :default) t
        (log/error "Routing failed!" t)))))

#?(:clj
   (defn compile-error [env form message ex]
     (throw (ana/error (merge env (some-> form meta)) message ex))))

#?(:clj
   (defn- defsc-router-union-element* [env sym arglist {:keys [ident router-targets default-route] :as options} bad-route-render]
     (when-not default-route (compile-error env options "`:default-route Class` is required in options." nil))
     (let [this-target      (first arglist)
           props-target     (second arglist)
           screen-render    (fn [cls] `((comp/factory ~cls) ~'props))
           query            (reduce (fn [q [kw sym]] (assoc q kw `(comp/get-query ~sym))) {} router-targets)
           query-fn         (apply list `(~'fn [] ~query))
           initial-state-fn (apply list `(~'fn [~'params] (comp/get-initial-state ~default-route ~'params)))
           render-cases     (reduce
                              (fn [cases [kw sym]]
                                (-> cases
                                  (conj kw (screen-render sym))))
                              []
                              router-targets)]
       `(comp/defsc ~sym [~'this ~props-target]
          {:initial-state ~initial-state-fn
           :query         ~query-fn
           :ident         ~ident}
          (let [~'props (comp/props ~'this)
                page# (first (comp/get-ident ~'this ~'props))]
            (case page#
              ~@render-cases
              (let [~this-target ~'this]
                ~@bad-route-render)))))))

#?(:clj (s/def ::router-targets (s/map-of keyword? (s/or :sym symbol? :dflt list?))))
#?(:clj (s/def ::ident list?))
#?(:clj (s/def ::router-id any?))
#?(:clj (s/def ::default-route symbol?))
#?(:clj (s/def ::defsc-router-options (s/keys :req-un [::router-targets ::ident ::router-id ::default-route])))

#?(:clj
   (defn defsc-router-router-element* [env router-sym union-sym arglist options]
     (when-not (contains? options :router-id)
       (compile-error env options ":router-id is required in optoins." nil))
     (let [{:keys [router-id]} options
           this-sym          (first arglist)
           union-factory-sym (symbol (str "ui-" (name router-sym) "-Union"))
           initial-state     (list `fn '[params] {::id router-id ::current-route `(comp/get-initial-state ~union-sym ~'params)})
           ident             (list `fn '[] [:fulcro.client.routing.routers/by-id router-id])
           query-fn          (list `fn '[] [::id {::current-route `(comp/get-query ~union-sym)}])
           options           (merge
                               (dissoc options :router-targets :router-id)
                               `{:initial-state ~initial-state
                                 :ident         ~ident
                                 :query         ~query-fn})]
       (when-not (symbol? this-sym)
         (compile-error env arglist "'this' argument MUST be a symbol." nil))
       `(comp/defsc ~router-sym ~arglist
          ~options
          (let [computed#            (comp/get-computed ~this-sym)
                props#               (::current-route (comp/props ~this-sym))
                props-with-computed# (comp/computed props# computed#)]
            (~union-factory-sym props-with-computed#))))))

#?(:clj
   (defn defsc-router* [env router-sym arglist options body]
     (when-not (and (vector? arglist) (<= 2 (count arglist)))
       (compile-error env options "defsc-router argument list must have entries for this and props." nil))
     (when-not (map? options)
       (compile-error env options "defsc-router requires a literal map of options." nil))
     (when-not (s/valid? ::defsc-router-options options)
       (compile-error env options (str "defsc-router options are invalid:\n" (s/explain-str ::defsc-router-options options)) nil))
     (let [union-sym         (symbol (str (name router-sym) "-Union"))
           union-factory-sym (symbol (str "ui-" (name router-sym) "-Union"))
           union-component   (defsc-router-union-element* env union-sym arglist options body)
           union-factory     `(def ~union-factory-sym (comp/factory ~union-sym))
           router-component  (defsc-router-router-element* env router-sym union-sym arglist options)]
       `(do
          ~union-component
          ~union-factory
          ~router-component))))

#?(:clj
   (defmacro
     ^{:doc
       "Define a router component.

       This is just like `defsc`, BUT generates a pair of components that optimize query performance and allow for efficient
       UI changes.  The options are identical to `defsc`, except:

       Required Options:

       - `:router-id` - An ID for the router
       - `:ident` - An ident that works across all of the routing targets. This kind of router generates a union query,
                    so this ident function must work on ALL router targets, and MUST vary the FIRST elements of the ident
                    to identiy which screen to show.
       - `:default-route` - The Class of the router target that is the default (initial) route.
       - `:router-targets` - A map of ident tables to router targets.  This map MUST correspond to the TABLE name that
       the router target lives in, and the Class of the router target component.

       You may NOT define a `:query` or `:initial-state` for a router.

       All other `defsc` options are supported.

        ```
        (defsc-router TopRouter [this props]
          {
           ;; REQUIRED for router:
           :router-id :top-router
           :ident (fn [this props] [(:table props) (:id props)]
           :router-targets  {:A A :B B :C C}
           :default-route A

           :css [] ; garden css rules
           :css-include [] ; list of components that have CSS to compose towards root.

           ; React Lifecycle Methods (this in scope)
           :initLocalState            (fn [this] ...) ; CAN BE used to call things as you might in a constructor. Return value is initial state.
           :shouldComponentUpdate     (fn [this next-props next-state] ...)

           :componentDidUpdate        (fn [this prev-props prev-state snapshot] ...) ; snapshot is optional, and is 16+. Is context for 15
           :componentDidMount         (fn [this ] ...)
           :componentWillUnmount      (fn [this ] ...)

           ;; DEPRECATED IN REACT 16 (to be removed in 17):
           :componentWillReceiveProps        (fn [this next-props] ...)
           :componentWillUpdate              (fn [this next-props next-state] ...)
           :componentWillMount               (fn [this ] ...)

           ;; Replacements for deprecated methods in React 16.3+
           :UNSAFE_componentWillReceiveProps (fn [this next-props] ...)
           :UNSAFE_componentWillUpdate       (fn [this next-props next-state] ...)
           :UNSAFE_componentWillMount        (fn [this ] ...)

           ;; ADDED for React 16:
           :componentDidCatch         (fn [this error info] ...)
           :getSnapshotBeforeUpdate   (fn [prevProps prevState] ...)
           :getDerivedStateFromProps  (fn [props state] ...)
        ```
        "}
     defsc-router [sym arglist options & body]
     (defsc-router* &env sym arglist options body)))

