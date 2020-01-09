(ns com.fulcrologic.fulcro.routing.dynamic-routing
  #?(:cljs (:require-macros [com.fulcrologic.fulcro.routing.dynamic-routing]))
  (:require
    [com.fulcrologic.guardrails.core :refer [>fdef => ?]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]
    [clojure.spec.alpha :as s]
    #?(:clj [cljs.analyzer :as ana])
    [com.fulcrologic.fulcro.algorithms.indexing :as indexing]))

(declare route-immediate)

(defn route-segment
  "Returns a vector that describes the sub-path that a given route target represents. String elements represent
  explicit path elements, and keywords represent variable values (which are always pulled as strings)."
  [class]
  (comp/component-options class :route-segment))

(defn get-route-cancelled
  "Returns the function that should be called if this target was in a deferred state and a different routing choice was made. Is given the same route parameters that were sent to `will-enter`."
  [class]
  (comp/component-options class :route-cancelled))

(defn route-cancelled
  "Universal CLJC version of will-enter.  Don't use the protocol method in CLJ."
  [class route-params]
  (when-let [f (get-route-cancelled class)]
    (f route-params)))

(defn get-will-enter
  "Returns the function that is called before a route target is activated (if the route segment of interest has changed and the
  target of the result is this target).  MUST return (r/route-immediate ident) or (r/route-deferred ident) to indicate
  what ident should be used in app state to connect the router's join.  If deferred, the router must cause a call to
  the r/target-ready mutation (or use the target-ready* mutation helper) with a {:target ident} parameter to indicate
  that the route target is loaded and ready for display.

  `params` will be a map from any keywords found in `route-segment` to the string value of that path element.

  WARNING: This method MUST be side-effect free."
  [class]
  (if-let [will-enter (comp/component-options class :will-enter)]
    will-enter
    (let [ident (comp/get-ident class {})]
      (fn [_ _] (route-immediate ident)))))

(defn will-enter
  "Universal CLJC version of will-enter."
  [class app params]
  (when-let [will-enter (get-will-enter class)]
    (will-enter app params)))

(defn route-target? [component] (boolean (comp/component-options component :route-segment)))

;; NON-static protocol for interacting as a route target
(defn get-will-leave
  "Returns the function of a route target to be called with
  the current component and props. If it returns `true` then the routing operation will continue.  If it returns `false`
  then whatever new route was requested will be completely abandoned.  It is the responsibility of this method to give
  UI feedback as to why the route change was aborted."
  [this]
  (or (comp/component-options this :will-leave) (constantly true)))

(defn will-leave [c props]
  (when-let [f (get-will-leave c)]
    (f c props)))

(defn route-lifecycle? [component] (boolean (comp/component-options component :will-leave)))

(defn get-targets
  "Returns a set of classes to which this router routes."
  [router]
  (set (comp/component-options router :router-targets)))

(defn route-immediate [ident] (with-meta ident {:immediate true}))
(defn route-deferred [ident completion-fn] (with-meta ident {:immediate false
                                                             :fn        completion-fn}))
(defn immediate? [ident] (some-> ident meta :immediate))

(defn- apply-route* [state-map {:keys [router target]}]
  (let [router-class (-> router meta :component)
        router-id    (second router)
        target-class (-> target meta :component)]
    (log/debug "Applying route ident" target "to router" router-id)
    (when (nil? router-class)
      (log/error "apply-route* was called without a proper :router argument."))
    (when (nil? target-class)
      (log/error "apply-route* for router " router-class "was given a target that did not have a component. "
        "Did you remember to call route-deferred or route-immediate?"))
    (-> state-map
      (assoc-in (conj router ::current-route) target)
      (update-in router dissoc ::pending-route)
      (comp/set-query* router-class {:query [::id [::uism/asm-id router-id] {::current-route (comp/get-query target-class state-map)}]}))))

(defn router-for-pending-target [state-map target]
  (let [routers   (some-> state-map ::id vals)
        router-id (reduce (fn [_ r]
                            (when (= target (some-> r ::pending-route :target))
                              (reduced (::id r))))
                    nil
                    routers)]
    router-id))

