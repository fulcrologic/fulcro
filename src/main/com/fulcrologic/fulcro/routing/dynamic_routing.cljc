(ns com.fulcrologic.fulcro.routing.dynamic-routing
  "A router that uses Fulcro dynamic queries to optimize query performance on rendering and provides a number of useful
  features such as easy composition, control over route targeting, on-screen component vetoes of routing requests, etc.

  NOTE: This router is *not* concerned with HTML5 history events or URL management. This router is intended to be usable
  in server-side rendering, React Native, and anywhere else Fulcro might be used. Therefore it is not tied to a particular
  rendering platform's idea of location management (i.e. URLs).

  The structure of the route composition (and its representation as a sequence of string path components) is intended to
  be easy to integrate with HTML5 history and URL control."
  #?(:cljs (:require-macros [com.fulcrologic.fulcro.routing.dynamic-routing]))
  (:require
    #?@(:clj  [[cljs.analyzer :as ana]]
        :cljs [[cljs.loader :as loader]
               [goog.object :as gobj]])
    [clojure.spec.alpha :as s]
    [clojure.zip :as zip]
    [com.fulcrologic.fulcro.algorithms.indexing :as indexing]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.guardrails.core :refer [>fdef => ?]]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(def ^:dynamic *target-class*
  "INTERNAL USE ONLY. Not guaranteed to be available at runtime in production builds. This is used to aid in giving
   development-time warnings/errors.

   Class of the routing target component, available in the notifications fns
   (:will-enter, :route-cancelled, :will-leave)"
  nil)

(declare route-immediate)

(defn route-segment
  "Returns a vector that describes the sub-path that a given route target represents. String elements represent
  explicit path elements, and keywords represent variable values (which are always pulled as strings)."
  [class]
  (let [result (rc/component-options class :route-segment)]
    (when (and #?(:clj true :cljs goog.DEBUG)
            (not (or (nil? result) (vector? result))))
      (log/error "Route segment should be a vector!"))
    result))

(defn get-route-cancelled
  "Returns the function that should be called if this target was in a deferred state and a different routing choice was made. Is given the same route parameters that were sent to `will-enter`."
  [class]
  (rc/component-options class :route-cancelled))

(defn route-cancelled
  "Universal CLJC version of route-cancelled.  Don't use the protocol method in CLJ."
  [class route-params]
  (when-let [f (get-route-cancelled class)]
    (binding [*target-class* class]
      (f route-params))))

(defn get-will-enter
  "Returns the function that is called before a route target is activated (if the route segment of interest has changed and the
  target of the result is this target).  MUST return (r/route-immediate ident) or (r/route-deferred ident) to indicate
  what ident should be used in app state to connect the router's join.  If deferred, the router must cause a call to
  the r/target-ready mutation (or use the target-ready* mutation helper) with a {:target ident} parameter to indicate
  that the route target is loaded and ready for display.

  `params` will be a map from any keywords found in `route-segment` to the string value of that path element.

  WARNING: This method MUST be side-effect free."
  [class]
  (if-let [will-enter (rc/component-options class :will-enter)]
    will-enter
    (let [ident (rc/get-ident class {})]
      (when-not ident (log/error "Component must have an ident for routing to work properly:" (rc/component-name class) "See https://book.fulcrologic.com/#err-dr-comp-needs-ident"))
      (fn [_ _] (route-immediate ident)))))

(defn will-enter
  "Universal CLJC version of will-enter."
  [class app params]
  (when-let [will-enter (get-will-enter class)]
    (binding [*target-class* class]
      (will-enter app params))))

(defn route-target? [component] (boolean (rc/component-options component :route-segment)))

(defn get-will-leave
  "Returns the function of a route target to be called with
  the current component and props. If it returns `true` then the routing operation will continue.  If it returns `false`
  then whatever new route was requested will be completely abandoned. If this component has a `allow-route-change?`
  then the return value of will-leave will be ignored."
  [this]
  (or (rc/component-options this :will-leave) (constantly true)))

(defn will-leave [c props]
  (when-let [f (get-will-leave c)]
    (binding [*target-class* (rc/isoget c :type)]
      (f c props))))

(defn get-allow-route-change?
  "Returns the function of a route target to be called with the current component and props.
   If it returns `true` then the routing operation can continue.  If it returns `false`
   then whatever new route was requested will be completely abandoned. This handler MUST NOT side-effect, and
   may be called multiple times on a single route request."
  [this]
  (or
    (rc/component-options this :allow-route-change?)
    (when-let [will-leave (rc/component-options this :will-leave)]
      (log/warn "DEPRECATED USE OF `:will-leave` to check for allowable routing. You should add :allow-route-change? to: " (rc/component-name this) "See https://book.fulcrologic.com/#warn-routing-will-leave-deprecated")
      (fn [] (will-leave this (rc/props this))))
    (constantly true)))

(defn allow-route-change? [c]
  (try
    (when-let [f (get-allow-route-change? c)]
      (binding [*target-class* (rc/isoget c :type)]
        (f c)))
    (catch #?(:clj Exception :cljs :default) e
      (log/error "Cannot evaluate route change. Assuming ok. Exception message: " (ex-message e) "See https://book.fulcrologic.com/#err-dr-cant-eval-route-chng")
      true)))

(defn route-lifecycle? [component] (boolean (rc/component-options component :will-leave)))

