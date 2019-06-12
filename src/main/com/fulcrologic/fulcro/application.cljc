(ns com.fulcrologic.fulcro.application
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.application-helpers :as ah]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.indexing :as indexing]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.misc :as util]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]
    [com.fulcrologic.fulcro.algorithms.scheduling :as sched]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.mutations :as mut]
    [com.fulcrologic.fulcro.rendering.ident-optimized-render :as ident-optimized]
    [edn-query-language.core :as eql]
    [ghostwheel.core :refer [>defn => |]]
    #?@(:cljs [[goog.object :as gobj]
               [goog.dom :as gdom]])
    [taoensso.timbre :as log])
  #?(:clj (:import (clojure.lang IDeref))))

(defn basis-t
  "Return the current basis time of the app."
  [app]
  (-> app ::runtime-atom deref ::basis-t))

(defn current-state
  "Get the value of the application state database at the current time."
  [app-or-component]
  (let [app (comp/any->app app-or-component)]
    (-> app ::state-atom deref)))

(defn tick!
  "Move the basis-t forward one tick. For internal use and internal algorithms."
  [app]
  (swap! (::runtime-atom app) update ::basis-t inc))

(defn update-shared!
  "Force shared props to be recalculated. This updates the shared props on the app, and future renders will see the
   updated values. This is a no-op if no shared-fn is defined on the app. If you're using React 16+ consider using
   Context instead of shared."
  [{::keys [runtime-atom] :as app}]
  (try
    (when-let [shared-fn (ah/app-algorithm app :shared-fn)]
      (let [shared     (-> app ::runtime-atom deref ::static-shared-props)
            state      (current-state app)
            root-class (-> app ::runtime-atom deref ::root-class)
            query      (comp/get-query root-class state)
            v          (fdn/db->tree query state state)]
        (swap! runtime-atom assoc ::shared-props (merge shared (shared-fn v)))))
    (catch #?(:cljs :default :clj Throwable) e
      (log/error e "Cannot compute shared"))))

(defn props-only-query [query]
  (let [{:keys [children]} (eql/query->shallow-ast query)]
    (into []
      (comp
        (map :key)
        (filter keyword?))
      children)))

(defn root-props-changed?
  "Returns true if the props queries directly by the root component of the app (if mounted) have changed since the last
  render.  This is a shallow analysis such that, for example, a join from root (in a normalized db) will be checked as a difference
  of idents that the root prop points to.  This can be used for determining if things like shared-fn need to be re-run,
  and if it would simply be quicker to keyframe render the entire tree.

  This is a naivé algorithm that is essentially `select-keys` on the root props. It does not interpret the query in
  any way."
  [app]
  (let [{::keys [runtime-atom state-atom]} app
        {::keys [root-class]} @runtime-atom]
    (if-not (comp/get-query root-class)
      true
      (let [
            state-map       @state-atom
            prior-state-map (-> runtime-atom deref ::last-rendered-state)
            props-query     (props-only-query (comp/get-query root-class state-map))
            root-old        (select-keys prior-state-map props-query)
            root-new        (select-keys state-map props-query)]
        (not= root-old root-new)))))

