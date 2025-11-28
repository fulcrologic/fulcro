(ns com.fulcrologic.fulcro.headless
  "Headless Fulcro application support for testing and server-side execution.

   This namespace provides utilities for running Fulcro applications in headless mode
   on the JVM. Key features:

   - Synchronous transaction processing (all operations complete before returning)
   - Render frame capture (inspect before/after state for assertions)
   - Raw dom-server Element tree capture (convert to hiccup or other formats as needed)
   - Controlled execution (step through transaction phases)
   - Integration with Ring handlers and Pathom parsers via loopback remotes

   Example:
   ```clojure
   (require '[com.fulcrologic.fulcro.headless :as h])
   (require '[com.fulcrologic.fulcro.headless.hiccup :as hic])
   (require '[com.fulcrologic.fulcro.headless.loopback-remotes :as lr])

   (def app (h/build-test-app
              {:root-class Root
               :remotes {:remote (lr/sync-remote my-handler)}}))

   ;; Transactions are synchronous
   (comp/transact! app [(my-mutation)])

   ;; Inspect render frames
   (let [frame (h/last-frame app)
         hiccup (hic/rendered-tree->hiccup (:rendered frame))]
     (is (= expected (:tree frame)))
     (hic/click! (hic/find-by-id hiccup \"my-button\")))
   ```"
  (:require
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fnorm]
    [com.fulcrologic.fulcro.algorithms.tx-processing.synchronous-tx-processing :as stx]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom-server :as dom]
    [com.fulcrologic.fulcro.headless.hiccup :as hic]
    [com.fulcrologic.fulcro.inspect.tools :as tools]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.react.hooks-context :as hooks-ctx]
    [taoensso.timbre :as log]))

(def ^:dynamic *render-frame-max*
  "Default maximum number of render frames to keep in history."
  10)

(declare render-component-to-element)