(defn get-targets
  "Returns a set of classes to which this router routes, including dynamic ones if possible.

   `router` - A router instance, class, or registry key, or ident.
   `state-map` - The current app state

   If `router` is a class or registry key you'll get the static list
   from component options unless you also supply the state-map (it will attempt to use rc/*query-state* if it is bound). "
  ([router]
   (let [sm (if (rc/component-instance? router)
              (app/current-state router)
              (or rc/*query-state* {}))]
     (get-targets router sm)))
  ([router state-map]
   (enc/when-let [[router router-ident] (cond
                                          (rc/component-class? router) [router (rc/get-ident router {})]
                                          (rc/component-instance? router) (let [p (rc/props router)]
                                                                            ;; in case it isn't mounted yet, props could be nil
                                                                            [router (rc/get-ident router (or p {}))])
                                          (eql/ident? router) [(rc/registry-key->class (second router)) router]
                                          (rc/legal-registry-lookup-key? router) (enc/when-let [cls (some-> router (rc/registry-key->class))]
                                                                                   [cls (rc/get-ident cls {})]))
                  static-router-targets (set (rc/component-options router :router-targets))
                  router-targets        (into static-router-targets
                                          (keep rc/registry-key->class)
                                          (get-in state-map (conj router-ident ::dynamic-router-targets)))]
     router-targets)))

(defn- ident-matches-expectation? [[expected-table maybe-expected-id] [table id]]
  ;; NOTE: If the `id` of the ident is hardcoded then maybe-expected-id will be set,
  ;; but if it depends on props then it will be nil
  (and (= expected-table table)
    (or (nil? maybe-expected-id) (= maybe-expected-id id))))

(defn- check-ident-matches-expectation? [fn-name ident]
  (when (and #?(:clj false :cljs goog.DEBUG)
          *target-class*
          (not (ident-matches-expectation? (rc/ident *target-class* {}) ident)))
    (log/error fn-name " was invoked with the ident " ident
      " which doesn't seem to match the ident of the wrapping component (class "
      *target-class* " , ident ["
      (first (rc/ident *target-class* {})) " ...]) See https://book.fulcrologic.com/#err-dr-ident-mismatch")))

(defn route-immediate
  "Used as a return value from `will-enter`. Instructs the routing system that you would like this target to be
   routed to as soon as possible. UI switching defaults to depth-first to prevent flicker."
  [ident]
  (check-ident-matches-expectation? "route-immediate" ident)
  (with-meta ident {:immediate true}))

(defn route-deferred
  "Used as a return value from `will-enter`. Instructs the router to run the `completion-fn`. The completion function
   *must* use the mutation `target-ready` or function `target-ready!` to indicate when it is ready for the target to
   appear on-screen."
  [ident completion-fn]
  (check-ident-matches-expectation? "route-deferred" ident)
  (with-meta ident {:immediate false
                    :fn        completion-fn}))
(defn immediate? [ident] (some-> ident meta :immediate))

(defn route-with-path-ordered-transaction
  "Used as a return value from `will-enter`. Instructs the routing system to execute the `txn` in *routing path order*,
   and optionally couples these operations into a single transaction. This can be used in cases where you need the
   side-effects (potentially full-stack) to complete for a parent target before those of a nested target.

   The `options` can contain:

   * `:optimistic?`  (default false) - When true, don't wait for this transaction to (full-stack) complete before starting
     child target effects.
   * `:route-immediate?` (default false) - When true, apply the UI routing immediately instead of waiting for the transaction
     to finish. Of course the UI of the target should then be willing to tolerate the lack of any full-stack result.
   * `:show-early?` - (default false) - When true each transaction that completes will cause that target to appear. When
    false the target won't appear until after all children have completed their non-optimistic path-based transactions.
  "
  ([ident txn] (route-with-path-ordered-transaction ident txn {}))
  ([ident txn {:keys [optimistic? route-immediate?] :as options}]
   (let [optimistic?      (if (some? optimistic?) optimistic? false)
         route-immediate? (if (some? route-immediate?) route-immediate? false)]
     (with-meta ident {:path-ordered? true
                       :immediate     route-immediate?
                       :txn           txn
                       :optimistic?   optimistic?}))))

(defn- apply-route* [state-map {:keys [router target]}]
  (let [router-class (-> router meta :component)
        router-id    (second router)
        target-class (-> target meta :component)]
    (log/debug "Applying route ident" target "for routed class" target-class "to router" router-id)
    (when (nil? router-class)
      (log/error "apply-route* was called without a proper :router argument. See https://book.fulcrologic.com/#err-dr-apply-route-lacks-router"))
    (when (nil? target-class)
      (log/error "apply-route* for router " router-class "was given a target that did not have a component. "
        "Did you remember to call route-deferred or route-immediate? See https://book.fulcrologic.com/#err-dr-apply-route-no-component"))
    (-> state-map
      (assoc-in (conj router ::current-route) target)
      (update-in router dissoc ::pending-route)
      (rc/set-query* router-class {:query [::id [::uism/asm-id router-id] {::current-route (rc/get-query target-class state-map)}]}))))

(defn router-for-pending-target [state-map target]
  (let [routers   (some-> state-map ::id vals)
        router-id (reduce (fn [_ r]
                            (when (and #?(:clj true :cljs goog.DEBUG) (nil? (::id r)))
                              (log/error "There is a router in state that is missing an ID. This indicates that"
                                "you forgot to compose it into your initial state! It will fail to operate properly. See https://book.fulcrologic.com/#err-dr-router-missing-id"))
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
          (when (and #?(:clj false :cljs goog.DEBUG) (nil? (get-in state-map target)))
            (log/error `target-ready "should route to" target "but there is no data in the DB for the ident."
              "Perhaps you supplied a wrong ident? See https://book.fulcrologic.com/#err-dr-target-ready-missing-data"))
          (uism/trigger! app router-id :ready!))
        (log/error "dr/target-ready! was called but there was no router waiting for the target listed: " target
          "This could mean you sent one ident, and indicated ready on another. See https://book.fulcrologic.com/#err-dr-target-ready-no-router-waiting"))))
  (refresh [_] [::current-route]))

(defn target-ready!
  "Indicate a target is ready.  Safe to use from within mutations.

  target - The ident that was originally listed as a deferred target."
  [component-or-app target]
  (rc/transact! component-or-app [(target-ready {:target target})]))

(defn router? [component] (boolean (rc/component-options component :router-targets)))

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
  (let [state-map (app/current-state (rc/any->app this))
        class     (some->> (rc/get-query this state-map) eql/query->ast :children
                    (filter #(= ::current-route (:key %))) first :component)
        ;; Hot code reload support to avoid getting the cached class from old metadata
        class     (if #?(:cljs goog.DEBUG :clj false)
                    (-> class rc/class->registry-key rc/registry-key->class)
                    class)]
    class))

(defn route-target
  "Given a router class and a path segment, returns the class of *that router's* target that accepts the given URI path,
  which is a vector of (string) URI components. `state-map` is required if you want it to work with dynamic targets.

  Returns nil if there is no target that accepts the path, or a map containing:

  {:target class
   :matching-prefix prefix}

  where `class` is the component class that accepts the path (the target, NOT the router), and `matching-prefix` is the
  portion of the path that is accepted by that class.

  NOTE: If more than one target matches, then the target with the longest match will be returned. A warning will be
  printed if more than one match of equal length is found.
  "
  ([router-class path] (route-target router-class path rc/*query-state*))
  ([router-class path state-map]
   (when (and router-class (router? router-class))
     (let [targets    (get-targets router-class state-map)
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
         (log/warn "More than one route target matches" path "See https://book.fulcrologic.com/#warn-routing-multiple-target-matches"))
       (first match)))))

(defn accepts-route?
  "Returns true if the given component is a router that manages a route target that will accept the given path.
   Requires `state-map` to work on dynamically-added routes."
  ([component path] (accepts-route? component path rc/*query-state*))
  ([component path state-map]
   (boolean (route-target component path state-map))))

(defn ast-node-for-route
  "Returns the AST node for a query that represents the router that has a target that can accept the given path. This is a breadth-first
  search.

  ast - A query AST node
  path - A vector of the current URI segments.
  state-map - Application state map, required for support of dynamically-added routes.

  Returns an AST node or nil if none is found."
  ([{:keys [component children] :as ast-node} path]
   (ast-node-for-route ast-node path rc/*query-state*))
  ([{:keys [component children] :as ast-node} path state-map]
   (or
     (and (accepts-route? component path state-map) ast-node)
     (some #(and (accepts-route? (:component %) path state-map) %) children)
     (some #(ast-node-for-route % path state-map) children))))

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
        (rc/transact! app [(indexing/reindex)]))
      new-env)))

(defn fail-handler [env] env)

(defn route-handler [{::uism/keys [app event-data] :as env}]
  (let [{:keys [router target error-timeout deferred-timeout path-segment] :or {error-timeout 5000 deferred-timeout 20}} event-data
        immediate? (immediate? target)]
    (-> (if immediate?
          (let [new-env (-> env
                          (uism/store :path-segment path-segment)
                          (uism/apply-action apply-route* event-data)
                          (uism/activate :routed))]
            (when app
              (rc/transact! app [(indexing/reindex)]))
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

    :routed   {::uism/events {:waiting! {::uism/handler identity} ; in case we fail to cancel timer
                              :timeout! {::uism/handler identity}
                              :route!   {::uism/handler route-handler}}}}})

;; TODO: This algorithm is repeated in more than one place in slightly different forms...refactor it.
(defn proposed-new-path
  "Internal algorithm: Returns a sequence of idents of the targets that the `new-route` goes through by analyzing the current
  application query and state."
  ([this-or-app relative-class-or-instance new-route]
   (proposed-new-path this-or-app relative-class-or-instance new-route {}))
  ([this-or-app relative-class-or-instance new-route timeouts-and-params]
   (let [app        (rc/any->app this-or-app)
         state-map  (app/current-state app)
         root-query (rc/get-query relative-class-or-instance state-map)
         ast        (eql/query->ast root-query)
         root       (ast-node-for-route ast new-route state-map)
         result     (atom [])]
     (loop [{:keys [component]} root path new-route]
       (when (and component (router? component))
         (let [{:keys [target matching-prefix]} (route-target component path state-map)
               target-ast     (some-> target (rc/get-query state-map) eql/query->ast)
               prefix-length  (count matching-prefix)
               remaining-path (vec (drop prefix-length path))
               segment        (route-segment target)
               params         (reduce
                                (fn [p [k v]] (if (keyword? k) (assoc p k v) p))
                                (dissoc timeouts-and-params :error-timeout :deferred-timeout)
                                (map (fn [a b] [a b]) segment matching-prefix))
               target-ident   (will-enter target app params)]
           (when (or (not (eql/ident? target-ident)) (nil? (second target-ident)))
             (log/error "will-enter for router target" (rc/component-name target) "did not return a valid ident. Instead it returned: " target-ident "See https://book.fulcrologic.com/#err-dr-will-enter-invalid-ident"))
           (when (and (eql/ident? target-ident)
                   (not (contains? (some-> target-ident meta) :immediate)))
             (log/error "will-enter for router target" (rc/component-name target) "did not wrap the ident in route-immediate, route-deferred, or route-with-path-ordered-transaction. See https://book.fulcrologic.com/#err-dr-will-enter-missing-metadata"))
           (when (vector? target-ident)
             (swap! result conj (vary-meta target-ident assoc :component target :params params)))
           (when (seq remaining-path)
             (recur (ast-node-for-route target-ast remaining-path state-map) remaining-path)))))
     @result)))

(defn signal-router-leaving
  "Tell active routers that they are about to leave the screen. Returns false if any of them deny the route change."
  ([app-or-comp relative-class-or-instance new-route]
   (signal-router-leaving app-or-comp relative-class-or-instance new-route {}))
  ([app-or-comp relative-class-or-instance new-route timeouts-and-params]
   (let [new-path   (proposed-new-path app-or-comp relative-class-or-instance new-route timeouts-and-params)
         app        (rc/any->app app-or-comp)
         state-map  (app/current-state app)
         router     relative-class-or-instance
         root-query (rc/get-query router state-map)
         ast        (eql/query->ast root-query)
         root       (ast-node-for-live-router app ast)
         to-signal  (atom [])
         to-cancel  (atom [])
         _          (loop [{:keys [component children] :as node} root new-path-remaining new-path]
                      (when (and component (router? component))
                        (let [new-target    (first new-path-remaining)
                              router-ident  (rc/get-ident component {})
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
                                                         (some-> component (rc/get-query state-map)
                                                           eql/query->ast :children))
                                  mounted-targets      (comp/class->all app mounted-target-class)]
                              (when (seq mounted-targets)
                                (swap! to-signal into mounted-targets))))
                          (when next-router
                            (recur next-router (rest new-path-remaining))))))
         components (reverse @to-signal)
         result     (atom true)]
     (doseq [c components]
       (let [will-leave-result (will-leave c (rc/props c))]
         (swap! result #(and % will-leave-result))))
     (when @result
       (doseq [t @to-cancel]
         (let [{:keys [component params]} (some-> t meta)]
           (route-cancelled component params))))
     @result)))

(defn current-route
  "Returns the current active route, starting from the relative Fulcro class or instance.

  Any component using this as a basis for rendering will need to add the following to their query to
  ensure the props of that component change on route changes:

  ```
  [::uism/asm-id fq-router-kw]
  ```

  where `fq-router-kw` is a keyword that has the exact namespace and name of the router you're interested in. If you want
  to just over-render you can use a quoted `_` instead.

  NOTE: This function is primarily meant to be used in mutation implementations or with global routing.
  It is not reliable to use this function during render because it relies on the router being mounted (and render gets
  called in order to determine what to render). If you want to know the current state of a particular
  router you should query for it's ASM as indicated above.
  "
  ([this-or-app]
   (if-let [cls (some-> this-or-app rc/any->app app/root-class)]
     (current-route this-or-app cls)
     []))
  ([this-or-app relative-class-or-instance]
   (let [app        (rc/any->app this-or-app)
         state-map  (app/current-state app)
         router     relative-class-or-instance
         root-query (rc/get-query router state-map)
         ast        (eql/query->ast root-query)
         root       (or (ast-node-for-live-router app ast)
                      (-> ast :children first))
         result     (atom [])]
     (loop [{:keys [component] :as node} root]
       (when (and component (router? component))
         (let [router-ident (rc/get-ident component {})
               router-id    (-> router-ident second)
               sm-env       (uism/state-machine-env state-map nil router-id :none {})
               path-segment (uism/retrieve sm-env :path-segment)
               next-router  (some #(ast-node-for-live-router app %) (:children node))]
           (when (seq path-segment)
             (swap! result into path-segment))
           (when next-router
             (recur next-router)))))
     @result)))

(defn- mounted-targets [app router-class]
  (let [state-map            (app/current-state app)
        mounted-target-class (reduce (fn [acc {:keys [dispatch-key component]}]
                                       (when (= ::current-route dispatch-key)
                                         (reduced component)))
                               nil
                               (some-> router-class (rc/get-query state-map)
                                 eql/query->ast :children))]
    (comp/class->all app mounted-target-class)))

(defn- set-force-route-flag! [route-target]
  #?(:cljs (gobj/set route-target "fulcro$routing$force_route" true)))

(defn- force-route-flagged?
  "returns true if the given route target's allow-route-change? should be ignored."
  [route-target]
  (rc/isoget route-target "fulcro$routing$force_route"))

(defn target-denying-route-changes
  "This function will return the first mounted instance of a route target that is currently indicating it would
  deny a route change. If a `relative-class` is given then it only looks for targets that would deny a change within
  that router's subtree."
  ([this-or-app relative-class]
   (let [app        (rc/any->app this-or-app)
         state-map  (app/current-state app)
         root-query (rc/get-query relative-class state-map)
         ast        (eql/query->ast root-query)
         root       (or (ast-node-for-live-router app ast)
                      (-> ast :children first))]
     (loop [{router-class :component
             :keys        [children]} root]
       (when (and router-class (router? router-class))
         (let [router-ident     (rc/get-ident router-class {})
               active-target    (get-in state-map (conj router-ident ::current-route))
               next-router      (some #(ast-node-for-live-router app %) children)
               rejecting-target (when (vector? active-target)
                                  (some (fn [c] (when (and
                                                        (false? (allow-route-change? c))
                                                        (not (force-route-flagged? c))) c)) (mounted-targets app router-class)))]
           (cond
             rejecting-target rejecting-target
             next-router (recur next-router)))))))
  ([this-or-app]
   (let [app    (rc/any->app this-or-app)
         router (app/root-class app)]
     (target-denying-route-changes app router))))

(defn can-change-route?
  "Returns true if the active on-screen targets indicate they will allow navigation.

  NOTE: If your route targets have an `:allow-route-change?`, then that will be used to determine if the route can
  be abandoned; otherwise `:will-leave` will be called to answer the question; however, this USE of `will-leave`
  is DEPRECATED (though the hook is NOT because it serves another purpose). If you side-effect in `:will-leave` this could cause strange
  behavior throughout the application.  It is recommended that your targets implement `:allow-route-change?` if they need
  to prevent routing, and only leverage `:will-leave` to do things like cancel in-progress loads."
  ([this-or-app] (nil? (target-denying-route-changes this-or-app)))
  ([this-or-app relative-class] (nil? (target-denying-route-changes this-or-app relative-class))))

(defn evaluate-relative-path
  "Takes an on-screen *instance* of a react element and a new route (vector of strings) and returns a vector containing
   either the original arguments, or an evaluation of relative navigation up the live routing tree.

   If `new-route` starts with `:..` (any number of times) then this function finds (and returns) the parent *router*
   and the new route stripped of `:..` prefix.

   For example, say you were in a target instance that has a parent router, which in turn has a parent router called
   `SomeRouter`. Then:

   ```
   (dr/evaluate-relative-path this [:.. :.. \"some-target\"])
   => [SomeRouter [\"some-target\"]]
   ```

   This function does *not* work on classes. It is meant for live evaluation of on-screen instances to enable relative
   routing based on the actual on-screen route targets.

   CAN return `nil` for the router if no such parent is found.

   Returns unmodified input argument if `new-route` does not begin with `:..`.
   "
  [relative-instance new-route]
  (loop [current-instance    relative-instance
         [lead-element & remainder :as path] new-route
         looking-for-router? ^Boolean (= :.. lead-element)]
    (cond
      (or (nil? current-instance) (empty? path))
      [current-instance path]

      (and looking-for-router? (router? current-instance))
      (recur current-instance (vec remainder) false)

      looking-for-router?
      (recur (comp/get-parent current-instance) path true)

      (= :.. lead-element)
      #_=> (recur (comp/get-parent current-instance) path true)

      :else
      [current-instance path])))

(defn change-route-relative!
  "Change the route, starting at the given Fulcro class or instance (scanning for the first router from there).  `new-route` is a vector
  of string components to pass through to the nearest child router as the new path. The first argument is any live component
  or the app.  The `timeouts-and-params` are as in `change-route`.

  When possible (i.e. no circular references to components) you can maintain better code navigation by
  generating `new-route` via `path-to`.  This will allow readers of your code to quickly jump to the actual
  components that implement the targets when reading the code.

  You may include the special keyword `:..` any number of times at the beginning of `new-route` to indicate the
  parent(s) of `relative-class-or-instance`, which allows you to do relative routing to a sibling.

  ```
  (dr/change-route-relative this this [:.. \"sibling-pattern\"])
  ```

  Returns one of:

  `:already-there` - The old and new route are the same, and there was no request to force an idempotent routing operation.
  `:invalid` - The new route didn't evaluate to a valid location
  `:denied` - One or more on-screen targets refused to allow the routing operation
  `:routing` - The routing operation is in progress, but deferred operations may still delay the route becoming visible.
  "
  ([this-or-app relative-class-or-instance new-route]
   (change-route-relative! this-or-app relative-class-or-instance new-route {}))
  ([app-or-comp relative-class-or-instance new-route timeouts-and-params]
   (let [[relative-class-or-instance new-route] (evaluate-relative-path relative-class-or-instance new-route)
         relative-class (if (rc/component? relative-class-or-instance)
                          (comp/react-type relative-class-or-instance)
                          relative-class-or-instance)
         old-route      (current-route app-or-comp relative-class)
         new-path       (proposed-new-path app-or-comp relative-class new-route timeouts-and-params)]
     (cond
       (and (= old-route new-route) (not (::force? timeouts-and-params)))
       (do
         (log/debug "Request to change route, but path is the current route. Ignoring change request.")
         :already-there)

       (and #?(:clj true :cljs goog.DEBUG) (not (seq new-path)))
       (do
         (log/error "Could not find route targets for new-route" new-route "See https://book.fulcrologic.com/#err-dr-new-route-target-not-found")
         :invalid)

       (not (can-change-route? app-or-comp relative-class))
       (let [app          (rc/any->app app-or-comp)
             target       (target-denying-route-changes app relative-class)
             route-denied (rc/component-options target :route-denied)]
         (log/debug "Route request denied by on-screen target" target ". Calling component's :route-denied (if defined).")
         (when route-denied
           (route-denied target relative-class-or-instance new-route timeouts-and-params))
         :denied)

       :otherwise
       (do
         (signal-router-leaving app-or-comp relative-class-or-instance new-route timeouts-and-params)
         (let [app             (rc/any->app app-or-comp)
               state-map       (app/current-state app)
               router          relative-class-or-instance
               root-query      (rc/get-query router state-map)
               ast             (eql/query->ast root-query)
               root            (ast-node-for-route ast new-route state-map)
               routing-actions (atom (list))
               pessimistic-txn (atom [])
               delayed-targets (atom [])]
           (loop [{:keys [component]} root path new-route]
             (when (and component (router? component))
               (let [{:keys [target matching-prefix]} (route-target component path state-map)
                     target-ast        (some-> target (rc/get-query state-map) eql/query->ast)
                     prefix-length     (count matching-prefix)
                     remaining-path    (vec (drop prefix-length path))
                     segment           (route-segment target)
                     params            (reduce
                                         (fn [p [k v]] (if (keyword? k) (assoc p k v) p))
                                         (dissoc timeouts-and-params :error-timeout :deferred-timeout)
                                         (map (fn [a b] [a b]) segment matching-prefix))
                     router-ident      (rc/get-ident component {})
                     router-id         (-> router-ident second)
                     target-ident      (will-enter target app params)
                     {:keys [path-ordered?
                             txn
                             show-early?
                             optimistic?]} (meta target-ident)
                     completing-action (or
                                         (some-> target-ident meta :fn)
                                         (and optimistic? (seq txn) #(comp/transact! app txn))
                                         (constantly true))
                     event-data        (merge
                                         {:error-timeout 5000 :deferred-timeout 20}
                                         timeouts-and-params
                                         {:path-segment matching-prefix
                                          :router       (vary-meta router-ident assoc :component component)
                                          :target       (vary-meta target-ident assoc :component target :params params)})]
                 (when (and path-ordered? (seq txn) (not optimistic?))
                   (swap! pessimistic-txn into txn)
                   (if show-early?
                     (swap! pessimistic-txn conj (target-ready {:target target-ident}))
                     (swap! delayed-targets conj (target-ready {:target target-ident}))))
                 ;; Route instructions queued into a list (which will reverse their order in the doseq below)
                 (swap! routing-actions conj
                   #(do
                      (if-not (uism/get-active-state app router-id)
                        (do
                          (let [state-map (comp/component->state-map app-or-comp)]
                            (when-not (-> state-map ::id (get router-id))
                              (log/error "You are routing to a router " router-id "whose state was not composed into the app from root. Please check your :initial-state. See https://book.fulcrologic.com/#err-dr-router-state-missing")))
                          (uism/begin! app-or-comp RouterStateMachine router-id
                            {:router (uism/with-actor-class router-ident component)}
                            event-data))
                        (uism/trigger! app router-id :route! event-data))
                      ;; make sure any transactions submitted from the completing action wait for a render of the state machine's
                      ;; startup or route effects before running.
                      (binding [rc/*after-render* true]
                        (completing-action))))
                 (when (seq remaining-path)
                   (recur (ast-node-for-route target-ast remaining-path state-map) remaining-path)))))
           ;; Normal route instructions are sent depth first to prevent flicker
           (doseq [action @routing-actions]
             (action))
           (when (or (seq @pessimistic-txn) (seq @delayed-targets))
             (log/debug "Running pessimistic transaction" @pessimistic-txn "with delayed targets" @delayed-targets)
             (comp/transact! app (into [] (concat @pessimistic-txn (reverse @delayed-targets)))
               {:optimistic? false})))
         :routing)))))

(def change-route-relative "DEPRECATED NAME: Use change-route-relative!" change-route-relative!)

(defn retry-route!
  "Retry a route that the receiving component just denied, and ignore this target's answer. All other targets will still
  be asked. This is primarily used when you want to be able to use js/confirm in a component to ask the user if
  they \"really mean to navigate away\". You MUST pass the arguments that `:route-denied` received
  or you can easily cause an infinite loop. Other on-screen targets can still potentially abort the route."
  ([denied-target-instance relative-root path]
   (retry-route! denied-target-instance relative-root path {}))
  ([denied-target-instance relative-root path timeouts-and-params]
   #?(:cljs
      (do
        (log/debug "Retrying route at the request of " (rc/component-name denied-target-instance))
        (set-force-route-flag! denied-target-instance)
        (change-route-relative! denied-target-instance relative-root path timeouts-and-params)))))

(defn change-route!
  "Trigger a route change.

  * `this` - The component (or app) that is causing the route change.
  * `new-route` - A vector of URI components to pass to the router.
  * `timeouts-and-params` - A map of additional parameters and route timeouts that affect UI during deferred routes:
  `{:error-timeout ms :deferred-timeout ms}`.  Anything extra will appear in the `params` of `will-enter`.

  The error timeout is how long to wait  (default 5000ms) before showing the error-ui of a route (which must be defined on the
  router that is having problems).  The deferred-timeout (default 100ms) is how long to wait before showing the loading-ui of
  a deferred router (to prevent flicker).

  Returns one of:

  `:already-there` - The old and new route are the same, and there was no request to force an idempotent routing operation.
  `:invalid` - The new route didn't evaluate to a valid location
  `:denied` - One or more on-screen targets refused to allow the routing operation
  `:routing` - The routing operation is in progress, but deferred operations may still delay the route becoming visible.
  "
  ([this new-route]
   (change-route! this new-route {}))
  ([this new-route timeouts-and-params]
   (let [app  (rc/any->app this)
         root (app/root-class app)]
     (change-route-relative! app root new-route timeouts-and-params))))

(def change-route change-route!)

#?(:clj
   (defn compile-error [env form message]
     (throw (ana/error (merge env (some-> form meta)) message))))

#?(:clj (s/def ::router-targets (s/coll-of symbol? :type vector?)))
#?(:clj (s/def ::always-render-body? boolean?))
#?(:clj (s/def ::defrouter-options (s/keys :req-un [::router-targets] :opt-un [::always-render-body?])))

(defn validate-route-targets
  "Run a runtime validation on route targets to verify that they at least declare a route-segment that is a vector."
  [router-instance]
  (when (and router-instance (rc/component-instance? router-instance) #?(:cljs goog.DEBUG :clj true))
    (let [state-map (app/current-state router-instance)]
      (doseq [t (get-targets router-instance state-map)
              :let [segment (route-segment t)
                    valid?  (and
                              (vector? segment)
                              (not (empty? segment))
                              (every? #(or (keyword? %) (string? %)) segment))]]
        (when-not valid?
          (log/error "Route target "
            (rc/component-name t)
            "of router"
            (rc/component-name router-instance)
            "does not declare a valid :route-segment. Route segments must be non-empty vector that contain only strings"
            "and keywords. See https://book.fulcrologic.com/#err-dr-target-lacks-r-segment"))))))

(defn alt-key
  "Creates the key used for alternative router targets given an index"
  [idx]
  (keyword (str "alt" idx)))

#?(:clj
   (defn defrouter* [env router-ns router-sym arglist options body]
     (when-not (and (vector? arglist) (= 2 (count arglist)))
       (compile-error env options "defrouter argument list must have an entry for this and props."))
     (when-not (map? options)
       (compile-error env options "defrouter requires a literal map of options."))
     (when-not (s/valid? ::defrouter-options options)
       (compile-error env options (str "defrouter options are invalid: " (s/explain-str ::defrouter-options options))))
     (let [{:keys [router-targets]} options
           id                     (keyword router-ns (name router-sym))
           getq                   (fn [s] `(or (rc/get-query ~s)
                                             (throw (ex-info (str "Route target has no query! "
                                                               (rc/component-name ~s)) {}))))
           base-query             [::id
                                   [::uism/asm-id id]
                                   ::dynamic-router-targets
                                   (if (seq router-targets) {::current-route (getq (first router-targets))}
                                                            ::current-route)]
           query                  (into base-query
                                    (map-indexed
                                      (fn [idx s]
                                        (when (nil? s)
                                          (compile-error env options "defrouter :target contains nil!"))
                                        {(alt-key idx) (getq s)})
                                      (rest router-targets)))
           base-initial-state     (cond-> {::id id}
                                    (seq router-targets) (assoc
                                                           ::current-route
                                                           `(rc/get-initial-state ~(first router-targets) ~'params)))
           initial-state-map      (into base-initial-state
                                    (map-indexed
                                      (fn [idx s] [(alt-key idx) `(rc/get-initial-state ~s {})])
                                      (rest router-targets)))
           ident-method           (apply list `(fn [] [::id ~id]))
           initial-state-lambda   (apply list `(fn [~'params] ~initial-state-map))
           states-to-render-route (if (seq body)
                                    #{:routed :deferred}
                                    `(constantly true))
           always-render-body?    (and (map? options) (:always-render-body? options))
           render-cases           (if always-render-body?
                                    (apply list `(let [~'class (current-route-class ~'this)]
                                                   (let [~(first arglist) ~'this
                                                         ~(second arglist) {:pending-path-segment ~'pending-path-segment
                                                                            :route-props          ~'current-route
                                                                            :route-factory        (when ~'class (comp/factory ~'class))
                                                                            :current-state        ~'current-state
                                                                            :router-state         (get-in ~'props [[::uism/asm-id ~id] ::uism/local-storage])}]
                                                     ~@body)))
                                    (apply list `(let [~'class (current-route-class ~'this)]
                                                   (if (~states-to-render-route ~'current-state)
                                                     (when ~'class
                                                       (let [~'factory (comp/factory ~'class)]
                                                         (~'factory (rc/computed ~'current-route (rc/get-computed ~'this)))))
                                                     (let [~(first arglist) ~'this
                                                           ~(second arglist) {:pending-path-segment ~'pending-path-segment
                                                                              :route-props          ~'current-route
                                                                              :route-factory        (when ~'class (comp/factory ~'class))
                                                                              :current-state        ~'current-state}]
                                                       ~@body)))))
           options                (merge
                                    `{:componentDidMount (fn [this#] (validate-route-targets this#))
                                      :use-hooks?        false}
                                    options
                                    `{:query                   ~query
                                      :ident                   ~ident-method
                                      :initial-state           ~initial-state-lambda
                                      :preserve-dynamic-query? true})
           hook-validate          `(hooks/use-effect (fn [] ; If using hooks, validate-route-targets once
                                                       (validate-route-targets ~'this)
                                                       js/undefined)
                                     [])
           body*                  `(let [~'current-state (uism/get-active-state ~'this ~id)
                                         ~'state-map (comp/component->state-map ~'this)
                                         ~'sm-env (uism/state-machine-env ~'state-map nil ~id :fake {})
                                         ~'pending-path-segment (when (uism/asm-active? ~'this ~id) (uism/retrieve ~'sm-env :pending-path-segment))]
                                     ~render-cases)
           body                   (cond-> body*
                                    (:use-hooks? options)
                                    ((fn add-hook-validation [body] `(do ~hook-validate ~body))))]
       `(comp/defsc ~router-sym [~'this {::keys [~'id ~'current-route] :as ~'props}]
          ~options
          ~body))))

#?(:clj
   (s/fdef defrouter
     :args (s/cat :sym symbol? :arglist vector? :options map? :body (s/* any?))))

#?(:clj
   (defmacro defrouter
     "Define a router.

     The arglist is `[this props]`, which are just like defsc. The props will contains :current-state and :pending-path-segment.

     The options are:

     `:router-targets` - (REQUIRED) A *vector* (which may be empty if you want to only add targets at runtime) of ui
                         components that are router targets. The first one is considered the \"default\"
                         (purely for the purpose of initial state; you always need to explicitly route to a particular target).
                         Other defsc options - (LIMITED) You may not specify query/initial-state/protocols/ident, but you can define things like react
                         lifecycle methods. See defsc.
     `:always-render-body?` - (OPTIONAL) When true this router expects that you will supply a render body, and
                              it will always be rendered. The props available in the body will include:

     - `:current-state` - The state of the routing state machine. (:initial, :pending, :failed, :routed)
     - `:route-factory` - A factory that can generate the current route.
     - `:route-props` -  The props that should be passed to the route factory. You can augment these with computed if you
                         wish. The router normally passes computed through like so: `(route-factory (comp/computed route-props (comp/get-computed this)))`
     - `:pending-path-segment` - The route that we're going to (when in pending state).
     - `:router-state` - A map of the path management details of the router. Includes the pending path segment (if deferred), the
                         target, and the path segment of the current route.

     The optional body, if defined, will *only* be used if the router has the `:always-render-body?` option set or
     it is in one of the following states:

     - `:initial` - No route is set.
     - `:pending` - A deferred route is taking longer than expected (configurable timeout, default 100ms)
     - `:failed` - A deferred route took longer than can reasonably be expected (configurable timeout, default 5s)

     otherwise the actual active route target will be rendered.
     "
     [router-sym arglist options & body]
     (defrouter* &env (str (ns-name *ns*)) router-sym arglist options body)))

(defn all-reachable-routers
  "Returns a sequence of all of the routers reachable in the query of the app."
  [state-map component-class]
  (let [root-query  (rc/get-query component-class state-map)
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
                          (let [router-ident (rc/get-ident r {})
                                router-id    (second router-ident)]
                            (uism/begin {::uism/asm-id           router-id
                                         ::uism/state-machine-id (::uism/state-machine-id RouterStateMachine)
                                         ::uism/event-data       {:path-segment []
                                                                  :router       (vary-meta router-ident assoc :component r)}
                                         ::uism/actor->ident     {:router (uism/with-actor-class router-ident r)}}))) routers)]
    (rc/transact! app tx)))

(defn into-path
  "Returns the given `prefix` with the TargetClass segment appended onto it, replacing the final elements with the
   given (optional) path args.

  ```
  (defsc X [_ _]
    {:route-segment [\"a\" :b]})

  (into [\"f\" \"g\"] X \"22\") ; => [\"f\" \"g\" \"a\" \"22\"]
  ```
  "
  [prefix TargetClass & path-args]
  (let [nargs           (count path-args)
        path            (some-> TargetClass rc/component-options :route-segment)
        static-elements (- (count path) nargs)]
    (into prefix (concat (take static-elements path) path-args))))

(defn subpath
  "Returns the route segment of the given TargetClass with the trailing elements replaced by path-args.

  ```
  (defsc X [_ _]
    {:route-segment [\"a\" :b]})

  (subpath X \"22\") ; => [\"a\" \"22\"]
  ```
  "
  [TargetClass & path-args]
  (apply into-path [] TargetClass path-args))

(defn path-to
  "Convert a sequence of router targets and parameters into a vector of strings that represents the target route. Parameters
  can be sequenced inline:

  ```
  (defsc A [_ _]
    {:route-segment [\"a\" :a-param]})

  (defsc B [_ _]
    {:route-segment [\"b\" :b-param]})

  (path-to A a-param1 B b-param ...)
  ```

  where the parameters for a target immediately follow the component that requires them. Alternatively
  one can specify all of the parameters at the end as a single map using the parameter names that are used in
  the component `:route-segment` itself:

  ```
  (defsc A [_ _]
    {:route-segment [\"a\" :a-param]})

  (path-to A B C D {:a-param 1})
  ```
  "
  ([& targets-and-params]
   (let [segments (seq (partition-by #(and
                                        #?(:clj true :cljs (fn? %))
                                        (or
                                          (rc/component? %)
                                          (rc/component-class? %))) targets-and-params))]
     (if (and (= 2 (count segments)) (map? (first (second segments))))
       (let [path   (mapcat #(rc/component-options % :route-segment) (first segments))
             params (first (second segments))]
         (mapv (fn [i] (get params i i)) path))
       (reduce
         (fn [path [classes params]]
           (-> path
             (into (mapcat #(rc/component-options % :route-segment) (butlast classes)))
             (into (apply subpath (last classes) params))))
         []
         (partition-all 2 segments))))))

(defn resolve-path-components
  ([StartingClass RouteTarget]
   (resolve-path-components StartingClass RouteTarget [] {}))
  ([StartingClass RouteTarget base-path]
   (resolve-path-components StartingClass RouteTarget base-path {}))
  ([StartingClass RouteTarget base-path {:keys [ParentRouter] :as options}]
   (if (= StartingClass RouteTarget)
     (let [parent     (last base-path)
           final-path (conj base-path RouteTarget)]
       (when (and (router? parent)
               (or (nil? ParentRouter) (= ParentRouter parent))) final-path))
     (let [path (conj base-path StartingClass)]
       (if (router? StartingClass)
         (let [targets (get-targets StartingClass rc/*query-state*)
               matches (->> targets
                         (keep #(resolve-path-components % RouteTarget path options)))]
           (when (< 1 (count matches))
             (log/warn "More than one match found resolving path components. You probably want to specify ParentRouter to avoid ambiguity:"
                StartingClass " -> " RouteTarget matches))
           (first matches))
         (let [candidates (->> (comp/get-query StartingClass)
                            (eql/query->ast)
                            :children
                            (keep :component))
               matches (->> candidates
                         (keep #(resolve-path-components % RouteTarget path options)))]
           (when (< 1 (count matches))
             (log/warn "More than one match found resolving path components. You probably want to specify ParentRouter to avoid ambiguity:"
                " -> " RouteTarget matches))
           (first matches)))))))

(defn resolve-path
  "Attempts to resolve a path from StartingClass to the given RouteTarget. Can also be passed `resolved-components`, which
   is the output of `resolve-path-components`. If ParentRouter is supplied, then if RouteTarget is in multiple places in the
   UI this function will only consider the path that includes ParentRouter as the immediate parent of the target.

   NOTE: This function works against static queries UNLESS you bind `rc/*query-state*` to `app/current-state`.

   Returns a vector of route segments. Any keywords in the result will be replaced by the values from `params`, if present.

   Returns nil if no path can be found. Be sure rc/*query-state* is bound to current app state if you want to include dynamic queries."
  ([resolved-components params]
   (when (seq resolved-components)
     (let [base-path (into []
                       (mapcat #(rc/component-options % :route-segment))
                       resolved-components)]
       (mapv (fn [ele]
               (if (contains? params ele)
                 (str (get params ele))
                 ele)) base-path))))
  ([StartingClass RouteTarget params] (resolve-path StartingClass RouteTarget params {}))
  ([StartingClass RouteTarget params {:keys [ParentRouter] :as options}]
   (if (:route-segment (comp/component-options RouteTarget))
     (resolve-path (resolve-path-components StartingClass RouteTarget [] options) params)
     (log/warn "Attempt to resolve the path to a component that has no route-segment"))))

(defn resolve-target
  "Given a new-route path (vector of strings): resolves the target (class) that is the ultimate target of that path."
  [app new-route]
  (let [state-map  (app/current-state app)
        root-query (rc/get-query (app/root-class app) state-map)
        ast        (eql/query->ast root-query)
        root       (ast-node-for-route ast new-route state-map)]
    (loop [{:keys [component]} root path new-route]
      (when (and component (router? component))
        (let [{:keys [target matching-prefix]} (route-target component path state-map)
              target-ast     (some-> target (rc/get-query state-map) eql/query->ast)
              prefix-length  (count matching-prefix)
              remaining-path (vec (drop prefix-length path))]
          (if (seq remaining-path)
            (recur (ast-node-for-route target-ast remaining-path state-map) remaining-path)
            target))))))

(letfn [(active-routes* [state-map {:keys [path] :as result} parent-component ast-nodes]
          (let [segment (some-> parent-component (route-segment))]
            (cond
              (nil? parent-component) [result]

              (some-> parent-component (router?))
              (let [ident           (some-> parent-component (comp/get-ident {}))
                    active-ast-node (first
                                      (filter (fn [{:keys [dispatch-key]}]
                                                (= ::current-route dispatch-key))
                                        ast-nodes))
                    new-parent      (:component active-ast-node)]
                (active-routes* state-map {:path         (into path segment)
                                           ;:target-ident ident ;; Need to follow along in state to get the proper ident...
                                           :target-class parent-component} new-parent (:children active-ast-node)))

              segment
              (let [subpath (into path segment)]
                (mapcat
                  (fn [{:keys [component children] :as node}]
                    (active-routes* state-map {:path         subpath
                                               ;:target-ident ident
                                               :target-class parent-component} component children))
                  ast-nodes))

              :else
              (mapcat
                (fn [{:keys [component children] :as node}]
                  (active-routes* state-map result component children))
                ast-nodes))))]
  (defn active-routes
    "Return a sequence of the leaf router targets that are routed to in the given app using the active dynamic query
     and app state.

     The return values are maps that currently contain a `:path` and `:target-component` key.
     Future versions of this function may include additional information.

     Note that dynamic routing purposely supports the ability to have more than one UI path active at a time, as it
     is NOT a strict URL-style UI router. However, since your code is ultimately responsible for determining what
     parts of the active query are rendered, it is possible for this to return routes that are available (in the props
     of components) but are not being rendered by your logic. Thus, the return value of this function isn't necessarily
     proof that the routes listed are visible to the user.

     WARNING: If you use disconnected roots (via hooks or otherwise), then you must specify a starting component that is
     well-connected (graph/state) from which to scan, and will get back paths relative to that `starting-from`
     (a component, element, or factory if you're using factory-based dynamic queries)."
    ([app]
     (active-routes app (app/root-class app)))
    ([app starting-from]
     (let [state-map (app/current-state app)
           query     (comp/get-query starting-from state-map)
           {:keys [children]} (eql/query->ast query)]
       (set (active-routes* state-map {:path []} starting-from children))))))

(defn dynamic-router
  "The functional version of `defrouter`. Generates a router (particularly useful at runtime for use with dynamically
   generated components) with the given Fulcro registry-key and list of router-targets. The options map can contain:

   * `:render` - A (fn [this props] ...) that needs to function as described in `defrouter`.
   * Any other options that `defrouter` supports in the component options map.
   "
  ([registry-key targets]
   (dynamic-router registry-key targets {}))
  ([router-registry-key router-targets {:keys [render always-render-body?] :as options}]
   (let [main-target        (first router-targets)
         alt-targets        (rest router-targets)
         static-query       (into
                              [::id
                               [:uism/asm-id router-registry-key]
                               ::dynamic-router-targets
                               {::current-route (or
                                                  (rc/get-query main-target)
                                                  (throw (ex-info (str "Route target has no query! " (rc/component-name main-target)) {})))}]
                              (map-indexed (fn [idx c] {(alt-key idx) (rc/get-query c)}))
                              alt-targets)
         addl-options       (dissoc options :render)
         user-render        (fn [this router-props route-factory current-route-target-props]
                              (when render
                                (let [current-state        (uism/get-active-state this router-registry-key)
                                      state-map            (comp/component->state-map this)
                                      sm-env               (uism/state-machine-env state-map nil router-registry-key :fake {})
                                      pending-path-segment (when (uism/asm-active? this router-registry-key)
                                                             (uism/retrieve sm-env :pending-path-segment))
                                      render-props         {:pending-path-segment pending-path-segment
                                                            :route-props          current-route-target-props
                                                            :route-factory        route-factory
                                                            :current-state        current-state
                                                            :router-state         (get-in router-props [[::uism/asm-id router-registry-key] ::uism/local-storage])}]
                                  (render this render-props))))
         render-target-only (fn [this route-target-props route-factory]
                              (when route-factory
                                (route-factory route-target-props (rc/get-computed this))))]
     (comp/sc router-registry-key
       (merge
         addl-options
         {:preserve-dynamic-query? true,
          :router-targets          router-targets
          :ident                   (fn [_ _] [::id router-registry-key]),
          :componentDidMount       (fn [this] (validate-route-targets this)),
          :initial-state           (fn [params]
                                     (into
                                       {::id            router-registry-key
                                        ::current-route (rc/get-initial-state (first router-targets) params)}
                                       (map-indexed (fn [idx c] [(alt-key idx) (rc/get-initial-state c {})]))
                                       (rest router-targets)))
          :query                   (fn [_] static-query)})
       (fn [this {::keys [current-route] :as props}]
         (let [TargetClass   (current-route-class this)
               route-factory (some-> TargetClass (comp/computed-factory))]
           (if always-render-body?
             (user-render this props route-factory current-route)
             (let [current-state          (uism/get-active-state this router-registry-key)
                   states-to-render-route (if render #{:routed :deferred} (constantly true))]
               (if (states-to-render-route current-state)
                 (render-target-only this current-route route-factory)
                 (user-render this props route-factory current-route))))))))))

(defn add-route-target*
  "Mutation helper. Add a target to a router dynamically.

  `router` - A class or registry key
  `target` - A class or registry key
  `initial-state-params` - Parameters to pass to `get-initial-state` when merging the state of `target` (which is only
  done if that component has a stable ident).
  "
  [state-map {:keys [router target initial-state-params]}]
  (let [Router              (rc/registry-key->class router)
        Target              (rc/registry-key->class target)
        stable-ident?       (and Target (some? (second (comp/get-ident Target {}))))
        router-ident        (rc/get-ident Router {})
        target-registry-key (rc/class->registry-key Target)]
    (cond
      (nil? Router)
      (do
        (log/error "Cannot add route target. Router class not found for" router)
        state-map)

      (nil? Target)
      (do
        (log/error "Cannot add route target. Target class not found for" target)
        state-map)

      (not (vector? (route-segment Target)))
      (do
        (log/error "Cannot add route target. Target class has a missing or invalid :route-segment: " target)
        state-map)

      :else
      (cond-> (update-in state-map (conj router-ident ::dynamic-router-targets) (fnil conj #{}) target-registry-key)
        stable-ident? (merge/merge-component Target (rc/get-initial-state Target (or initial-state-params {})))))))

(defmutation add-route-target
  "Mutation. Add a target to a router dynamically.

   params:
   * router - A router class or registry key
   * target - A target class or registry key (must have :route-segment)
   * initial-state-params - Parameters for the initial state for merging the target into state (if it has a stable ident)

   See also `add-route-target!` and `add-route-target*`.
   "
  [{:keys [router target] :as params}]
  (action [{:keys [state]}]
    (swap! state add-route-target* router target)))

(defn add-route-target!
  "Add a target to an existing router.

  app-ish - An app or component
  options - A map:
   * router - A router class or registry key
   * target - A target class or registry key (must have :route-segment)
   * initial-state-params - Parameters for the initial state for merging the target into state (if it has a stable ident)
  "
  [app-ish options]
  (comp/transact! app-ish [(add-route-target options)]))

(defn add-route-target!!
  "Add a target to an existing router synchronously. This will NOT show in Fulcro Inspect as a transaction.

  app-ish - An app or component
  options - A map:
   * router - A router class or registry key
   * target - A target class or registry key (must have :route-segment)
   * initial-state-params - Parameters for the initial state for merging the target into state (if it has a stable ident)
  "
  [app-ish options]
  ;; We go straight to the app state atom, which is safe because the targets are never rendered, and it ensures
  ;; absolute synchronous change.
  (let [state-atom (::app/state-atom (comp/any->app app-ish))]
    (swap! state-atom add-route-target* options)))

(defn absolute-path
  "Get the absolute path for the given route target.

   NOTE: Using this on a route target that is on multiple paths of your application
   can lead to ambiguity and failure of general routing, since this will then return an unpredictable result.
   In those cases you must supply the options map with the ParentRouter of the RouteTarget, which will resolve the ambiguity. "
  ([app-ish RouteTarget route-params {:keys [ParentRouter] :as options}]
   (let [app       (comp/any->app app-ish)
         app-root  (app/root-class app)
         state-map (app/current-state app)]
     (binding [rc/*query-state* state-map]
       (resolve-path app-root RouteTarget route-params options))))
  ([app-ish RouteTarget route-params]
   (absolute-path app-ish RouteTarget route-params {})))

(defn- loaded? [k] #?(:cljs (or (nil? k) (enc/catching (loader/loaded? k))) :clj true))

(defn route-to!
  "Route to a specific `target` of the given `Router`. This is different from `change-route!` in that it makes the
   code a bit more navigable (though a bit less easily refactored), and supports some additional dynamic features:

   * Dynamically adding the target to the router if it isn't there
   * Loading a module that contains the router (dynamic code load through cljs.loader) and adding it to the router

   `app-ish` - An app or component instance

   The `options` map can contain:

   * `router` (OPTIONAL/REQUIRED) - A router class or registry key for that router. Required if you want auto-add or loading to work.
   * `target` (REQUIRED) - A target class or registry key.
   * `:route-params` - A map from keywords to values for any of the route parameters expected for the given target.
   * `:auto-add?` - Default false. Automatically add the target to the router if it isn't already there.
   * `:load-from <module-name>` - Default nil. Check to see if <module-name> is loaded. If not, load it, IMPLIES `auto-add? true`.
   * `:initial-state-params` - Parameters to use for the merge with get-initial-state if the component is added, and has a stable ident.
   * `after-load (fn [app] ...)` - IF dynamically loaded, this function will be called before attempting to add the target, allowing
     you to dynamically generate the component from the loaded code if necessary. Such generation MUST be synchronous.
   * `before-change (fn [app {:keys [target path route-params]}] ...)` - If the routing is possible and is not denied,
     this will be called just before the route is put into effect.
   "
  [app-ish {Router :router
            :keys [target
                   route-params
                   auto-add?
                   after-load
                   before-change
                   initial-state-params
                   load-from] :as options}]
  (let [app              (comp/any->app app-ish)
        state-map        (app/current-state app)
        auto-add?        (or auto-add? (boolean load-from))
        Router           (rc/registry-key->class Router)
        target-key       (if (rc/legal-registry-lookup-key? target)
                           (keyword target)
                           (rc/class->registry-key target))
        RouteTarget      (rc/registry-key->class target-key)
        existing-targets (and Router (into #{} (map rc/class->registry-key) (get-targets Router state-map)))
        present?         (or (nil? Router) (contains? existing-targets target-key))
        loaded?          (loaded? load-from)]
    (cond
      (and Router auto-add? loaded? (not present?))
      (do
        (add-route-target!! app {:router               Router
                                 :initial-state-params initial-state-params
                                 :target               RouteTarget})
        (route-to! app {:router       Router
                        :target       RouteTarget
                        :route-params route-params
                        :auto-add?    false}))

      (and Router (not loaded?))
      #?(:clj  nil
         :cljs (loader/load load-from (fn []
                                        (when (fn? after-load) (after-load app))
                                        (add-route-target!! app {:router               Router
                                                                 :initial-state-params initial-state-params
                                                                 :target               target-key})
                                        (route-to! app {:router       Router
                                                        :target       target-key
                                                        :route-params route-params
                                                        :auto-add?    false}))))

      (and present? RouteTarget)
      (if-let [path (absolute-path app RouteTarget route-params {:ParentRouter Router})]
        (do
          (when-not (every? string? path)
            (log/warn "Insufficient route parameters passed. Resulting route is probably invalid."
              (comp/component-name RouteTarget) route-params))
          (when (and
                  (if Router
                    (can-change-route? app Router)
                    (can-change-route? app))
                  (fn? before-change))
            (before-change app {:target       RouteTarget
                                :path         path
                                :route-params route-params}))
          (change-route! app path route-params))
        (log/error "Routing failed. Unable to construct route path from given arguments" {:router Router :target target-key}))

      (and (not present?) (not auto-add?))
      (do
        (log/error "Cannot route to target because the router does not have that target (perhaps it failed to load?, or auto-add? was false)."
          {:router Router :target target})))))