(defmutation target-ready
  "Mutation: Indicate that a target is ready."
  [{:keys [target]}]
  (action [{:keys [app]}]
    (let [state-map (app/current-state app)
          router-id (router-for-pending-target state-map target)]
      (if router-id
        (do
          (log/debug "Router" router-id "notified that pending route is ready.")
          (uism/trigger! app router-id :ready!))
        (log/error "dr/target-ready! was called but there was no router waiting for the target listed: " target
          "This could mean you sent one ident, and indicated ready on another."))))
  (refresh [_] [::current-route]))

(defn target-ready!
  "Indicate a target is ready.  Safe to use from within mutations.

  target - The ident that was originally listed as a deferred target."
  [component-or-app target]
  (comp/transact! component-or-app [(target-ready {:target target})]))

(defn router? [component] (boolean (comp/component-options component :router-targets)))

(defn matching-prefix
  "Returns the elements of actual-path that match the route-segment definition."
  [route-segment actual-path]
  (let [matching-segment (reduce
                           (fn [result [expected actual]]
                             (cond
                               (and (string? expected) (= expected actual))
                               (conj result actual)

                               (and (keyword? expected) (seq (str actual)))
                               (conj result (str actual))

                               :otherwise result))
                           []
                           (map (fn [a b] [a b]) route-segment actual-path))]
    (when (= (count matching-segment) (count route-segment))
      matching-segment)))