(defn- render-children-with-index
  "Render a sequence of children, tracking child indices for hook path computation."
  [children]
  (let [child-counter (atom -1)]
    (mapv (fn [child]
            (swap! child-counter inc)
            (binding [hooks/*child-index* (atom @child-counter)]
              (render-component-to-element child)))
      children)))

(defn- render-component-to-element
  "Recursively render a component instance to a dom-server Element tree.
   Must be called within a dynamic binding context for *app*, *parent*, *shared*.
   When hooks are enabled, also tracks component paths for hook state management."
  [x]
  (cond
    (nil? x) nil

    (dom/element? x)
    ;; It's already a dom-server Element, but recurse into children with index tracking
    (update x :children render-children-with-index)

    (rc/component-instance? x)
    ;; Render the component with hook context
    (when-let [render (rc/component-options x :render)]
      (let [;; Compute path for this component
            child-idx    (when hooks/*child-index* @hooks/*child-index*)
            react-key    (some-> x :props :fulcro$reactKey)
            path-segment (if react-key
                           [:key react-key]
                           (or child-idx 0))
            parent-path  (or hooks-ctx/*current-path* [])
            new-path     (conj parent-path path-segment)]
        ;; Render with hook context bindings
        (binding [hooks-ctx/*current-path* new-path
                  hooks/*hook-index*       (atom 0)
                  hooks/*child-index*      (atom -1)]
          ;; Record this path as rendered (for cleanup tracking)
          (hooks/record-rendered-path! new-path)
          (let [output (render x)]
            ;; Mark component as mounted after first successful render
            (hooks/mark-mounted! new-path)
            (cond
              (nil? output) nil
              (vector? output) (render-children-with-index output)
              :else (render-component-to-element output))))))

    (vector? x)
    (render-children-with-index x)

    ;; Pass through valid dom-server record types unchanged
    (instance? com.fulcrologic.fulcro.dom_server.Text x) x
    (instance? com.fulcrologic.fulcro.dom_server.ReactText x) x
    (instance? com.fulcrologic.fulcro.dom_server.ReactEmpty x) x
    (instance? com.fulcrologic.fulcro.dom_server.ReactFragment x)
    (update x :elements render-children-with-index)

    ;; Strings and numbers should be wrapped in Text
    (or (string? x) (number? x))
    (dom/text-node (str x))

    :else
    (throw (ex-info "Invalid element in render tree. A component render function returned an invalid type."
             {:type (type x) :value x}))))

(defn- render-app-tree
  "Render the app's root component to a dom-server Element tree.
   Binds the proper dynamic vars for component rendering.
   Also sets up hooks context for stateful hook emulation."
  [app]
  (let [{:com.fulcrologic.fulcro.application/keys [state-atom runtime-atom]} app
        state-map  @state-atom
        {:com.fulcrologic.fulcro.application/keys [root-class root-factory]} @runtime-atom
        factory    (or root-factory
                     (when root-class
                       (comp/factory root-class)))
        ;; Get paths from previous render for cleanup tracking
        ;; Must access runtime-atom directly since comp/*app* not bound yet
        prev-paths (set (keys (get @runtime-atom ::hooks/hook-registry)))]
    (when (and root-class factory)
      (let [query (comp/get-query root-class state-map)
            tree  (fdn/db->tree query state-map state-map)]
        (binding [comp/*app*               app
                  comp/*parent*            nil
                  comp/*shared*            (comp/shared app)
                  ;; Hook context - root starts with empty path
                  hooks-ctx/*current-path* []
                  hooks/*hook-index*       (atom 0)
                  hooks/*child-index*      (atom -1)
                  hooks/*pending-effects*  (atom [])
                  hooks/*rendered-paths*   (atom #{})]
          ;; Call factory, then fully render any component instances to Elements
          (let [result        (render-component-to-element (factory tree))
                current-paths @hooks/*rendered-paths*]
            ;; Clean up effects for unmounted components
            (hooks/cleanup-unmounted-components! prev-paths current-paths)
            ;; Run pending effects from this render
            (hooks/run-pending-effects!)
            result))))))

(defn- capture-frame
  "Capture a render frame from the current app state.
   The frame contains:
   - :state - The normalized state map at render time
   - :tree - The denormalized props tree
   - :rendered - The raw dom-server Element tree (convert to hiccup with headless.hiccup/rendered-tree->hiccup)
   - :timestamp - System time in milliseconds"
  [app]
  (let [{:com.fulcrologic.fulcro.application/keys [state-atom runtime-atom]} app
        state-map @state-atom
        {:com.fulcrologic.fulcro.application/keys [root-class]} @runtime-atom
        query     (when root-class (comp/get-query root-class state-map))
        tree      (when query (fdn/db->tree query state-map state-map))
        rendered  (try
                    (render-app-tree app)
                    (catch Exception e
                      (log/trace e "Could not render tree (root component may not have render fn)")
                      nil))]
    {:state     state-map
     :tree      tree
     :rendered  rendered
     :timestamp (System/currentTimeMillis)}))

(defn- add-frame!
  "Add a frame to the render history, maintaining max size."
  [app frame]
  (let [runtime-atom (:com.fulcrologic.fulcro.application/runtime-atom app)
        max-frames   (or (::render-frame-max @runtime-atom) *render-frame-max*)]
    (swap! runtime-atom update ::render-frames
      (fn [frames]
        (let [new-frames (vec (cons frame (or frames [])))]
          (if (> (count new-frames) max-frames)
            (subvec new-frames 0 max-frames)
            new-frames))))))

(defn- frame-capturing-render
  "A render function that captures frames to history.
   This replaces the optimized-render! algorithm."
  [app options]
  (let [frame (capture-frame app)]
    (add-frame! app frame)
    frame))

;; =============================================================================
;; Render History
;; =============================================================================

(defn render-history
  "Get the render history (newest first).
   Each entry is a map with:
   - :state - The state map at render time
   - :tree - The denormalized props tree
   - :rendered - The raw dom-server Element tree
   - :timestamp - System time in milliseconds"
  [app]
  (-> app :com.fulcrologic.fulcro.application/runtime-atom deref ::render-frames))

(defn last-frame
  "Get the most recent render frame, or nil if none."
  [app]
  (first (render-history app)))

(defn frame-at
  "Get a specific render frame by index.
   `frame-index` is 0-indexed from most recent (0 = latest)."
  [app frame-index]
  (nth (render-history app) frame-index nil))

(defn clear-render-history!
  "Clear all captured render frames."
  [app]
  (swap! (:com.fulcrologic.fulcro.application/runtime-atom app)
    assoc ::render-frames []))

(defn render-frame!
  "Force a render and capture the frame in history.
   Returns the captured frame."
  [app]
  (let [render! (ah/app-algorithm app :render!)]
    (when render!
      (render! app {:force-root? true}))
    (last-frame app)))

(defn frames-since
  "Get all frames captured since the given timestamp."
  [app timestamp]
  (take-while #(> (:timestamp %) timestamp) (render-history app)))

;; =============================================================================
;; Convenience Accessors for Frame Data
;; =============================================================================

(defn state-at-render
  "Get the state map from a specific render frame.
   `frame-index` is 0-indexed from most recent (0 = latest)."
  [app frame-index]
  (:state (frame-at app frame-index)))

(defn tree-at-render
  "Get the denormalized tree from a specific render frame.
   `frame-index` is 0-indexed from most recent (0 = latest)."
  [app frame-index]
  (:tree (frame-at app frame-index)))

(defn rendered-at
  "Get the raw dom-server Element tree from a specific render frame.
   `frame-index` is 0-indexed from most recent (0 = latest).
   Use headless.hiccup/rendered-tree->hiccup to convert to hiccup."
  [app frame-index]
  (:rendered (frame-at app frame-index)))

(defn hiccup-frame
  "Get the rendered output as hiccup from a render frame.
   Convenience function that converts the dom-server Element tree to hiccup.

   With 1 arg: Returns hiccup from the most recent frame.
   With 2 args: Returns hiccup from the nth most recent frame (0 = latest).

   Example:
   ```clojure
   ;; Get most recent render as hiccup
   (hiccup-frame app)

   ;; Get the render from 2 frames ago
   (hiccup-frame app 2)
   ```"
  ([app]
   (hiccup-frame app 0))
  ([app n-steps-ago]
   (when-let [rendered (rendered-at app n-steps-ago)]
     (hic/rendered-tree->hiccup rendered))))

;; =============================================================================
;; Props Access
;; =============================================================================

(defn current-props
  "Get denormalized props for a component at the given ident.
   Useful for inspecting component state in tests."
  [app component-class ident]
  (fnorm/ui->props (rapp/current-state app) component-class ident))

(defn hiccup-for
  "Return the hiccup for the given component based on the `app` current state. You MUST supply
   the ident unless the component has a constant ident (or is Root)."
  ([app component-class]
   (hiccup-for app component-class (rc/get-ident component-class {})))
  ([app component-class ident]
   (let [state-map (rapp/current-state app)
         query     (comp/get-query component-class state-map)
         factory   (comp/factory component-class)
         tree      (fdn/db->tree query (if ident
                                         (get-in state-map ident)
                                         state-map) state-map)]
     (hic/rendered-tree->hiccup
       (binding [comp/*app*    app
                 comp/*parent* nil
                 comp/*shared* (comp/shared app)]
         (render-component-to-element (factory tree)))))))

;; =============================================================================
;; Isolated Component Testing (without full app)
;; =============================================================================

(defn render-component
  "Render a component instance (result of factory call) with hook support.
   Works with either a full headless app or a lightweight rendering context.

   `ctx` - Either a headless app from `build-test-app` or a rendering context from
           `hooks/make-rendering-context`
   `component-instance` - The result of calling a component factory, e.g., (ui-counter {:count 0})

   Returns the dom-server Element tree. Use `headless.hiccup/rendered-tree->hiccup` to convert
   to hiccup for assertions.

   Example with rendering context (isolated component testing):
   ```clojure
   (let [ctx (hooks/make-rendering-context)
         result (h/render-component ctx (ui-counter {:count 0}))]
     (is (= [:div ...] (hic/rendered-tree->hiccup result))))
   ```

   Example with headless app:
   ```clojure
   (let [app (h/build-test-app {:root-class Root})
         result (h/render-component app (ui-widget {:id 1}))]
     ...)
   ```"
  [ctx component-instance]
  (let [prev-paths (set (keys (hooks/get-context-hook-registry ctx)))]
    (binding [comp/*app*               ctx
              comp/*parent*            nil
              comp/*shared*            (or (comp/shared ctx) {})
              hooks-ctx/*current-path* []
              hooks/*hook-index*       (atom 0)
              hooks/*child-index*      (atom -1)
              hooks/*pending-effects*  (atom [])
              hooks/*rendered-paths*   (atom #{})]
      (let [result        (render-component-to-element component-instance)
            current-paths @hooks/*rendered-paths*]
        ;; Clean up effects for unmounted components
        (hooks/cleanup-unmounted-components! prev-paths current-paths)
        ;; Run pending effects from this render
        (hooks/run-pending-effects!)
        result))))

(defn render-component-hiccup
  "Convenience function: render a component instance and return hiccup directly.

   `ctx` - Either a headless app or a rendering context from `hooks/make-rendering-context`
   `component-instance` - The result of calling a component factory

   Example:
   ```clojure
   (let [ctx (hooks/make-rendering-context)
         hiccup (h/render-component-hiccup ctx (ui-counter {:label \"Count:\"}))]
     (is (= \"Count:\" (hic/element-text (hic/find-by-id hiccup \"label\")))))
   ```"
  [ctx component-instance]
  (hic/rendered-tree->hiccup (render-component ctx component-instance)))

;; =============================================================================
;; Mounted Component Handle (auto re-render on state changes)
;; =============================================================================

(defrecord MountedComponent [ctx factory props-atom hiccup-atom])

(defn mount-component
  "Mount a component for testing with automatic re-render on state changes.
   Returns a MountedComponent handle that tracks the current hiccup.

   Unlike `render-component`, this sets up a render callback so that when
   a state setter (from useState) is called, the component automatically
   re-renders. This makes tests more natural - you interact with the component
   and the hiccup updates automatically.

   `ctx` - A rendering context from `hooks/make-rendering-context`
   `factory` - The component factory function (e.g., ui-counter)
   `initial-props` - The initial props to pass to the factory

   Returns a MountedComponent record. Use `current-hiccup` to get the latest render.

   Example:
   ```clojure
   (let [ctx (hooks/make-rendering-context)
         mounted (h/mount-component ctx ui-counter {:label \"Count:\"})]
     ;; Check initial render
     (is (= \"0\" (hic/element-text (hic/find-by-id (h/current-hiccup mounted) \"count\"))))

     ;; Click increment - re-renders automatically!
     (hic/click! (hic/find-by-id (h/current-hiccup mounted) \"inc\"))

     ;; State is updated
     (is (= \"1\" (hic/element-text (hic/find-by-id (h/current-hiccup mounted) \"count\")))))
   ```"
  [ctx factory initial-props]
  (let [props-atom      (atom initial-props)
        hiccup-atom     (atom nil)
        mounted         (->MountedComponent ctx factory props-atom hiccup-atom)
        ;; Create render callback that re-renders the component
        render-callback (fn []
                          (let [result (render-component ctx (factory @props-atom))]
                            (reset! hiccup-atom (hic/rendered-tree->hiccup result))))]
    ;; Register the render callback
    (hooks/set-render-callback! ctx render-callback)
    ;; Initial render
    (render-callback)
    mounted))

(defn current-hiccup
  "Get the current hiccup from a mounted component.
   This reflects the latest render after any state changes."
  [^MountedComponent mounted]
  @(:hiccup-atom mounted))

(defn update-props!
  "Update the props of a mounted component and re-render.
   The props-fn receives the current props and should return the new props."
  [^MountedComponent mounted props-fn]
  (swap! (:props-atom mounted) props-fn)
  ;; Trigger re-render
  (when-let [callback (hooks/get-render-callback (:ctx mounted))]
    (callback)))

(defn set-props!
  "Set new props on a mounted component and re-render."
  [^MountedComponent mounted new-props]
  (update-props! mounted (constantly new-props)))

(defn unmount-component!
  "Unmount a component, running any cleanup effects.
   Clears the render callback and hook state."
  [^MountedComponent mounted]
  (let [ctx (:ctx mounted)]
    ;; Clear the render callback
    (hooks/set-render-callback! ctx nil)
    ;; Clear all hooks (which would run effect cleanups in a full implementation)
    (hooks/clear-context-hooks! ctx)
    (reset! (:hiccup-atom mounted) nil)))

;; =============================================================================
;; Event Capture (Transactions and Network)
;; =============================================================================

(def ^:private optimistic-action-type 'com.fulcrologic.fulcro.inspect.devtool-api/optimistic-action)
(def ^:private send-started-type 'com.fulcrologic.fulcro.inspect.devtool-api/send-started)
(def ^:private send-finished-type 'com.fulcrologic.fulcro.inspect.devtool-api/send-finished)
(def ^:private send-failed-type 'com.fulcrologic.fulcro.inspect.devtool-api/send-failed)

(defn- install-event-capture!
  "Install the event-capturing tool on the app. Called automatically by build-test-app."
  [app]
  (tools/register-tool! app
    (fn [app event]
      (let [event-type (:type event)
            runtime    (:com.fulcrologic.fulcro.application/runtime-atom app)]
        (cond
          (= event-type optimistic-action-type)
          (swap! runtime update ::captured-transactions
            (fnil conj [])
            (select-keys event [:fulcro.history/tx
                                :fulcro.history/db-before-id
                                :fulcro.history/db-after-id
                                :fulcro.history/network-sends
                                :component
                                :ident-ref]))

          (#{send-started-type send-finished-type send-failed-type} event-type)
          (swap! runtime update ::captured-network-events
            (fnil conj [])
            (assoc (select-keys event [:fulcro.inspect.ui.network/remote
                                       :fulcro.inspect.ui.network/request-id
                                       :fulcro.inspect.ui.network/request-edn
                                       :fulcro.inspect.ui.network/response-edn
                                       :fulcro.inspect.ui.network/error])
              :event-type (cond
                            (= event-type send-started-type) :started
                            (= event-type send-finished-type) :finished
                            (= event-type send-failed-type) :failed))))))))

(defn captured-transactions
  "Get the list of captured transactions (optimistic actions).

   Each entry is a map containing:
   - :fulcro.history/tx - The transaction form (mutation-sym params)
   - :fulcro.history/network-sends - Set of remotes that will be contacted
   - :component - Name of the component that initiated the transaction (if any)
   - :ident-ref - The ident of the component (if any)

   Example:
   ```clojure
   (comp/transact! app [(my-mutation {:x 1})])
   (let [txns (captured-transactions app)]
     (is (= 1 (count txns)))
     (is (= 'my-ns/my-mutation (-> txns first :fulcro.history/tx first))))
   ```"
  [app]
  (-> app :com.fulcrologic.fulcro.application/runtime-atom deref ::captured-transactions (or [])))

(defn captured-network-events
  "Get the list of captured network events.

   Each entry is a map containing:
   - :event-type - One of :started, :finished, or :failed
   - :fulcro.inspect.ui.network/remote - The remote name
   - :fulcro.inspect.ui.network/request-id - Unique request identifier
   - :fulcro.inspect.ui.network/request-edn - The EQL sent (on :started)
   - :fulcro.inspect.ui.network/response-edn - The response body (on :finished)
   - :fulcro.inspect.ui.network/error - Error info (on :failed)

   Example:
   ```clojure
   (df/load! app :current-user User)
   (let [events (captured-network-events app)]
     (is (some #(= :finished (:event-type %)) events)))
   ```"
  [app]
  (-> app :com.fulcrologic.fulcro.application/runtime-atom deref ::captured-network-events (or [])))

(defn clear-captured-events!
  "Clear all captured transactions and network events.

   Useful for isolating assertions between test phases.

   Example:
   ```clojure
   ;; Phase 1
   (click! app \"load-btn\")
   (is (= 1 (count (captured-transactions app))))

   ;; Clear for phase 2
   (clear-captured-events! app)

   ;; Phase 2
   (click! app \"save-btn\")
   (is (= 1 (count (captured-transactions app))))
   ```"
  [app]
  (swap! (:com.fulcrologic.fulcro.application/runtime-atom app)
    dissoc ::captured-transactions ::captured-network-events)
  nil)

(defn last-transaction
  "Get the most recently captured transaction, or nil if none.

   Convenience for the common case of checking what just happened.

   Example:
   ```clojure
   (click! app \"submit-btn\")
   (is (= 'my-ns/submit (-> (last-transaction app) :fulcro.history/tx first)))
   ```"
  [app]
  (last (captured-transactions app)))

(defn transaction-mutations
  "Extract just the mutation symbols from captured transactions.

   Example:
   ```clojure
   (click! app \"btn1\")
   (click! app \"btn2\")
   (is (= ['ns/action1 'ns/action2] (transaction-mutations app)))
   ```"
  [app]
  (mapv #(-> % :fulcro.history/tx first) (captured-transactions app)))

;; =============================================================================
;; App Building
;; =============================================================================

(defn build-test-app
  "Create a test application configured for synchronous testing.

   Options:
   - :root-class - Root component class (required for render frame capture)
   - :remotes - Map of remote-name to remote implementation
   - :initial-state - Initial state map (merged with root's initial state)
   - :render-history-size - Number of frames to keep (default 10)
   - :shared - Static shared props
   - :shared-fn - Function to compute shared props from root tree

   Returns a Fulcro app configured with:
   - Synchronous transaction processing
   - Frame-capturing render
   - Event capture (transactions and network events)
   - The specified remotes

   Event capture is installed automatically. Use `captured-transactions`,
   `captured-network-events`, and `clear-captured-events!` to inspect
   what mutations were triggered and what network activity occurred.

   IMPORTANT: For event capture to work, you must enable inspect notifications
   by setting the JVM property: -Dcom.fulcrologic.fulcro.inspect=true"
  [{:keys [root-class remotes initial-state render-history-size shared shared-fn]
    :or   {render-history-size *render-frame-max*
           remotes             {}}}]
  (let [base-app (rapp/fulcro-app
                   {:initial-db        (or initial-state {})
                    :root-class        root-class
                    :remotes           (if (empty? remotes)
                                         {:remote {:transmit! (fn [{:com.fulcrologic.fulcro.algorithms.tx-processing/keys [result-handler]}]
                                                                (log/warn "No remote configured, returning empty response")
                                                                (result-handler {:status-code 200 :body {}}))}}
                                         remotes)
                    :shared            shared
                    :shared-fn         shared-fn
                    :optimized-render! frame-capturing-render
                    :core-render!      (fn [app options]
                                         (let [optimized-render! (ah/app-algorithm app :optimized-render!)]
                                           (binding [comp/*app*    app
                                                     comp/*parent* nil
                                                     comp/*shared* (comp/shared app)]
                                             (optimized-render! app options))))})
        app      (stx/with-synchronous-transactions base-app)]
    (when root-class
      (rapp/initialize-state! app root-class))
    (swap! (:com.fulcrologic.fulcro.application/runtime-atom app)
      assoc ::render-frame-max render-history-size
      ::render-frames []
      ;; Initialize hook registry for stateful hooks
      ::hooks/hook-registry {})
    ;; Install event capture for testing
    (install-event-capture! app)
    ;; Register render callback so hook setters auto-trigger renders
    (hooks/set-render-callback! app (fn [] (render-frame! app)))
    ;; Capture initial render frame
    (when root-class
      (frame-capturing-render app {}))
    app))

(defn set-remote!
  "Set or replace a remote on the test app.
   Convenience wrapper around rapp/set-remote!"
  [app remote-name remote]
  (rapp/set-remote! app remote-name remote))

;; =============================================================================
;; Transaction Processing
;; =============================================================================

(defn wait-for-idle!
  "Block until all pending work is complete.
   With synchronous tx processing, this is usually instant,
   but useful after operations that might queue follow-on work."
  [app]
  (loop [iterations 0]
    (when (< iterations 100)                                ; Safety limit
      (let [submission-queue (stx/submission-queue app)
            active-queue     (stx/active-queue app)]
        (when (or (seq submission-queue) (seq active-queue))
          (Thread/sleep 1)
          (recur (inc iterations)))))))

(defn pending-sends
  "Get the pending send queue for a remote.
   Useful for verifying what would be sent to the server."
  [app remote-name]
  (stx/send-queue app remote-name))

(defn has-pending-work?
  "Returns true if there is any pending transaction work."
  [app]
  (or (stx/available-work? app)
    (seq (stx/active-queue app))
    (stx/post-processing? app)))

;; =============================================================================
;; Test Utilities
;; =============================================================================

(defn reset-app!
  "Reset the app state to initial state from root component.
   Useful for test isolation."
  [app]
  (let [runtime-atom (:com.fulcrologic.fulcro.application/runtime-atom app)
        {:com.fulcrologic.fulcro.application/keys [root-class]} @runtime-atom]
    (when root-class
      (reset! (:com.fulcrologic.fulcro.application/state-atom app) {})
      (rapp/initialize-state! app root-class)
      (clear-render-history! app))))

(defn with-render-tracking
  "Execute body and return a map with:
   - :result - The return value of body
   - :frames - All frames captured during execution
   - :frame-count - Number of frames captured

   Example:
   ```clojure
   (let [{:keys [result frames]} (with-render-tracking app
                                   (comp/transact! app [(my-mutation)]))]
     (is (= 1 (count frames))))
   ```"
  [app body-fn]
  (let [start-count     (count (render-history app))
        result          (body-fn)
        end-count       (count (render-history app))
        new-frame-count (- end-count start-count)]
    {:result      result
     :frames      (take new-frame-count (render-history app))
     :frame-count new-frame-count}))

;; =============================================================================
;; UI Interaction (always operates on the most recent frame)
;; =============================================================================

(defn click!
  "Click on an element by ID in the most recent render frame.
   Invokes the element's :onClick handler if present.

   Example:
   ```clojure
   (click! app \"submit-btn\")
   ```"
  [app element-id]
  (some-> (hiccup-frame app) (hic/find-by-id element-id) (hic/click!))
  nil)

(defn type-into!
  "Type a value into an input element by ID.
   Invokes the element's :onChange handler with {:target {:value new-value}}.

   Example:
   ```clojure
   (type-into! app \"email-input\" \"user@example.com\")
   ```"
  [app element-id new-value]
  (some-> (hiccup-frame app) (hic/find-by-id element-id) (hic/type-text! new-value))
  nil)

(defn change!
  "Invoke onChange on an element with a custom event.
   For simple text input, prefer `type-into!`.

   Example:
   ```clojure
   ;; For a checkbox
   (change! app \"remember-me\" {:target {:checked true}})

   ;; For a select
   (change! app \"country-select\" {:target {:value \"US\"}})
   ```"
  [app element-id event]
  (some-> (hiccup-frame app) (hic/find-by-id element-id) (hic/invoke-handler! :onChange event))
  nil)

(defn blur!
  "Trigger blur on an element by ID.
   Invokes the element's :onBlur handler.

   Example:
   ```clojure
   (blur! app \"username-input\")
   ```"
  [app element-id]
  (some-> (hiccup-frame app) (hic/find-by-id element-id) (hic/invoke-handler! :onBlur {}))
  nil)

(defn invoke-handler!
  "Invoke an arbitrary handler on an element by ID.
   Use this for handlers not covered by the convenience functions.

   Example:
   ```clojure
   (invoke-handler! app \"my-element\" :onMouseEnter {})
   (invoke-handler! app \"draggable\" :onDragStart {:dataTransfer ...})
   ```"
  [app element-id handler-key event]
  (some-> (hiccup-frame app) (hic/find-by-id element-id) (hic/invoke-handler! handler-key event))
  nil)

;; =============================================================================
;; UI Inspection (with optional frame offset)
;; =============================================================================

(defn element
  "Find an element by ID in the rendered hiccup.

   With 2 args: Searches the most recent frame.
   With 3 args: Searches the nth frame (0 = most recent).

   Returns the hiccup element or nil if not found.

   Example:
   ```clojure
   (element app \"submit-btn\")
   ;; => [:button {:id \"submit-btn\" :onClick #fn} \"Submit\"]

   ;; Check element from 2 frames ago
   (element app \"submit-btn\" 2)
   ```"
  ([app element-id]
   (element app element-id 0))
  ([app element-id frame-index]
   (some-> (hiccup-frame app frame-index) (hic/find-by-id element-id))))

(defn elements-by-class
  "Find all elements with a CSS class in the rendered hiccup.

   With 2 args: Searches the most recent frame.
   With 3 args: Searches the nth frame (0 = most recent).

   Returns a vector of matching elements.

   Example:
   ```clojure
   (elements-by-class app \"error-message\")
   ;; => [[:div {:className \"error-message\"} \"Invalid email\"]]
   ```"
  ([app class-name]
   (elements-by-class app class-name 0))
  ([app class-name frame-index]
   (some-> (hiccup-frame app frame-index) (hic/find-by-class class-name))))

(defn elements-by-tag
  "Find all elements with a specific tag in the rendered hiccup.

   With 2 args: Searches the most recent frame.
   With 3 args: Searches the nth frame (0 = most recent).

   Returns a vector of matching elements.

   Example:
   ```clojure
   (elements-by-tag app :button)
   ;; => [[:button {...} \"Save\"] [:button {...} \"Cancel\"]]
   ```"
  ([app tag]
   (elements-by-tag app tag 0))
  ([app tag frame-index]
   (some-> (hiccup-frame app frame-index) (hic/find-by-tag tag))))

(defn text-of
  "Get the text content of an element by ID.

   With 2 args: From the most recent frame.
   With 3 args: From the nth frame (0 = most recent).

   Returns the concatenated text content, or nil if element not found.

   Example:
   ```clojure
   (text-of app \"welcome-message\")
   ;; => \"Hello, John!\"

   ;; Compare text before and after
   (let [before (text-of app \"counter\" 1)
         after  (text-of app \"counter\" 0)]
     (is (not= before after)))
   ```"
  ([app element-id]
   (text-of app element-id 0))
  ([app element-id frame-index]
   (some-> (hiccup-frame app frame-index) (hic/text-of element-id))))

(defn attr-of
  "Get an attribute value from an element by ID.

   With 3 args: From the most recent frame.
   With 4 args: From the nth frame (0 = most recent).

   Returns the attribute value, or nil if element/attr not found.

   Example:
   ```clojure
   (attr-of app \"email-input\" :value)
   ;; => \"user@example.com\"

   (attr-of app \"submit-btn\" :disabled)
   ;; => true
   ```"
  ([app element-id attr-key]
   (attr-of app element-id attr-key 0))
  ([app element-id attr-key frame-index]
   (some-> (hiccup-frame app frame-index) (hic/attr-of element-id attr-key))))

(defn classes-of
  "Get the CSS classes of an element by ID as a set.

   With 2 args: From the most recent frame.
   With 3 args: From the nth frame (0 = most recent).

   Returns a set of class names, or nil if element not found.

   Example:
   ```clojure
   (classes-of app \"submit-btn\")
   ;; => #{\"btn\" \"btn-primary\" \"loading\"}
   ```"
  ([app element-id]
   (classes-of app element-id 0))
  ([app element-id frame-index]
   (some-> (hiccup-frame app frame-index) (hic/classes-of element-id))))

(defn exists?
  "Check if an element with the given ID exists in the rendered hiccup.

   With 2 args: Checks the most recent frame.
   With 3 args: Checks the nth frame (0 = most recent).

   Example:
   ```clojure
   (is (exists? app \"welcome-message\"))
   (is (not (exists? app \"error-message\")))
   ```"
  ([app element-id]
   (exists? app element-id 0))
  ([app element-id frame-index]
   (some? (element app element-id frame-index))))

(defn has-class?
  "Check if an element has a specific CSS class.

   With 3 args: Checks the most recent frame.
   With 4 args: Checks the nth frame (0 = most recent).

   Example:
   ```clojure
   (is (has-class? app \"submit-btn\" \"loading\"))
   (is (not (has-class? app \"submit-btn\" \"disabled\")))
   ```"
  ([app element-id class-name]
   (has-class? app element-id class-name 0))
  ([app element-id class-name frame-index]
   (contains? (classes-of app element-id frame-index) class-name)))

(defn visible?
  "Check if an element exists and is not hidden via inline style.
   Note: This only checks inline `display: none` or `visibility: hidden`.
   CSS classes that hide elements are not detected.

   With 2 args: Checks the most recent frame.
   With 3 args: Checks the nth frame (0 = most recent).

   Example:
   ```clojure
   (is (visible? app \"modal\"))
   ```"
  ([app element-id]
   (visible? app element-id 0))
  ([app element-id frame-index]
   (when-let [elem (element app element-id frame-index)]
     (let [style (hic/element-attr elem :style)]
       (and (some? elem)
         (not= "none" (:display style))
         (not= "hidden" (:visibility style)))))))

(defn input-value
  "Get the current value of an input element by ID.
   Shorthand for (attr-of app id :value).

   With 2 args: From the most recent frame.
   With 3 args: From the nth frame (0 = most recent).

   Example:
   ```clojure
   (is (= \"user@example.com\" (input-value app \"email-input\")))
   ```"
  ([app element-id]
   (input-value app element-id 0))
  ([app element-id frame-index]
   (attr-of app element-id :value frame-index)))

(defn checked?
  "Check if a checkbox or radio input is checked.

   With 2 args: Checks the most recent frame.
   With 3 args: Checks the nth frame (0 = most recent).

   Example:
   ```clojure
   (is (checked? app \"remember-me\"))
   ```"
  ([app element-id]
   (checked? app element-id 0))
  ([app element-id frame-index]
   (boolean (attr-of app element-id :checked frame-index))))

(defn disabled?
  "Check if an element is disabled.

   With 2 args: Checks the most recent frame.
   With 3 args: Checks the nth frame (0 = most recent).

   Example:
   ```clojure
   (is (disabled? app \"submit-btn\"))
   ```"
  ([app element-id]
   (disabled? app element-id 0))
  ([app element-id frame-index]
   (boolean (attr-of app element-id :disabled frame-index))))

(defn count-elements
  "Count elements matching a predicate in the rendered hiccup.

   With 2 args: Counts in the most recent frame.
   With 3 args: Counts in the nth frame (0 = most recent).

   Example:
   ```clojure
   ;; Count all list items
   (is (= 5 (count-elements app #(= :li (hic/element-tag %)))))

   ;; Count error messages
   (is (= 0 (count-elements app #(hic/has-class? % \"error\"))))
   ```"
  ([app pred]
   (count-elements app pred 0))
  ([app pred frame-index]
   (count (some-> (hiccup-frame app frame-index) (hic/find-all pred)))))

;; =============================================================================
;; Text-Based Interaction
;; =============================================================================

(defn click-on-text!
  "Click on an element by its text content in the most recent render frame.
   Uses event bubbling semantics: if the matched element doesn't have an onClick
   handler, walks up the ancestor chain to find one.

   Pattern can be:
   - A string (substring match)
   - A regex (pattern match)
   - A vector of patterns (all must match)

   The optional `n` parameter specifies which match to click (0-indexed, default 0).

   Also checks :text and :label attributes on React component elements.

   Example:
   ```clojure
   (click-on-text! app \"View All\")
   (click-on-text! app \"New\" 1)        ; click second 'New' link
   (click-on-text! app #\"Logout\")
   (click-on-text! app [\"Account\" \"View\"])  ; element containing both
   ```"
  ([app pattern]
   (click-on-text! app pattern 0))
  ([app pattern n]
   (some-> (hiccup-frame app) (hic/click-bubbling! pattern n))
   nil))

(defn type-into-labeled!
  "Type a value into an input field identified by its label.
   Finds an input element associated with a label matching the pattern,
   then invokes the input's :onChange handler.

   Pattern can be:
   - A string (substring match on label text)
   - A regex (pattern match)
   - A vector of patterns (all must match)

   The optional `n` parameter specifies which matching labeled field to use
   (0-indexed, default 0).

   Example:
   ```clojure
   (type-into-labeled! app \"Username\" \"john.doe\")
   (type-into-labeled! app \"Password\" \"secret123\")
   (type-into-labeled! app #\"(?i)email\" \"user@example.com\")
   (type-into-labeled! app \"Amount\" \"100.00\" 1)  ; second Amount field
   ```"
  ([app label-pattern value]
   (type-into-labeled! app label-pattern value 0))
  ([app label-pattern value n]
   (some-> (hiccup-frame app) (hic/type-text-into-labeled! label-pattern value n))
   nil))

(defn find-by-text
  "Find all elements whose text content matches the pattern.

   With 2 args: Searches the most recent frame.
   With 3 args: Searches the nth frame (0 = most recent).

   Pattern can be:
   - A string (substring match)
   - A regex (pattern match)
   - A vector of patterns (all must match)

   Returns a vector of matching hiccup elements.

   Example:
   ```clojure
   (find-by-text app \"Submit\")
   (find-by-text app #\"Account.*\")
   ```"
  ([app pattern]
   (find-by-text app pattern 0))
  ([app pattern frame-index]
   (some-> (hiccup-frame app frame-index) (hic/find-by-text pattern))))

(defn text-exists?
  "Check if any element with the given text content exists in the rendered hiccup.

   With 2 args: Checks the most recent frame.
   With 3 args: Checks the nth frame (0 = most recent).

   Example:
   ```clojure
   (is (text-exists? app \"Welcome, John!\"))
   (is (not (text-exists? app \"Error\")))
   ```"
  ([app pattern]
   (text-exists? app pattern 0))
  ([app pattern frame-index]
   (boolean (seq (find-by-text app pattern frame-index)))))

