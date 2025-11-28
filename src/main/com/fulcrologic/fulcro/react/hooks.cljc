(ns com.fulcrologic.fulcro.react.hooks
  "React hooks wrappers and helpers. The wrappers are simple API simplifications that help when using hooks from
   Clojurescript, but this namespace also includes utilities for using Fulcro's data/network management from raw React
   via hooks.

   See `use-root`, `use-component`, and `use-uism`."
  #?(:cljs
     (:require-macros [com.fulcrologic.fulcro.react.hooks :refer [use-effect use-lifecycle]]))
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; WARNING TO MAINTAINERS: DO NOT REFERENCE DOM IN HERE. This has to work with native.
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (:require
    #?(:clj [clojure.set :as set])
    #?(:clj [com.fulcrologic.fulcro.react.hooks-context :as hooks-ctx])
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    #?(:cljs ["react" :as react])
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.application :as app])
  #?(:clj (:import (cljs.tagged_literals JSValue))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLJ Headless Hooks Emulation
;;
;; For headless mode on the JVM, we emulate React hooks with proper state
;; persistence across render frames. This enables testing hook-based components.
;;
;; Key concepts:
;; - Component identity via tree path (e.g., [0 :key "user-1" 2])
;; - Hook state persisted in app's runtime-atom (not global - supports multiple apps)
;; - Effects collected during render, executed after render completes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (do
     ;; ==========================================================================
     ;; Render-time context - set during component render (transient per-render)
     ;; ==========================================================================
     ;; hooks-ctx/*current-path* is defined in hooks-context.clj to break cyclic dependency
     ;; with dom-server. headless.clj binds that var directly.

     (def ^:dynamic *hook-index*
       "Atom tracking the current hook index within the rendering component."
       nil)

     (def ^:dynamic *child-index*
       "Atom tracking the current child index for path computation."
       nil)

     (def ^:dynamic *pending-effects*
       "Atom collecting effects to run after render completes."
       nil)

     (def ^:dynamic *rendered-paths*
       "Atom collecting all component paths rendered in this frame (for cleanup)."
       nil)

     ;; ==========================================================================
     ;; HeadlessRef - mimics React ref with mutable .-current property
     ;; Uses an atom internally because deftype mutable fields can't be set
     ;; from outside the type's own methods in Clojure.
     ;; ==========================================================================
     (deftype HeadlessRef [current-atom]
       clojure.lang.IDeref
       (deref [_] @current-atom)
       clojure.lang.ILookup
       (valAt [_ k] (when (= k :current) @current-atom))
       (valAt [_ k not-found] (if (= k :current) @current-atom not-found))
       Object
       (toString [_] (str "#<HeadlessRef " @current-atom ">")))

     (defn- get-ref-atom
       "Get the internal atom of a HeadlessRef."
       [^HeadlessRef ref]
       (.-current-atom ref))

     (defn- make-ref [initial]
       (HeadlessRef. (atom initial)))

     ;; ==========================================================================
     ;; Hook Registry Access - stored in app's runtime-atom under ::hook-registry
     ;; Structure: {path -> {:hooks [...] :effects [...] :mounted? bool}}
     ;; ==========================================================================
     (defn- get-hook-registry
       "Get the hook registry from the current app's runtime-atom."
       []
       (when-let [app comp/*app*]
         (-> app :com.fulcrologic.fulcro.application/runtime-atom deref ::hook-registry)))

     (defn- swap-hook-registry!
       "Swap the hook registry in the current app's runtime-atom."
       [f & args]
       (when-let [app comp/*app*]
         (apply swap! (:com.fulcrologic.fulcro.application/runtime-atom app)
           update ::hook-registry f args)))

     ;; ==========================================================================
     ;; Hook infrastructure helpers
     ;; ==========================================================================
     (defn- advance-hook-index!
       "Increment and return the current hook index."
       []
       (let [idx @*hook-index*]
         (swap! *hook-index* inc)
         idx))

     (defn- deps-equal?
       "Compare two dependency arrays like React's areHookInputsEqual.
        Uses identical? for reference equality."
       [prev-deps next-deps]
       (cond
         (nil? prev-deps) false                             ; First render or no deps
         (nil? next-deps) false                             ; No deps means always run
         (not= (count prev-deps) (count next-deps)) false
         :else (every? true? (map identical? prev-deps next-deps))))

     (defn- get-component-state
       "Get the hook state map for a component at the given path."
       [path]
       (get (get-hook-registry) path))

     (defn- mounting?
       "Returns true if the component at path is being mounted (first render)."
       [path]
       (not (:mounted? (get-component-state path))))

     (defn- ensure-component-state!
       "Ensure a component state entry exists for the given path."
       [path]
       (when (and comp/*app* (nil? (get (get-hook-registry) path)))
         (swap-hook-registry! assoc path {:hooks [] :effects [] :mounted? false})))

     (defn- get-hook
       "Get the hook at the given index for a component."
       [path idx]
       (get-in (get-hook-registry) [path :hooks idx]))

     (defn- get-hook-with-app
       "Get the hook at the given index for a component, using an explicit app reference.
        Used by setters that execute outside of render context."
       [app path idx]
       (when-let [runtime-atom (:com.fulcrologic.fulcro.application/runtime-atom app)]
         (get-in @runtime-atom [::hook-registry path :hooks idx])))

     (defn- set-hook!
       "Set the hook value at the given index for a component."
       [path idx hook-data]
       (swap-hook-registry! assoc-in [path :hooks idx] hook-data))

     (defn- set-hook-with-app!
       "Set the hook value at the given index for a component, using an explicit app reference.
        Used by setters that execute outside of render context."
       [app path idx hook-data]
       (when-let [runtime-atom (:com.fulcrologic.fulcro.application/runtime-atom app)]
         (swap! runtime-atom assoc-in [::hook-registry path :hooks idx] hook-data)))

     (defn- get-effect
       "Get the effect at the given index for a component."
       [path idx]
       (get-in (get-hook-registry) [path :effects idx]))

     (defn- set-effect!
       "Set the effect at the given index for a component."
       [path idx effect-data]
       (swap-hook-registry! assoc-in [path :effects idx] effect-data))

     (defn mark-mounted!
       "Mark a component as mounted (no longer in first render).
        Called by headless.clj after component renders."
       [path]
       (swap-hook-registry! assoc-in [path :mounted?] true))

     (defn record-rendered-path!
       "Record that a path was rendered in this frame.
        Called by headless.clj during render traversal."
       [path]
       (when *rendered-paths*
         (swap! *rendered-paths* conj path)))

     (defn hooks-enabled?
       "Returns true if hooks infrastructure is active (we're in a headless render with app bound)."
       []
       (and (some? comp/*app*)
         (some? hooks-ctx/*current-path*)))

     (defn run-pending-effects!
       "Execute all pending effects collected during render.
        Runs cleanup (destroy) functions first, then setup (create) functions.
        Called by headless.clj after render completes."
       []
       (when *pending-effects*
         (doseq [{:keys [path idx create deps prev-destroy]} @*pending-effects*]
           ;; Run previous cleanup first
           (when prev-destroy
             (try
               (prev-destroy)
               (catch Exception e
                 (log/error e "Error running effect cleanup for path" path "idx" idx))))
           ;; Run new effect, capture cleanup fn
           (let [destroy (try
                           (create)
                           (catch Exception e
                             (log/error e "Error running effect for path" path "idx" idx)
                             nil))]
             (set-effect! path idx {:create create :destroy destroy :deps deps})))
         (reset! *pending-effects* [])))

     (defn cleanup-unmounted-components!
       "Clean up effects for components that were not rendered in this frame.
        Called by headless.clj after render completes."
       [prev-paths current-paths]
       (let [unmounted (set/difference prev-paths current-paths)]
         (doseq [path unmounted]
           (when-let [component-state (get (get-hook-registry) path)]
             ;; Run all effect cleanups for this component
             (doseq [effect (vals (:effects component-state))]
               (when-let [destroy (:destroy effect)]
                 (try
                   (destroy)
                   (catch Exception e
                     (log/error e "Error running effect cleanup for unmounted component" path)))))
             ;; Remove component from registry
             (swap-hook-registry! dissoc path)))))

     (defn get-all-component-paths
       "Get all component paths currently in the hook registry."
       []
       (set (keys (get-hook-registry))))

     ;; ==========================================================================
     ;; Isolated Component Testing Support
     ;; For testing individual components without a full headless app
     ;; ==========================================================================

     (defn make-rendering-context
       "Create a minimal context for isolated component rendering with hooks support.
        This creates a mock 'app' structure that provides just enough to make hooks work,
        without the overhead of a full headless test app.

        Returns a context map that can be used with `render-with-hooks`.

        Example:
        ```clojure
        (let [ctx (hooks/make-rendering-context)]
          (hooks/render-with-hooks ctx
            (fn [] (ui-my-component {:some \"props\"}))))
        ```"
       []
       (let [runtime-atom (atom {::hook-registry {}})]
         {:com.fulcrologic.fulcro.application/runtime-atom runtime-atom
          :com.fulcrologic.fulcro.application/state-atom   (atom {})
          ::context-type                                   ::rendering-context}))

     (defn set-render-callback!
       "Set a render callback on a context. Called by state setters after updating state.
        Used internally by mount-component to enable auto-re-render."
       [ctx callback]
       (swap! (:com.fulcrologic.fulcro.application/runtime-atom ctx)
         assoc ::render-callback callback))

     (defn get-render-callback
       "Get the render callback from a context, if one is set."
       [ctx]
       (-> ctx :com.fulcrologic.fulcro.application/runtime-atom deref ::render-callback))

     (defn get-context-hook-registry
       "Get the hook registry from a rendering context.
        Useful for inspecting hook state in tests."
       [ctx]
       (-> ctx :com.fulcrologic.fulcro.application/runtime-atom deref ::hook-registry))

     (defn clear-context-hooks!
       "Clear all hooks from a rendering context. Useful for test isolation between renders."
       [ctx]
       (swap! (:com.fulcrologic.fulcro.application/runtime-atom ctx)
         assoc ::hook-registry {}))

     (defn render-with-hooks
       "Render a component within a rendering context, with full hooks support.
        Sets up all the hook bindings and executes effects after render.

        `ctx` - A context from `make-rendering-context`
        `render-fn` - A zero-arg function that performs the render (e.g., calling a factory)

        Returns the result of render-fn.

        Example:
        ```clojure
        (let [ctx (hooks/make-rendering-context)
              factory (comp/factory MyComponent)]
          ;; First render - hooks initialize
          (hooks/render-with-hooks ctx
            (fn [] (factory {:id 1 :name \"Test\"})))

          ;; Subsequent renders - hooks persist
          (hooks/render-with-hooks ctx
            (fn [] (factory {:id 1 :name \"Updated\"}))))
        ```"
       [ctx render-fn]
       (let [prev-paths (set (keys (get-context-hook-registry ctx)))]
         (binding [comp/*app*               ctx
                   comp/*parent*            nil
                   comp/*shared*            {}
                   hooks-ctx/*current-path* []
                   *hook-index*             (atom 0)
                   *child-index*            (atom -1)
                   *pending-effects*        (atom [])
                   *rendered-paths*         (atom #{})]
           (let [result        (render-fn)
                 current-paths @*rendered-paths*]
             ;; Clean up effects for unmounted components
             (cleanup-unmounted-components! prev-paths current-paths)
             ;; Run pending effects from this render
             (run-pending-effects!)
             result))))))

#?(:clj
   (defmacro with-rendering-context
     "Macro for convenient isolated component rendering with hooks support.
      Executes body within a rendering context with all hooks properly bound.

      Usage:
      ```clojure
      (let [ctx (hooks/make-rendering-context)]
        ;; First render
        (hooks/with-rendering-context [ctx]
          (ui-my-component {:id 1}))

        ;; Subsequent render with same context - hooks persist
        (hooks/with-rendering-context [ctx]
          (ui-my-component {:id 1})))
      ```

      The context must be created outside with `make-rendering-context` so it can
      persist across multiple renders (enabling hooks state to accumulate).

      For a single-render test, you can also create the context inline:
      ```clojure
      (hooks/with-rendering-context [(hooks/make-rendering-context)]
        (ui-my-component {:id 1}))
      ```"
     [[ctx] & body]
     `(render-with-hooks ~ctx (fn [] ~@body))))

(defn useState
  "A simple CLJC wrapper around React/useState. Returns a JS vector for speed. You probably want use-state, which is more
  convenient.

  React docs: https://reactjs.org/docs/hooks-reference.html#usestate"
  [initial-value]
  #?(:cljs (react/useState initial-value)
     :clj  (if-not (hooks-enabled?)
             ;; Fallback for non-headless CLJ (e.g., macro expansion time)
             (let [v (if (fn? initial-value) (initial-value) initial-value)]
               (to-array [v (fn [_] nil)]))
             ;; Proper headless hook implementation
             ;; IMPORTANT: The setter must be stable (same object) across renders,
             ;; just like React's setState. We store it in the hook on mount.
             (let [app      comp/*app*
                   path     hooks-ctx/*current-path*
                   idx      (advance-hook-index!)
                   existing (get-hook path idx)]
               (if existing
                 ;; Update: return existing state AND the same stable setter
                 (to-array [(:value existing) (:setter existing)])
                 ;; Mount: create stable setter and initialize state
                 (let [;; Create setter once on mount - it captures app, path, idx
                       setter (fn [new-val]
                                (let [current-val (:value (get-hook-with-app app path idx))
                                      resolved    (if (fn? new-val) (new-val current-val) new-val)]
                                  (set-hook-with-app! app path idx
                                    (assoc (get-hook-with-app app path idx)
                                      :value resolved))
                                  ;; Trigger re-render if a callback is registered
                                  (when-let [callback (get-render-callback app)]
                                    (callback))))
                       v      (if (fn? initial-value) (initial-value) initial-value)]
                   (set-hook! path idx {:type :state :value v :setter setter})
                   (to-array [v setter])))))))

(defn use-state
  "A simple wrapper around React/useState. Returns a cljs vector for easy destructuring.

  `initial-value` can be a function.

  React docs: https://reactjs.org/docs/hooks-reference.html#usestate"
  [initial-value]
  #?(:cljs (into-array (react/useState initial-value))
     :clj  (if-not (hooks-enabled?)
             ;; Fallback for non-headless CLJ
             (let [v (if (fn? initial-value) (initial-value) initial-value)]
               [v (fn [_] nil)])
             ;; Proper headless hook implementation
             ;; IMPORTANT: The setter must be stable (same object) across renders,
             ;; just like React's setState. We store it in the hook on mount.
             (let [app      comp/*app*
                   path     hooks-ctx/*current-path*
                   idx      (advance-hook-index!)
                   existing (get-hook path idx)]
               (if existing
                 ;; Update: return existing state AND the same stable setter
                 [(:value existing) (:setter existing)]
                 ;; Mount: create stable setter and initialize state
                 (let [;; Create setter once on mount - it captures app, path, idx
                       setter (fn [new-val]
                                (let [current-val (:value (get-hook-with-app app path idx))
                                      resolved    (if (fn? new-val) (new-val current-val) new-val)]
                                  (set-hook-with-app! app path idx
                                    (assoc (get-hook-with-app app path idx)
                                      :value resolved))
                                  ;; Trigger re-render if a callback is registered
                                  (when-let [callback (get-render-callback app)]
                                    (callback))))
                       v      (if (fn? initial-value) (initial-value) initial-value)]
                   (set-hook! path idx {:type :state :value v :setter setter})
                   [v setter]))))))

(defn useEffect
  "A CLJC wrapper around js/React.useEffect that does NO conversion of
  dependencies. You probably want the macro use-effect instead.

  React docs: https://reactjs.org/docs/hooks-reference.html#useeffect"
  ([f]
   #?(:cljs (react/useEffect f)
      :clj  (useEffect f nil)))
  ([f deps]
   #?(:cljs (react/useEffect f deps)
      :clj  (when (hooks-enabled?)
              (let [path        hooks-ctx/*current-path*
                    idx         (advance-hook-index!)
                    prev-effect (get-effect path idx)
                    ;; deps can be nil (always run), [] (run once), or [a b c] (run when changed)
                    ;; Convert js array to vector if needed
                    deps-vec    (when deps (vec deps))
                    should-run? (or (nil? prev-effect)      ; First mount
                                  (nil? deps-vec)           ; No deps = always run
                                  (not (deps-equal? (:deps prev-effect) deps-vec)))]
                (when should-run?
                  ;; Schedule effect to run after render completes
                  (swap! *pending-effects* conj
                    {:path         path
                     :idx          idx
                     :create       f
                     :deps         deps-vec
                     :prev-destroy (:destroy prev-effect)}))
                ;; Always update deps even if we don't run, so next render can compare
                (when (and prev-effect (not should-run?))
                  (set-effect! path idx (assoc prev-effect :deps deps-vec))))))))

#?(:clj
   (defmacro use-effect
     "A simple macro wrapper around React/useEffect that does compile-time conversion of `dependencies` to a js-array
     for convenience without affecting performance.

      React docs: https://reactjs.org/docs/hooks-reference.html#useeffect"
     ([f]
      `(useEffect ~f))
     ([f dependencies]
      (if (enc/compiling-cljs?)
        (let [deps (cond
                     (nil? dependencies) nil
                     (instance? JSValue dependencies) dependencies
                     :else (JSValue. dependencies))]
          `(useEffect ~f ~deps))
        `(useEffect ~f ~dependencies)))))

(defn use-context
  "A simple wrapper around the RAW React/useContext. You should probably prefer the context support from c.f.f.r.context."
  [ctx]
  #?(:cljs (react/useContext ctx)
     :clj  nil))                                            ;; No context in headless mode

(defn use-reducer
  "A simple wrapper around React/useReducer. Returns a cljs vector for easy destructuring

  React docs: https://reactjs.org/docs/hooks-reference.html#usecontext"
  ([reducer initial-arg]
   #?(:cljs (into-array (react/useReducer reducer initial-arg))
      :clj  [initial-arg (fn [_] nil)]))
  ([reducer initial-arg init]
   #?(:cljs (into-array (react/useReducer reducer initial-arg init))
      :clj  [(if init (init initial-arg) initial-arg) (fn [_] nil)])))

(defn use-callback
  "A simple wrapper around React/useCallback. Converts args to js array before send.

  React docs: https://reactjs.org/docs/hooks-reference.html#usecallback"
  ([cb]
   #?(:cljs (react/useCallback cb)
      :clj  (if-not (hooks-enabled?)
              cb
              ;; No deps = never memoize (return same cb always would be wrong)
              ;; Actually useCallback with no deps returns a stable reference
              (let [path     hooks-ctx/*current-path*
                    idx      (advance-hook-index!)
                    existing (get-hook path idx)]
                (if existing
                  (:value existing)
                  (do (set-hook! path idx {:type :callback :value cb :deps nil})
                      cb))))))
  ([cb deps]
   #?(:cljs (react/useCallback cb (to-array deps))
      :clj  (if-not (hooks-enabled?)
              cb
              (let [path     hooks-ctx/*current-path*
                    idx      (advance-hook-index!)
                    existing (get-hook path idx)
                    deps-vec (vec deps)]
                (if (and existing (deps-equal? (:deps existing) deps-vec))
                  ;; Deps unchanged, return memoized callback
                  (:value existing)
                  ;; Deps changed or first render, store new callback
                  (do (set-hook! path idx {:type :callback :value cb :deps deps-vec})
                      cb)))))))

(defn use-memo
  "A simple wrapper around React/useMemo. Converts args to js array before send.

   NOTE: React does NOT guarantee it won't re-create the value during the lifetime of the
   component, so it is sorta crappy in terms of actual memoization. Purely for optimizations, not
   for guarantees.

  React docs: https://reactjs.org/docs/hooks-reference.html#usememo"
  ([value-factory-fn]
   #?(:cljs (react/useMemo value-factory-fn)
      :clj  (if-not (hooks-enabled?)
              (value-factory-fn)
              ;; No deps = compute fresh every time (matches React behavior)
              (let [path hooks-ctx/*current-path*
                    idx  (advance-hook-index!)
                    v    (value-factory-fn)]
                (set-hook! path idx {:type :memo :value v :deps nil})
                v))))
  ([value-factory-fn dependencies]
   #?(:cljs (react/useMemo value-factory-fn (to-array dependencies))
      :clj  (if-not (hooks-enabled?)
              (value-factory-fn)
              (let [path     hooks-ctx/*current-path*
                    idx      (advance-hook-index!)
                    existing (get-hook path idx)
                    deps-vec (vec dependencies)]
                (if (and existing (deps-equal? (:deps existing) deps-vec))
                  ;; Deps unchanged, return memoized value
                  (:value existing)
                  ;; Deps changed or first render, compute and store
                  (let [v (value-factory-fn)]
                    (set-hook! path idx {:type :memo :value v :deps deps-vec})
                    v)))))))

(defn use-ref
  "A simple wrapper around React/useRef.

  React docs: https://reactjs.org/docs/hooks-reference.html#useref"
  ([] (use-ref nil))
  ([value]
   #?(:cljs (react/useRef value)
      :clj  (if-not (hooks-enabled?)
              ;; Fallback for non-headless CLJ
              (make-ref value)
              ;; Proper headless hook implementation - refs persist across renders
              (let [path     hooks-ctx/*current-path*
                    idx      (advance-hook-index!)
                    existing (get-hook path idx)]
                (if existing
                  ;; Return existing ref (same object across renders)
                  (:ref existing)
                  ;; Mount: create new ref
                  (let [ref (make-ref value)]
                    (set-hook! path idx {:type :ref :ref ref})
                    ref)))))))

(defn ref-current
  "Get the current value of a ref. Works in both CLJ and CLJS.
   This is a CLJC-compatible alternative to (.-current ref).

   Usage:
   ```
   (let [my-ref (use-ref 0)]
     (ref-current my-ref))  ;; => 0
   ```"
  [ref]
  #?(:cljs (.-current ref)
     :clj  (if (instance? HeadlessRef ref)
             @ref
             (.-current ref))))

(defn set-ref-current!
  "Set the current value of a ref. Works in both CLJ and CLJS.
   This is a CLJC-compatible alternative to (set! (.-current ref) value).

   Usage:
   ```
   (let [my-ref (use-ref 0)]
     (set-ref-current! my-ref 42)
     (ref-current my-ref))  ;; => 42
   ```"
  [ref value]
  #?(:cljs (set! (.-current ref) value)
     :clj  (if (instance? HeadlessRef ref)
             (reset! (get-ref-atom ref) value)
             (set! (.-current ref) value))))

(defn use-imperative-handle
  "A simple wrapper around React/useImperativeHandle.

  React docs: https://react.dev/reference/react/useImperativeHandle"
  ([ref f]
   #?(:cljs (react/useImperativeHandle ref f)
      :clj  nil))                                           ;; No-op for headless
  ([ref f args]
   #?(:cljs (react/useImperativeHandle ref f (to-array args))
      :clj  nil)))                                          ;; No-op for headless

(defn use-layout-effect
  "A simple wrapper around React/useLayoutEffect.

  React docs: https://reactjs.org/docs/hooks-reference.html#uselayouteffect"
  ([f]
   #?(:cljs (react/useLayoutEffect f)
      :clj  nil))                                           ;; No-op for headless
  ([f args]
   #?(:cljs (react/useLayoutEffect f (to-array args))
      :clj  nil)))                                          ;; No-op for headless

(defn use-debug-value
  "A simple wrapper around React/useDebugValue.

  React docs: https://reactjs.org/docs/hooks-reference.html#uselayouteffect"
  ([value]
   #?(:cljs (react/useDebugValue value)
      :clj  nil))                                           ;; No-op for headless
  ([value formatter]
   #?(:cljs (react/useDebugValue value formatter)
      :clj  nil)))                                          ;; No-op for headless

(defn use-deferred-value
  "A simple wrapper around React/useDeferredValue.

  React docs: https://reactjs.org/docs/hooks-reference.html#usedeferredvalue"
  [value]
  #?(:cljs (react/useDeferredValue value)
     :clj  value))                                          ;; Just return the value directly

(defn use-transition
  "A simple wrapper around React/useTransition.

  React docs: https://reactjs.org/docs/hooks-reference.html#usetransition"
  [value]
  #?(:cljs (into-array (react/useTransition value))
     :clj  [false (fn [f] (f))]))                           ;; [isPending, startTransition]

(defn use-id
  "A simple wrapper around React/useId. See also use-generated-id, which is a Fulcro-specific function for generating
   random uuids.

  React docs: https://reactjs.org/docs/hooks-reference.html#useid"
  []
  #?(:cljs (react/useId)
     :clj  (str ":r" (hash (java.util.UUID/randomUUID)) ":")))

(defn use-sync-external-store
  "A simple wrapper around React/useSyncExternalStore.

  React docs: https://reactjs.org/docs/hooks-reference.html#usesyncexternalstore"
  ([subscribe get-snapshot get-server-ss]
   #?(:cljs (react/useSyncExternalStore subscribe get-snapshot get-server-ss)
      :clj  (if get-server-ss (get-server-ss) (get-snapshot))))
  ([subscribe get-snapshot]
   #?(:cljs (react/useSyncExternalStore subscribe get-snapshot)
      :clj  (get-snapshot))))

(defn use-insertion-effect
  "A simple wrapper around React/useInsertionEffect.

  React docs: https://reactjs.org/docs/hooks-reference.html#useinsertioneffect"
  [didUpdate]
  #?(:cljs (react/useInsertionEffect didUpdate)
     :clj  nil))                                            ;; No-op for headless

#?(:clj
   (defmacro use-lifecycle
     "A macro shorthand that evaluates to low-level js at compile time for
     `(use-effect (fn [] (when setup (setup)) (when teardown teardown)) [])`"
     ([setup] `(use-lifecycle ~setup nil))
     ([setup teardown]
      (cond
        (and setup teardown) `(use-effect (fn [] (~setup) ~teardown) [])
        setup `(use-effect (fn [] (~setup) ~(when (enc/compiling-cljs?) 'js/undefined)) [])
        teardown `(use-effect (fn [] ~teardown) [])))))

(let [id (fn [] (tempid/uuid))]
  (defn use-generated-id
    "Returns a constant ident with a generated ID component."
    []
    #?(:cljs (aget (useState id) 0)
       :clj  (id))))

(defn use-gc
  "Effect handler. Creates an effect that will garbage-collect the given ident from fulcro app state on cleanup, and
  will follow any `edges` (a set of keywords) and remove any things pointed through those keywords as well. See
  normalized-state's `remove-entity`.

  ```
  (defsc NewRoot [this props]
    {:use-hooks? true}
    (let [generated-id (hooks/use-generated-id)
          f (use-fulcro-mount this {:child-class SomeChild
                                    :initial-state-params {:id generated-id})]
      ;; will garbage-collect the floating root child on unmount
      (use-gc this [:child/id generated-id] #{})
      (f props)))
  ```
  "
  [this-or-app ident edges]
  (use-lifecycle
    nil
    (fn []
      (let [state (-> this-or-app comp/any->app :com.fulcrologic.fulcro.application/state-atom)]
        (swap! state fns/remove-entity ident edges)))))

(defn- pcs [app component prior-props-tree-or-ident]
  (let [ident           (if (eql/ident? prior-props-tree-or-ident)
                          prior-props-tree-or-ident
                          (comp/get-ident component prior-props-tree-or-ident))
        state-map       (rapp/current-state app)
        starting-entity (get-in state-map ident)
        query           (comp/get-query component state-map)]
    (fdn/db->tree query starting-entity state-map)))

(defn- use-db-lifecycle [app component current-props-tree set-state!]
  (let [[id _] (use-state #?(:cljs (random-uuid) :clj (java.util.UUID/randomUUID)))]
    (use-lifecycle
      (fn []
        (let [state-map (rapp/current-state app)
              ident     (comp/get-ident component current-props-tree)
              exists?   (map? (get-in state-map ident))]
          (when-not exists?
            (merge/merge-component! app component current-props-tree))
          (rapp/add-render-listener! app id
            (fn [app _]
              (let [props (pcs app component ident)]
                (set-state! props))))))
      (fn [] (rapp/remove-render-listener! app id)))))

(defn use-component
  "Use Fulcro from raw React. This is a Hook effect/state combo that will connect you to the transaction/network/data
  processing of Fulcro, but will not rely on Fulcro's render. Thus, you can embed the use of the returned props in any
  stock React context. Technically, you do not have to use Fulcro components for rendering, but they are necessary to define the
  query/ident/initial-state for startup and normalization. You may also use this within normal (Fulcro)
  components to generate dynamic components on-the-fly (see `nc`).

  The arguments are:

  `app` - A Fulcro app
  `component` - A component with query/ident. Queries MUST have co-located normalization info. You
              can create this with normal `defsc` or as an anonymous component via `raw.components/nc`.
  `options` - A map of options, containing:
    * `:initial-params` - The parameters to use when getting the initial state of the component. See `comp/get-initial-state`.
      If no initial state exists on the top-level component, then an empty map will be used. This will mean your props will be
      empty to start.
    * `initialize?` - A boolean (default true). If true then the initial state of the component will be used to pre-populate the component's state
      in the app database.
    * `:keep-existing?` - A boolean. If true, then the state of the component will not be initialized if there
      is already data at the component's ident (which will be computed using the initial state params provided, if
      necessary).
    * `:ident` - Only needed if you are NOT initializing state, AND the component has a dynamic ident.

  Returns the props from the Fulcro database. The component using this function will automatically refresh after Fulcro
  transactions run (Fulcro is not a watched-atom system. Updates happen at transaction boundaries).

  MAY return nil if no data is at that component's ident.

  See also `use-root`.
  "
  [app component
   {:keys [initialize? initial-params keep-existing?]
    :or   {initial-params {}}
    :as   options}]
  #?(:cljs
     (let [prior-props-ref (use-ref nil)
           get-props       (fn [ident]
                             (rc/get-traced-props (rapp/current-state app) component
                               ident
                               (.-current prior-props-ref)))
           [current-props
            set-props!] (use-state
                          (fn initialize-component-state []
                            (let [initial-entity (comp/get-initial-state component initial-params)
                                  initial-ident  (or (:ident options) (rc/get-ident component initial-entity))]
                              (rapp/maybe-merge-new-component! app component initial-entity options)
                              (let [initial-props (get-props initial-ident)]
                                (set! (.-current prior-props-ref) initial-props)
                                initial-props))))
           current-ident   (or (:ident options) (rc/get-ident component current-props))]
       (use-effect
         (fn [] (let [listener-id (random-uuid)]
                  (rapp/add-render-listener! app listener-id
                    (fn [app _]
                      (let [props (get-props current-ident)]
                        (when-not (identical? (.-current prior-props-ref) props)
                          (set! (.-current prior-props-ref) props)
                          (set-props! props)))))
                  (fn use-tree-remove-render-listener* []
                    (rapp/remove-render-listener! app listener-id)
                    (set! (.-current prior-props-ref) nil))))
         [(hash current-ident)])
       current-props)))

(defn use-root
  "Use a root key and component as a subtree managed by Fulcro from raw React. The `root-key` must be a unique
   (namespace recommended) key among all keys used within the application, since the root of the database is where it
   will live.

   The `component` should be a real Fulcro component or a generated normalizing component from `nc` (or similar).

   Returns the props (not including `root-key`) that satisfy the query of `component`. MAY return nil if no data is available.

   See also `use-component`.
  "
  [app root-key component {:keys [initialize? keep-existing? initial-params] :as options}]
  (let [prior-props-ref (use-ref nil)
        get-props       #(rapp/get-root-subtree-props app root-key component (.-current prior-props-ref))
        [current-props set-props!] (use-state (fn []
                                                (rapp/maybe-merge-new-root! app root-key component options)
                                                (let [initial-props (get-props)]
                                                  (set! (.-current prior-props-ref) initial-props)
                                                  initial-props)))]
    (use-lifecycle
      (fn [] (rapp/add-render-listener! app root-key (fn use-root-render-listener* [app _]
                                                       (let [props (get-props)]
                                                         (when-not (identical? (.-current prior-props-ref) props)
                                                           (set! (.-current prior-props-ref) props)
                                                           (set-props! props))))))
      (fn use-tree-remove-render-listener* [] (rapp/remove-root! app root-key)))
    (get current-props root-key)))

(defn use-uism
  "Use a UISM as an effect hook. This will set up the given state machine under the given ID, and start it (if not
   already started). Your initial state handler MUST set up actors and otherwise initialize based on options.

   If the machine is already started at the given ID then this effect will send it an `:event/remounted` event.

   You MUST include `:componentName` in each of your actor's normalizing component options (e.g. `(nc query {:componentName ::uniqueName})`)
   because UISM requires component appear in the component registry (components cannot be safely stored in app state, just their
   names).

   `options` is a map that can contain `::uism/actors` as an actor definition map (see `begin!`). Any other keys in options
   are sent as the initial event data when the machine is started.

   Returns a map that contains the actor props (by actor name) and the current state of the state machine as `:active-state`."
  [app state-machine-definition id options]
  (let [[uism-data set-uism-data!] (use-state (fn initialize-component-state []
                                                (uism/current-state-and-actors (app/current-state app) id)))]
    (use-lifecycle
      (fn []
        (uism/add-uism! app {:state-machine-definition state-machine-definition
                             :id                       id
                             :receive-props            set-uism-data!
                             :actors                   (::uism/actors options)
                             :initial-event-data       (dissoc options ::uism/actors)}))
      (fn [] (uism/remove-uism! app id)))
    uism-data))
