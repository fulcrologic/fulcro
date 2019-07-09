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
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [edn-query-language.core :as eql]
    com.fulcrologic.fulcro.specs
    [ghostwheel.core :refer [>defn => |]]
    [clojure.spec.alpha :as s]
    #?@(:cljs [[goog.object :as gobj]
               [goog.dom :as gdom]])
    [taoensso.timbre :as log])
  #?(:clj (:import (clojure.lang IDeref))))

(>defn basis-t
  "Return the current basis time of the app."
  [app]
  [::app => pos-int?]
  (-> app ::runtime-atom deref ::basis-t))

(>defn current-state
  "Get the value of the application state database at the current time."
  [app-or-component]
  [any? => map?]
  (let [app (comp/any->app app-or-component)]
    (-> app ::state-atom deref)))

(>defn tick!
  "Move the basis-t forward one tick. For internal use and internal algorithms."
  [app]
  [::app => any?]
  (swap! (::runtime-atom app) update ::basis-t inc))

(>defn update-shared!
  "Force shared props to be recalculated. This updates the shared props on the app, and future renders will see the
   updated values. This is a no-op if no shared-fn is defined on the app. If you're using React 16+ consider using
   Context instead of shared."
  [{::keys [runtime-atom] :as app}]
  [::app => any?]
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

(>defn props-only-query
  [query]
  [vector? => vector?]
  (let [{:keys [children]} (eql/query->shallow-ast query)]
    (into []
      (comp
        (map :key)
        (filter keyword?))
      children)))

(>defn root-props-changed?
  "Returns true if the props queries directly by the root component of the app (if mounted) have changed since the last
  render.  This is a shallow analysis such that, for example, a join from root (in a normalized db) will be checked as a difference
  of idents that the root prop points to.  This can be used for determining if things like shared-fn need to be re-run,
  and if it would simply be quicker to keyframe render the entire tree.

  This is a naivé algorithm that is essentially `select-keys` on the root props. It does not interpret the query in
  any way."
  [app]
  [::app => boolean?]
  (let [{::keys [runtime-atom state-atom]} app
        {::keys [root-class]} @runtime-atom]
    (if-not (comp/get-query root-class)
      true
      (let [state-map       @state-atom
            prior-state-map (-> runtime-atom deref ::last-rendered-state)
            props-query     (props-only-query (comp/get-query root-class state-map))
            root-old        (select-keys prior-state-map props-query)
            root-new        (select-keys state-map props-query)]
        (not= root-old root-new)))))

(declare schedule-render!)

(>defn render!
  "Render the application immediately.  Prefer `schedule-render!`, which will ensure no more than 60fps.

  This is the central processing for render and cannot be overridden. `schedule-render!` will always invoke
  this function.  The optimized render is called by this function, which does extra bookkeeping and
  other supporting features common to all rendering.

  Options include:
  - `force-root?`: boolean.  When true disables all optimizations and forces a full root re-render.
  - anything your selected rendering optization system allows.  Shared props are updated via `shared-fn`
  only on `force-root?` and when (shallow) root props change.
  "
  ([app]
   [::app => any?]
   (render! app {:force-root? false}))
  ([app {:keys [force-root?] :as options}]
   [::app map? => any?]
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

     (swap! runtime-atom assoc ::last-rendered-state @state-atom)

     (let [limited-refresh? (seq (::only-refresh @runtime-atom))
           refresh?         (seq (::to-refresh @runtime-atom))]
       ;; limited refresh can cause missed refreshes. Clear only the limited ones, and schedule one more update.
       ;; If more limited refreshes arrive before that scheduled update, then they will run and block the requested
       ;; refreshes again, and cause this to try again.
       (if (and refresh? limited-refresh?)
         (do
           (swap! runtime-atom assoc ::only-refresh #{})
           (schedule-render! app))
         (swap! runtime-atom assoc
           ::to-refresh #{}
           ::only-refresh #{}))))))

(defn schedule-render!
  "Schedule a render on the next animation frame."
  ([app]
   (schedule-render! app {:force-root? false}))
  ([app options]
   (sched/schedule-animation! app ::render-scheduled? #(render! app options))))

(defn default-tx!
  "Default (Fulcro-2 compatible) transaction submission. The options map can contain any additional options
  that might be used by the transaction processing (or UI refresh).

  Some that may be supported (depending on application settings):

  - `:optimistic?` - boolean. Should the transaction be processed optimistically?
  - `:ref` - ident. The component ident to include in the transaction env.
  - `:component` - React element. The instance of the component that should appear in the transaction env.
  - `:refresh` - Vector containing idents (of components) and keywords (of props). Things that have changed and should be re-rendered
    on screen. Only necessary when the underlying rendering algorithm won't auto-detect, such as when UI is derived from the
    state of other components or outside of the directly queried props. Interpretation depends on the renderer selected:
    The ident-optimized render treats these as \"extras\".
  - `:only-refresh` - Vector of idents/keywords.  If the underlying rendering configured algorithm supports it: The
    components using these are the *only* things that will be refreshed in the UI.
    This can be used to avoid the overhead of looking for stale data when you know exactly what
    you want to refresh on screen as an extra optimization. Idents are *not* checked against queries.

  WARNING: `:only-refresh` can cause missed refreshes because rendering is debounced. If you are using this for
           rapid-fire updates like drag-and-drop it is recommended that on the trailing edge (e.g. drop) of your sequence you
           force a normal refresh via `app/render!`.

  If the `options` include `:ref` (which comp/transact! sets), then it will be auto-included on the `:refresh` list.

  NOTE: Fulcro 2 'follow-on reads' are supported and are added to the `:refresh` entries. Your choice of rendering
  algorithm will influence their necessity.

  Returns the transaction ID of the submitted transaction.
  "
  ([app tx]
   [::app ::txn/tx => ::txn/id]
   (default-tx! app tx {:optimistic? true}))
  ([{:keys [::runtime-atom] :as app} tx options]
   [:com.fulcrologic.fulcro.application/app ::txn/tx ::txn/options => ::txn/id]
   (txn/schedule-activation! app)
   (let [{:keys [refresh only-refresh ref] :as options} (merge {:optimistic? true} options)
         follow-on-reads (into #{} (filter #(or (keyword? %) (eql/ident? %)) tx))
         node            (txn/tx-node tx options)
         accumulate      (fn [r items] (into (set r) items))
         refresh         (cond-> (set refresh)
                           (seq follow-on-reads) (into follow-on-reads)
                           ref (conj ref))]
     (swap! runtime-atom (fn [s] (cond-> (update s ::txn/submission-queue (fnil conj []) node)
                                   ;; refresh sets are cumulative because rendering is debounced
                                   (seq refresh) (update ::to-refresh accumulate refresh)
                                   (seq only-refresh) (update ::only-refresh accumulate only-refresh))))
     (::txn/id node))))

(>defn default-remote-error?
  "Default detection of network errors. Returns true if the status-code of the given result
  map is not 200."
  [{:keys [status-code body] :as result}]
  [map? => boolean?]
  (not= 200 status-code))

(defn default-global-eql-transform
  "The default query transform function.  It makes sure the following items on a component query
  are never sent to the server:

  - Props whose namespace is `ui`
  - The form-state configuration join

  Takes an AST and returns the modified AST.
  "
  [ast]
  (let [kw-namespace (fn [k] (and (keyword? k) (namespace k)))]
    (util/elide-ast-nodes ast (fn [k]
                                (when-let [ns (some-> k kw-namespace)]
                                  (or
                                    (= k ::fs/config)
                                    (and
                                      (string? ns)
                                      (= "ui" ns))))))))

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
      - `default-result-action!` - A `(fn [env])` that will be used in your mutations defined with `defmutation` as the
        default `:result-action` when none is supplied. Normally defaults to a function that supports mutation joins, targeting,
        and ok/error actions. WARNING: Overriding this is for advanced users and can break important functionality. The
        default is value for this option is `com.fulcrologic.fulcro.mutations/default-result-action!`, which could be used
        as an element of your own custom implementation.
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
      - `:remote-error?` - A `(fn [result] boolean)`. It can examine the network result and should only return
        true when the result is an error. The `result` will contain both a `:body` and `:status-code` when using
        the normal remotes.  The default version of this returns true if the status code isn't 200.
      - `:global-error-action` - A `(fn [env] ...)` that is run on any remote error (as defined by `remote-error?`).
      - `:load-mutation` - A symbol. Defines which mutation to use as an implementation of low-level load operations. See
      Developer's Guide
      - `:query-transform-default` - A `(fn [query] query')`. Defaults to a function that strips `:ui/...` keywords and
      form state config joins from load queries.
      - `:load-marker-default` - A default value to use for load markers. Defaults to false.
    "
  ([] (fulcro-app {}))
  ([{:keys [props-middleware
            global-eql-transform
            global-error-action
            default-result-action!
            optimized-render!
            render-middleware
            initial-db
            client-did-mount
            remote-error?
            remotes
            query-transform-default
            load-marker-default
            load-mutation
            shared
            shared-fn] :as options}]
   {::id           (util/uuid)
    ::state-atom   (atom (or initial-db {}))
    ::config       {:load-marker-default     load-marker-default
                    :client-did-mount       (or client-did-mount (:started-callback options))
                    :query-transform-default query-transform-default
                    :load-mutation           load-mutation}
    ::algorithms   {:com.fulcrologic.fulcro.algorithm/tx!                    default-tx!
                    :com.fulcrologic.fulcro.algorithm/optimized-render!      (or optimized-render! ident-optimized/render!)
                    :com.fulcrologic.fulcro.algorithm/shared-fn              (or shared-fn (constantly {}))
                    :com.fulcrologic.fulcro.algorithm/render!                render!
                    :com.fulcrologic.fulcro.algorithm/remote-error?          (or remote-error? default-remote-error?)
                    :com.fulcrologic.fulcro.algorithm/global-error-action    global-error-action
                    :com.fulcrologic.fulcro.algorithm/merge*                 merge/merge*
                    :com.fulcrologic.fulcro.algorithm/default-result-action! (or default-result-action! mut/default-result-action!)
                    :com.fulcrologic.fulcro.algorithm/global-eql-transform   (or global-eql-transform default-global-eql-transform)
                    :com.fulcrologic.fulcro.algorithm/index-root!            indexing/index-root!
                    :com.fulcrologic.fulcro.algorithm/index-component!       indexing/index-component!
                    :com.fulcrologic.fulcro.algorithm/drop-component!        indexing/drop-component!
                    :com.fulcrologic.fulcro.algorithm/props-middleware       props-middleware
                    :com.fulcrologic.fulcro.algorithm/render-middleware      render-middleware
                    :com.fulcrologic.fulcro.algorithm/schedule-render!       schedule-render!}
    ::runtime-atom (atom
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

(>defn fulcro-app?
  "Returns true if the given `x` is a Fulcro application."
  [x]
  [any? => boolean?]
  (boolean
    (and (map? x) (contains? x ::state-atom) (contains? x ::runtime-atom))))

(>defn mounted?
  "Is the given app currently mounted on the DOM?"
  [{:keys [::runtime-atom]}]
  [::app => boolean?]
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
                                  (if (nil? dom-node)
                                    (log/error "Mount cannot find DOM node" node "to mount" (comp/class->registry-key root))
                                    (do
                                      (swap! (::runtime-atom app) assoc
                                        ::mount-node dom-node
                                        ::root-factory root-factory
                                        ::root-class root)
                                      (update-shared! app)
                                      (indexing/index-root! app)
                                      (schedule-render! app {:force-root? true})))))]
        (if (mounted? app)
          (reset-mountpoint!)
          (do
            (inspect/app-started! app)
            (when initialize-state?
              (let [initial-db   (-> app ::state-atom deref)
                    root-query   (comp/get-query root)
                    initial-tree (comp/get-initial-state root)
                    db-from-ui   (if root-query
                                   (-> (fnorm/tree->db root-query initial-tree true (merge/pre-merge-transform initial-tree))
                                     (merge/merge-alternate-union-elements root))
                                   initial-tree)
                    db           (util/deep-merge initial-db db-from-ui)]
                (reset! (::state-atom app) db)))
            (reset-mountpoint!)
            (when-let [cdm (-> app ::config :client-did-mount)]
              (cdm app))))))))

(defn app-root
  "Returns the current app root, if mounted."
  [app]
  (-> app ::runtime-atom deref ::app-root))

(defn force-root-render!
  "Force a re-render of the root. Runs a root query, disables shouldComponentUpdate, and renders the root component.
   This effectively forces React to do a full VDOM diff. Useful for things like UI refresh on hot code reload and
   changing locales where there are no real data changes, but the UI still needs to refresh.

   Argument can be anything that any->reconciler accepts.

   WARNING: This disables all Fulcro rendering optimizations, so it is much slower than other ways of refreshing the app.
   Use `schedule-render!` to request a normal optimized render."
  [app-ish]
  (when-let [app (comp/any->app app-ish)]
    (binding [comp/*blindly-render* true]
      (render! app {:force-root? true}))))