(defn current-route-class
  "Get the class of the component that is currently being routed to."
  [this]
  (let [state-map (comp/component->state-map this)
        class     (some->> (comp/get-query this state-map) eql/query->ast :children
                    (filter #(= ::current-route (:key %))) first :component)
        ;; Hot code reload support to avoid getting the cached class from old metadata
        class     (if #?(:cljs goog.DEBUG :clj false)
                    (-> class comp/class->registry-key comp/registry-key->class)
                    class)]

    class))

(defn route-target
  "Given a router class and a path segment, returns the class of the router-class that is the target of the given URI path,
  which is a vector of (string) URI components.

  Returns nil if there is no target that accepts the path, or a map containing:

  {:target class
   :matching-prefix prefix}

  where `class` is the component class that accepts the path (the target, NOT the router), and `matching-prefix` is the
  portion of the path that is accepted by that class.

  NOTE: If more than one target matches, then the target with the longest match will be returned. A warning will be
  printed if more than one match of equal length is found.
  "
  [router-class path]
  (when (and router-class (router? router-class))
    (let [targets    (get-targets router-class)
          matches    (->> (reduce (fn [result target-class]
                                    (let [prefix (and target-class (route-target? target-class)
                                                   (some-> target-class (route-segment) (matching-prefix path)))]
                                      (if (and prefix (seq prefix))
                                        (conj result {:length          (count prefix)
                                                      :matching-prefix prefix
                                                      :target          target-class})
                                        result))) [] targets)
                       (sort-by :length)
                       reverse)
          max-length (some-> matches first :length)
          match      (filter #(= max-length (:length %)) matches)]
      (when (second match)
        (log/warn "More than one route target matches" path))
      (first match))))

(defn accepts-route?
  "Returns true if the given component is a router that manages a route target that will accept the given path."
  [component path]
  (boolean (route-target component path)))

(defn ast-node-for-route
  "Returns the AST node for a query that represents the router that has a target that can accept the given path. This is a breadth-first
  search.

  ast - A query AST node
  path - A vector of the current URI segments.

  Returns an AST node or nil if none is found."
  [{:keys [component children] :as ast-node} path]
  (or
    (and (accepts-route? component path) ast-node)
    (some #(and (accepts-route? (:component %) path) %) children)
    (some #(ast-node-for-route % path) children)))

(defn ast-node-for-live-router
  "Returns the AST node for a query that represents the closest \"live\" (on-screen) router

  ast - A query AST node

  Returns an AST node or nil if none is found."
  [app {:keys [component children] :as ast-node}]
  (letfn [(live-router? [c] (and (router? c) (boolean (comp/class->any app c))))]
    (or
      (and (live-router? component) ast-node)
      (some #(and (live-router? (:component %)) %) children)
      (some #(ast-node-for-live-router app %) children))))


(defmutation apply-route
  "Mutation: Indicate that a given route is ready and should show the result.

  router - The ident of the router, with metadata :component that is the class of the router.
  target - The ident of the target route, with metadata :component that is the class of the target."
  [{:keys [router target] :as params}]
  (action [{:keys [app state]}]
    (swap! state apply-route* params)))

(defn mark-route-pending* [state-map {:keys [router target] :as params}]
  (assoc-in state-map (conj router ::pending-route) params))

(letfn [(target-ready*
          [state-map target]
          (let [router-id (router-for-pending-target state-map target)]
            (if router-id
              (apply-route* state-map (get-in state-map [::id router-id ::pending-route]))
              state-map)))]
  (defn ready-handler [env]
    (let [new-env (-> env
                    (uism/store :path-segment (uism/retrieve env :pending-path-segment))
                    (uism/store :pending-path-segment [])
                    (uism/apply-action target-ready* (uism/retrieve env :target)))
          app     (::uism/app env)]
      (when app
        (comp/transact! app [(indexing/reindex)]))
      new-env)))

(defn fail-handler [env] env)

(defn route-handler [{::uism/keys [app event-data] :as env}]
  (let [{:keys [router target error-timeout deferred-timeout path-segment] :or {error-timeout 5000 deferred-timeout 100}} event-data
        immediate? (immediate? target)]
    (-> (if immediate?
          (let [new-env (-> env
                          (uism/store :path-segment path-segment)
                          (uism/apply-action apply-route* event-data)
                          (uism/activate :routed))]
            (when app
              (comp/transact! app [(indexing/reindex)]))
            new-env)
          (-> env
            (uism/store :pending-path-segment path-segment)
            (uism/apply-action mark-route-pending* event-data)
            (uism/set-timeout :error-timer :timeout! {} error-timeout #{:ready! :route!})
            (uism/set-timeout :delay-timer :waiting! {} deferred-timeout #{:ready! :route!})
            (uism/activate :deferred)))
      (uism/store :target target))))

(defstatemachine RouterStateMachine
  {::uism/actors
   #{:router}

   ::uism/aliases
   {:current-route [:router ::current-route]
    :state         [:router ::current-state]}

   ::uism/states
   {:initial  {::uism/handler route-handler}

    :deferred {::uism/events
               {:waiting! {::uism/target-state :pending}
                :route!   {::uism/handler route-handler}
                :ready!   {::uism/target-state :routed
                           ::uism/handler      ready-handler}
                :timeout! {::uism/target-state :failed
                           ::uism/handler      fail-handler}}}

    :pending  {::uism/events
               {:waiting! {::uism/target-state :pending}
                :route!   {::uism/handler route-handler}
                :ready!   {::uism/target-state :routed
                           ::uism/handler      ready-handler}
                :timeout! {::uism/target-state :failed
                           ::uism/handler      fail-handler}}}

    ;; failed may potentially resolve (just very late), so it must accept ready! events
    :failed   {::uism/events
               {:route! {::uism/handler route-handler}
                :ready! {::uism/target-state :routed
                         ::uism/handler      ready-handler}}}

    :routed   {::uism/events {:route! {::uism/handler route-handler}}}}})

;; TODO: This algorithm is repeated in more than one place in slightly different forms...refactor it.
(defn proposed-new-path [this-or-app relative-class-or-instance new-route]
  (let [app        (comp/any->app this-or-app)
        state-map  (app/current-state app)
        router     relative-class-or-instance
        root-query (comp/get-query router state-map)
        ast        (eql/query->ast root-query)
        root       (ast-node-for-route ast new-route)
        result     (atom [])]
    (loop [{:keys [component]} root path new-route]
      (when (and component (router? component))
        (let [{:keys [target matching-prefix]} (route-target component path)
              target-ast     (some-> target (comp/get-query state-map) eql/query->ast)
              prefix-length  (count matching-prefix)
              remaining-path (vec (drop prefix-length path))
              segment        (route-segment target)
              params         (reduce
                               (fn [p [k v]] (if (keyword? k) (assoc p k v) p))
                               {}
                               (map (fn [a b] [a b]) segment matching-prefix))
              target-ident   (will-enter target app params)]
          (when (or (not (eql/ident? target-ident)) (nil? (second target-ident)))
            (log/error "will-enter for router target" (comp/component-name target) "did not return a valid ident. Instead it returned: " target-ident))
          (when (and (eql/ident? target-ident)
                  (not (contains? (some-> target-ident meta) :immediate)))
            (log/error "will-enter for router target" (comp/component-name target) "did not wrap the ident in route-immediate or route-deferred."))
          (when (vector? target-ident)
            (swap! result conj (vary-meta target-ident assoc :component target :params params)))
          (when (seq remaining-path)
            (recur (ast-node-for-route target-ast remaining-path) remaining-path)))))
    @result))

(defn signal-router-leaving
  "Tell active routers that they are about to leave the screen. Returns false if any of them deny the route change."
  [app-or-comp relative-class-or-instance new-route]
  (let [new-path   (proposed-new-path app-or-comp relative-class-or-instance new-route)
        app        (comp/any->app app-or-comp)
        state-map  (app/current-state app)
        router     relative-class-or-instance
        root-query (comp/get-query router state-map)
        ast        (eql/query->ast root-query)
        root       (ast-node-for-live-router app ast)
        to-signal  (atom [])
        to-cancel  (atom [])
        _          (loop [{:keys [component children] :as node} root new-path-remaining new-path]
                     (when (and component (router? component))
                       (let [new-target    (first new-path-remaining)
                             router-ident  (comp/get-ident component {})
                             active-target (get-in state-map (conj router-ident ::current-route))
                             {:keys [target]} (get-in state-map (conj router-ident ::pending-route))
                             next-router   (some #(ast-node-for-live-router app %) children)]
                         (when (eql/ident? target)
                           (swap! to-cancel conj target))
                         (when (and (not= new-target active-target) (vector? active-target))
                           (let [mounted-target-class (reduce (fn [acc {:keys [dispatch-key component]}]
                                                                (when (= ::current-route dispatch-key)
                                                                  (reduced component)))
                                                        nil
                                                        (some-> component (comp/get-query state-map)
                                                          eql/query->ast :children))
                                 mounted-targets      (comp/class->all app mounted-target-class)]
                             (when (and #?(:cljs goog.DEBUG :clj true) (> (count mounted-targets) 1))
                               (log/error "More than one route target on screen of type" mounted-target-class))
                             (when (seq mounted-targets)
                               (swap! to-signal into mounted-targets))))
                         (when next-router
                           (recur next-router (rest new-path-remaining))))))
        components (reverse @to-signal)
        result     (atom true)]
    (doseq [c components]
      (swap! result #(and % (will-leave c (comp/props c)))))
    (when @result
      (doseq [t @to-cancel]
        (let [{:keys [component params]} (some-> t meta)]
          (route-cancelled component params))))
    @result))

(defn change-route-relative
  "Change the route, starting at the given Fulcro class or instance (scanning for the first router from there).  `new-route` is a vector
  of string components to pass through to the nearest child router as the new path. The first argument is any live component
  or the app.  The `timeouts` are as in `change-route`.
  It is safe to call this from within a mutation."
  ([this-or-app relative-class-or-instance new-route]
   (change-route-relative this-or-app relative-class-or-instance new-route {}))
  ([app-or-comp relative-class-or-instance new-route timeouts]
   (when (and #?(:clj true :cljs goog.DEBUG) (not (seq (proposed-new-path app-or-comp relative-class-or-instance new-route))))
     (log/error "Could not find route targets for new-route" new-route))
   (if (signal-router-leaving app-or-comp relative-class-or-instance new-route)
     (let [app        (comp/any->app app-or-comp)
           state-map  (app/current-state app)
           router     relative-class-or-instance
           root-query (comp/get-query router state-map)
           ast        (eql/query->ast root-query)
           root       (ast-node-for-route ast new-route)]
       (loop [{:keys [component]} root path new-route]
         (when (and component (router? component))
           (let [{:keys [target matching-prefix]} (route-target component path)
                 target-ast        (some-> target (comp/get-query state-map) eql/query->ast)
                 prefix-length     (count matching-prefix)
                 remaining-path    (vec (drop prefix-length path))
                 segment           (route-segment target)
                 params            (reduce
                                     (fn [p [k v]] (if (keyword? k) (assoc p k v) p))
                                     {}
                                     (map (fn [a b] [a b]) segment matching-prefix))
                 router-ident      (comp/get-ident component {})
                 router-id         (-> router-ident second)
                 target-ident      (will-enter target app params)
                 completing-action (or (some-> target-ident meta :fn) (constantly true))
                 event-data        (merge
                                     {:error-timeout 5000 :deferred-timeout 100}
                                     timeouts
                                     {:path-segment matching-prefix
                                      :router       (vary-meta router-ident assoc :component component)
                                      :target       (vary-meta target-ident assoc :component target :params params)})]
             (if-not (uism/get-active-state app router-id)
               (uism/begin! app-or-comp RouterStateMachine router-id
                 {:router (uism/with-actor-class router-ident component)}
                 event-data)
               (uism/trigger! app router-id :route! event-data))
             (completing-action)
             (when (seq remaining-path)
               (recur (ast-node-for-route target-ast remaining-path) remaining-path))))))
     (log/debug "Route request cancelled by on-screen target."))))

(defn change-route
  "Trigger a route change.

  this - The component (or app) that is causing the route change.
  new-route - A vector of URI components to pass to the router.
  timeouts - A map of timeouts that affect UI during deferred routes: {:error-timeout ms :deferred-timeout ms}

  The error timeout is how long to wait  (default 5000ms) before showing the error-ui of a route (which must be defined on the
  router that is having problems).  The deferred-timeout (default 100ms) is how long to wait before showing the loading-ui of
  a deferred router (to prevent flicker).
  "
  ([this new-route]
   (change-route this new-route {}))
  ([this new-route timeouts]
   (let [app  (comp/any->app this)
         root (app/root-class app)]
     (change-route-relative app root new-route timeouts))))

(defn current-route
  "Returns the current active route, starting from the relative Fulcro class or instance.

  Any component using this as a basis for rendering will need to add the following to their query to
  ensure the props of that component change on route changes:

  ```
  [::uism/asm-id fq-router-kw]
  ```

  where `fq-router-kw` is a keyword that has the exact namespace and name of the router you're interested in. If you want
  to just over-render you can use a quoted `_` instead.
  "
  [this-or-app relative-class-or-instance]
  (let [app        (comp/any->app this-or-app)
        state-map  (app/current-state app)
        router     relative-class-or-instance
        root-query (comp/get-query router state-map)
        ast        (eql/query->ast root-query)
        root       (or (ast-node-for-live-router app ast)
                     (-> ast :children first))
        result     (atom [])]
    (loop [{:keys [component] :as node} root]
      (when (and component (router? component))
        (let [router-ident (comp/get-ident component {})
              router-id    (-> router-ident second)
              sm-env       (uism/state-machine-env state-map nil router-id :none {})
              path-segment (uism/retrieve sm-env :path-segment)
              next-router  (some #(ast-node-for-live-router app %) (:children node))]
          (when (seq path-segment)
            (swap! result into path-segment))
          (when next-router
            (recur next-router)))))
    @result))

#?(:clj
   (defn compile-error [env form message]
     (throw (ana/error (merge env (some-> form meta)) message {}))))

#?(:clj (s/def ::router-targets (s/coll-of symbol? :type vector?)))
#?(:clj (s/def ::initial-ui list?))
#?(:clj (s/def ::loading-ui list?))
#?(:clj (s/def ::failed-ui list?))
#?(:clj (s/def ::defrouter-options (s/keys :req-un [::router-targets] :opt-un [::initial-ui ::loading-ui ::failed-ui])))

(defn validate-route-targets
  "Run a runtime validation on route targets to verify that they at least declare a route-segment that is a vector."
  [router-instance]
  (doseq [t (get-targets router-instance)
          :let [segment (route-segment t)
                valid?  (and
                          (vector? segment)
                          (not (empty? segment))
                          (every? #(or (keyword? %) (string? %)) segment))]]
    (when-not valid?
      (log/error "Route target "
        (comp/component-name t)
        "of router"
        (comp/component-name router-instance)
        "does not declare a valid :route-segment. Route segments must be non-empty vector that contain only strings"
        "and keywords"))))

#?(:clj
   (defn defrouter* [env router-ns router-sym arglist options body]
     (when-not (and (vector? arglist) (= 2 (count arglist)))
       (compile-error env options "defrouter argument list must have an entry for this and props."))
     (when-not (map? options)
       (compile-error env options "defrouter requires a literal map of options."))
     (when-not (s/valid? ::defrouter-options options)
       (compile-error env options (str "defrouter options are invalid: " (s/explain-str ::defrouter-options options))))
     (let [{:keys [router-targets]} options
           _                      (when (empty? router-targets)
                                    (compile-error env options "defrouter requires at least one router-target"))
           id                     (keyword router-ns (name router-sym))
           query                  (into [::id
                                         [::uism/asm-id id]
                                         {::current-route `(comp/get-query ~(first router-targets))}]
                                    (map-indexed
                                      (fn [idx s]
                                        (when (nil? s)
                                          (compile-error env options "defrouter target contains nil!"))
                                        {(keyword (str "alt" idx)) `(comp/get-query ~s)})
                                      (rest router-targets)))
           initial-state-map      (into {::id            id
                                         ::current-route `(comp/get-initial-state ~(first router-targets) ~'params)}
                                    (map-indexed
                                      (fn [idx s] [(keyword (str "alt" idx)) `(comp/get-initial-state ~s {})])
                                      (rest router-targets)))
           ident-method           (apply list `(fn [] [::id ~id]))
           get-targets-method     (apply list `(fn [~'c] ~(set router-targets)))
           initial-state-lambda   (apply list `(fn [~'params] ~initial-state-map))
           states-to-render-route (if (seq body)
                                    #{:routed :deferred}
                                    `(constantly true))
           render-cases           (apply list `(let [~'class (current-route-class ~'this)]
                                                 (if (~states-to-render-route ~'current-state)
                                                   (when ~'class
                                                     (let [~'factory (comp/factory ~'class)]
                                                       (~'factory (comp/computed ~'current-route (comp/get-computed ~'this)))))
                                                   (let [~(first arglist) ~'this
                                                         ~(second arglist) {:pending-path-segment ~'pending-path-segment
                                                                            :route-props          ~'current-route
                                                                            :route-factory        (when ~'class (comp/factory ~'class))
                                                                            :current-state        ~'current-state}]
                                                     ~@body))))
           options                (merge
                                    `{:componentDidMount (fn [this#] (validate-route-targets this#))}
                                    options
                                    `{:query         ~query
                                      :ident         ~ident-method
                                      :initial-state ~initial-state-lambda})]
       `(comp/defsc ~router-sym [~'this {::keys [~'id ~'current-route] :as ~'props}]
          ~options
          (let [~'current-state (uism/get-active-state ~'this ~id)
                ~'state-map (comp/component->state-map ~'this)
                ~'sm-env (uism/state-machine-env ~'state-map nil ~id :fake {})
                ~'pending-path-segment (uism/retrieve ~'sm-env :pending-path-segment)]
            ~render-cases)))))

#?(:clj
   (defmacro defrouter
     "Define a router.

     The arglist is `[this props]`, which are just like defsc. The props will contains :current-state and :pending-path-segment.

     The options are:

     `:router-targets` - (REQUIRED) A *vector* of ui components that are router targets. The first one is considered the \"default\".
     Other defsc options - (LIMITED) You may not specify query/initial-state/protocols/ident, but you can define things like react
     lifecycle methods. See defsc.

     The optional body, if defined, will *only* be used if the router is in one of the following states:

     - `:initial` - No route is set.
     - `:pending` - A deferred route is taking longer than expected (configurable timeout, default 100ms)
     - `:failed` - A deferred route took longer than can reasonably be expected (configurable timeout, default 5s)

     otherwise the actual active route target will be rendered.
     "
     [router-sym arglist options & body]
     (defrouter* &env (str (ns-name *ns*)) router-sym arglist options body)))

#?(:clj
   (s/fdef defrouter
     :args (s/cat :sym symbol? :arglist vector? :options map? :body (s/* any?))))

(defn all-reachable-routers
  "Returns a sequence of all of the routers reachable in the query of the app."
  [state-map component-class]
  (let [root-query  (comp/get-query component-class state-map)
        {:keys [children]} (eql/query->ast root-query)
        get-routers (fn get-routers* [nodes]
                      (reduce
                        (fn [acc {:keys [component children]}]
                          (into (if (router? component)
                                  (conj acc component)
                                  acc)
                            (get-routers* children)))
                        []
                        nodes))]
    (get-routers children)))


(defn initialize!
  "Initialize the routing system.  This ensures that all routers have state machines in app state."
  [app]
  (let [state-map (app/current-state app)
        root      (app/root-class app)
        routers   (all-reachable-routers state-map root)
        tx        (mapv (fn [r]
                          (let [router-ident (comp/get-ident r {})
                                router-id    (second router-ident)]
                            (uism/begin {::uism/asm-id           router-id
                                         ::uism/state-machine-id (::uism/state-machine-id RouterStateMachine)
                                         ::uism/event-data       {:path-segment []
                                                                  :router       (vary-meta router-ident assoc :component r)}
                                         ::uism/actor->ident     {:router (uism/with-actor-class router-ident r)}}))) routers)]
    (comp/transact! app tx)))