(defn render!
  "Render the application immediately.  Prefer `schedule-render!`, which will ensure no more than 60fps.

  Options include:
  `force-root?` - boolean.  When true disables all optimizations and forces a full root re-render.

  and anything your selected rendering optization system allows.  Shared props are updated via `shared-fn`
  only on `force-root?` and when (shallow) root props change.
  "
  ([app]
   (render! app {:force-root? false}))
  ([app {:keys [force-root?] :as options}]
   (tick! app)
   (let [{:keys [::runtime-atom ::state-atom]} app
         render!             (ah/app-algorithm app :optimized-render!)
         shared-props        (get @runtime-atom ::shared-props)
         root-props-changed? (root-props-changed? app)]
     (binding [fdn/*denormalize-time* (basis-t app)
               comp/*app*             app
               comp/*shared*          shared-props
               comp/*query-state*     @state-atom]
       (when (or force-root? root-props-changed?)
         (update-shared! app))
       (render! app (merge options {:root-props-changed? root-props-changed?})))
     (swap! runtime-atom assoc ::last-rendered-state @state-atom))))

(defn schedule-render!
  "Schedule a render on the next animation frame."
  ([app]
   (schedule-render! app false))
  ([app options]
   #?(:clj  (render! app options)
      :cljs (let [r #(render! app options)]
              (if (not (exists? js/requestAnimationFrame))
                (sched/defer r 16)
                (js/requestAnimationFrame r))))))

(defn default-tx!
  "Default (Fulcro-2 compatible) transaction submission."
  ([app tx]
   [::app ::txn/tx => ::txn/id]
   (default-tx! app tx {:optimistic? true}))
  ([{:keys [::runtime-atom] :as app} tx options]
   [:com.fulcrologic.fulcro.application/app ::txn/tx ::txn/options => ::txn/id]
   (txn/schedule-activation! app)
   (let [options (merge {:optimistic? true} options)
         node    (txn/tx-node tx options)
         ref     (get options :ref)]
     (swap! runtime-atom (fn [s] (cond-> (update s ::txn/submission-queue (fnil conj []) node)
                                   ref (update ::components-to-refresh (fnil conj []) ref))))
     (::txn/id node))))

(defn- default-load-error? [{:keys [status-code body] :as result}] (not= 200 status-code))

(defn- default-global-eql-transform
  "The default query transform function.  It makes sure the following items on a component query
  are never sent to the server:

  - Props whose namespace is `ui`
  - The form-state configuration join

  Takes an AST and returns the modified AST.
  "
  [ast]
  (log/info "Rewriting network AST" ast)
  (log/spy :info
    (let [kw-namespace (fn [k] (and (keyword? k) (namespace k)))]
      (util/elide-ast-nodes ast (fn [k]
                                  (when-let [ns (some-> k kw-namespace)]
                                    (or
                                      (= k ::fs/config)
                                      (and
                                        (string? ns)
                                        (= "ui" ns)))))))))

(defonce fulcro-tools (atom {}))

(defn register-tool!
  "Register a debug tool. When an app starts, the debug tool can have several hooks that are notified:

  ::tool-id some identifier to place the tool into the tool map
  ::tx-listen is a (fn [tx info] ...) that will be called on every `transact!` of the app. Return value is ignored.
  ::network-wrapper is (fn [network-map] network-map') that will be given the networking config BEFORE it is initialized. You can wrap
  them, but you MUST return a compatible map out or you'll disable networking.
  ::component-lifecycle is (fn [component evt]) that is called with evt of :mounted and :unmounted to tell you when the given component mounts/unmounts.
  ::instrument-wrapper is a (fn [instrument] instrument') that allows you to wrap your own instrumentation (for rendering) around any existing (which may be nil)
  ::app-started (fn [app] ...) that will be called once the app is mounted, just like started-callback/client-did-mount. Return value is ignored."
  [{:keys [::tool-id] :as tool-registry}]
  (log/info "Installing tool" tool-id)
  (swap! fulcro-tools assoc tool-id tool-registry))

(defn- add-tools [{::keys [runtime-atom]
                   :keys  [client-did-mount] :as app}]
  (if (seq (vals @fulcro-tools))
    (let [remotes (some-> runtime-atom deref ::remotes)
          tx!     (-> app ::algorithms :algorithm/tx!)
          started (or client-did-mount (constantly nil))
          new-tx! (fn wrapped-tx*
                    ([app tx] (wrapped-tx* app tx {}))
                    ([app tx options]
                     (log/info "Running normal tx" tx)
                     (let [txid (tx! app tx options)]
                       (try
                         (log/info "Sending tool mesg about tx" tx)
                         (doseq [tool (vals @fulcro-tools)]
                           (when-let [tx-listen (get tool ::tx-listen)]
                             (tx-listen app tx (assoc options ::txn/id txid))))
                         (catch #?(:cljs :default :clj Exception) e)))))
          [started remotes] (reduce
                              (fn [[start net] {:keys [::network-wrapper ::app-started]}]
                                (let [start (if app-started (fn [app] (app-started app) (start app)) start)
                                      net   (if network-wrapper (network-wrapper app net) net)]
                                  [start net]))
                              [started remotes]
                              (vals @fulcro-tools))]
      (swap! runtime-atom assoc ::remotes remotes)
      (-> app
        (assoc-in [::algorithms :algorithm/tx!] new-tx!)
        (assoc :client-did-mount started)))
    app))

(defn fulcro-app
  "Create a new Fulcro application.

  `options` - A map of initial options
      - `initial-db` a *map* containing a *normalized* Fulcro app db.  Normally Fulcro will populate app state with
        your component tree's initial state.  Use `mount!` options to toggle the initial state pull from root.
      - `:optimized-render!` - A function that can analyze the state of the application and optimally refresh the screen.
        Defaults to `ident-optimized-render/render!`, but can also be set to `keyframe-render/render!`.  Further customizations are
        also possible.  Most applications will likely be best with the default (which analyzes changes by ident and targets
        refreshes), but applications with a lot of on-screen components may find the keyframe renderer to be faster. Both
        get added benefits from Fulcro's default `shouldComponentUpdate`, which will prevent rendering when there are no real
        changes.
      - `default-result-action` - A `(fn [env])` that will be used in your mutations defined with `defmutation` as the
        default `:result-action` when none is supplied. Normally defaults to a function that supports mutation joins, targeting,
        and ok/error actions. WARNING: Overriding this is for advanced users and can break important functionality. The
        default is value for this option is `com.fulcrologic.fulcro.mutations/default-result-action`.
      - `:result-pre-action` - A `(fn [env])` that will be run (if present) before any user-supplied `ok-action` body when
        using `defmutation` with the default result action. (may not apply if you override default-result-action)
      - `:result-post-action` - A `(fn [env])` that will be run (if present) after the user-supplied `ok-action` body when
        using `defmutation` with the default result action. (may not apply if you override default-result-action)
      - `:global-error-action` a `(fn [env])` that is called on status codes other than 200 on *mutations* if the default
        result-action is in use. (may not apply if you override default-result-action)
      - `:global-eql-transform` - A `(fn [AST] new-AST)` that will be asked to rewrite the AST of all transactions just
        before they are placed on the network layer.
      - `:client-did-mount` - A `(fn [app])` that is called when the application mounts the first time.
      - `:remotes` - A map from remote name to a remote handler, which is defined as a map that contains at least
        a `:transmit!` key whose value is a `(fn [send-node])`. See `networking.http-remote`.
      - `:shared` - A (static) map of data that should be visible in all components through `comp/shared`.
      - `:shared-fn` - A function on root props that can select/augment shared whenever a forced root render
        or explicit call to `app/update-shared!` happens.
      - `:props-middleware` - A function that can add data to the 4th (optional) argument of
        `defsc`.  Useful for allowing users to quickly destructure extra data created by
        component extensions. See the fulcro-garden-css project on github for an example usage.
      - `:render-middleware` - A `(fn [this real-render])`. If supplied it will be called for every Fulcro component
        render, and *must* call (and return the result of) `real-render`.  This can be used to wrap the real render
        function in order to do things like measure performance, set dynamic vars, or augment the UI in arbitrary ways.
        `this` is the component being rendered.
    "
  ([] (fulcro-app {}))
  ([{:keys [props-middleware
            global-eql-transform
            default-result-action
            result-pre-action
            result-post-action
            global-error-action
            optimized-render!
            render-middleware
            initial-db
            client-did-mount
            remotes
            shared
            shared-fn] :as options}]
   {::id              (util/uuid)
    ::state-atom      (atom (or initial-db {}))
    :client-did-mount client-did-mount
    ::algorithms      {:algorithm/tx!                   default-tx!
                       :algorithm/optimized-render!     (or optimized-render! ident-optimized/render!)
                       :algorithm/shared-fn             (or shared-fn (constantly {}))
                       :algorithm/render!               render!
                       :algorithm/load-error?           default-load-error?
                       :algorithm/merge*                merge/merge*

                       :algorithm/default-result-action (or default-result-action mut/default-result-action)
                       :algorithm/result-pre-action     result-pre-action
                       :algorithm/result-post-action    result-post-action
                       :algorithm/global-error-action   global-error-action
                       :algorithm/global-eql-transform  (or global-eql-transform default-global-eql-transform)
                       :algorithm/index-root!           indexing/index-root!
                       :algorithm/index-component!      indexing/index-component!
                       :algorithm/drop-component!       indexing/drop-component!
                       :algorithm/props-middleware      props-middleware
                       :algorithm/render-middleware     render-middleware
                       :algorithm/schedule-render!      schedule-render!}
    ::runtime-atom    (atom
                        {::app-root                        nil
                         ::mount-node                      nil
                         ::root-class                      nil
                         ::root-factory                    nil
                         ::basis-t                         1
                         ::last-rendered-state             {}

                         ::static-shared-props             shared
                         ::shared-props                    {}

                         ::remotes                         (or remotes
                                                             {:remote {:transmit! (fn [send]
                                                                                    (log/fatal "Remote requested, but no remote defined."))}})
                         ::indexes                         {:ident->components {}}
                         ::mutate                          mut/mutate
                         ::txn/activation-scheduled?       false
                         ::txn/queue-processing-scheduled? false
                         ::txn/sends-scheduled?            false
                         ::txn/submission-queue            []
                         ::txn/active-queue                []
                         ::txn/send-queues                 {}})}))

(defn fulcro-app? [x] (and (map? x) (contains? x ::state-atom) (contains? x ::runtime-atom)))

(defn mounted? [{:keys [::runtime-atom]}]
  (-> runtime-atom deref ::app-root boolean))

(defn mount!
  "Mount the app.  If called on an already-mounted app this will have the effect of re-installing the root node so that
  hot code reload will refresh the UI (useful for development).

  - `app`  The Fulcro app
  - `root`  The Root UI component
  - `node` The (string) ID or DOM node on which to mount.
  - `options` An optional map with additional mount options.


  `options` can include:

  - `:initialize-state?` (default true) - If NOT mounted already: Pulls the initial state tree from root component,
  normalizes it, and installs it as the application's state.  If there was data supplied as an initial-db, then this
  new initial state will be *merged* with that initial-db.
  "
  ([app root node]
   (mount! app root node {:initialize-state? true}))
  ([app root node {:keys [initialize-state?]}]
   #?(:cljs
      (let [initialize-state? (if (boolean? initialize-state?) initialize-state? true)
            reset-mountpoint! (fn []
                                (let [dom-node     (if (string? node) (gdom/getElement node) node)
                                      root-factory (comp/factory root)]
                                  (swap! (::runtime-atom app) assoc
                                    ::mount-node dom-node
                                    ::root-factory root-factory
                                    ::root-class root)
                                  (update-shared! app)
                                  (indexing/index-root! app)
                                  (schedule-render! app {:force-root? true})))]
        (if (mounted? app)
          (reset-mountpoint!)
          (let [app (add-tools app)]
            (when initialize-state?
              (let [initial-db   (-> app ::state-atom deref)
                    root-query   (comp/get-query root)
                    initial-tree (comp/get-initial-state root)
                    db-from-ui   (if root-query
                                   (-> (fnorm/tree->db root-query initial-tree true)
                                     (merge/merge-alternate-union-elements root))
                                   initial-tree)
                    db           (util/deep-merge initial-db db-from-ui)]
                (reset! (::state-atom app) db)))
            (reset-mountpoint!)
            (when-let [cdm (:client-did-mount app)]
              (cdm app))))))))

(defn app-root [app] (-> app ::runtime-atom deref ::app-root))

(defn force-root-render!
  "Force a re-render of the root. Runs a root query, disables shouldComponentUpdate, and renders the root component.
   This effectively forces React to do a full VDOM diff. Useful for things like UI refresh on hot code reload and
   changing locales where there are no real data changes, but the UI still needs to refresh.

   Argument can be anything that any->reconciler accepts."
  [app-ish]
  (when-let [app (comp/any->app app-ish)]
    (binding [comp/*blindly-render* true]
      (render! app {:force-root? true}))))
