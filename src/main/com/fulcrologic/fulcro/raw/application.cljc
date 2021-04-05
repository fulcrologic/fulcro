(ns com.fulcrologic.fulcro.raw.application
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    [com.fulcrologic.fulcro.algorithms.indexing :as indexing]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]
    [com.fulcrologic.fulcro.algorithms.scheduling :as sched]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.algorithms.tx-processing.synchronous-tx-processing :as stx]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.mutations :as mut]
    [com.fulcrologic.fulcro.raw.components :as comp]
    [edn-query-language.core :as eql]
    #?@(:cljs [[goog.object :as gobj]
               [goog.functions :refer [debounce]]
               [goog.dom :as gdom]])
    [taoensso.timbre :as log])
  #?(:clj (:import (clojure.lang IDeref))))

(defn basis-t
  "Return the current basis time of the app."
  [app]
  (-> app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/basis-t))

(defn current-state
  "Get the current value of the application state database. If called without arguments it will attempt to find the app
   in the dynamically-bound comp/*app*, which is bound during render."
  [app-or-component]
  (let [app (comp/any->app app-or-component)]
    (-> app :com.fulcrologic.fulcro.application/state-atom deref)))

(defn tick!
  "Move the basis-t forward one tick. For internal use in internal algorithms. Fulcro
  uses this to add metadata to props so it can detect the newer of two version of props."
  [app]
  (swap! (:com.fulcrologic.fulcro.application/runtime-atom app) update :com.fulcrologic.fulcro.application/basis-t inc))

(defn update-shared!
  "Force shared props to be recalculated. This updates the shared props on the app, and future renders will see the
   updated values. This is a no-op if no shared-fn is defined on the app. If you're using React 16+ consider using
   Context instead of shared."
  [{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app}]
  (try
    (if-let [shared-fn (ah/app-algorithm app :shared-fn)]
      (let [shared       (-> app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/static-shared-props)
            state        (current-state app)
            root-class   (-> app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/root-class)
            query        (comp/get-query root-class state)
            v            (fdn/db->tree query state state)
            shared-props (merge shared (shared-fn v))]
        (swap! runtime-atom assoc :com.fulcrologic.fulcro.application/shared-props shared-props))
      (swap! runtime-atom assoc :com.fulcrologic.fulcro.application/shared-props (-> app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/static-shared-props)))
    (catch #?(:cljs :default :clj Throwable) e
      (log/error e "Cannot compute shared"))))

(defn root-props-changed?
  "Returns true if the props queries directly by the root component of the app (if mounted) have changed since the last
  render.  This is a shallow analysis such that, for example, a join from root (in a normalized db) will be checked as a difference
  of idents that the root prop points to.  This can be used for determining if things like shared-fn need to be re-run,
  and if it would simply be quicker to keyframe render the entire tree.

  This is a naivé algorithm that is essentially `select-keys` on the root props. It does not interpret the query in
  any way."
  [app]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom state-atom]} app
        {:com.fulcrologic.fulcro.application/keys [root-class indexes]} @runtime-atom]
    (if-not (comp/get-query root-class @state-atom)
      true
      (let [state-map       @state-atom
            prior-state-map (-> runtime-atom deref :com.fulcrologic.fulcro.application/last-rendered-state)
            root-props      (:root-props indexes)
            root-old        (select-keys prior-state-map root-props)
            root-new        (select-keys state-map root-props)]
        (not= root-old root-new)))))

(defn render!
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
   (render! app {:force-root? false}))
  ([app {:keys [force-root?] :as options}]
   (tick! app)
   (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom state-atom]} app
         {:com.fulcrologic.fulcro.application/keys [root-class]} (some-> runtime-atom deref)]
     (when root-class
       (let [core-render!        (ah/app-algorithm app :core-render!)
             root-props-changed? (root-props-changed? app)]
         (when (or force-root? root-props-changed?)
           (update-shared! app))
         (when core-render!
           (core-render! app (merge options {:root-props-changed? root-props-changed?})))
         (swap! runtime-atom assoc
           :com.fulcrologic.fulcro.application/last-rendered-state @state-atom
           :com.fulcrologic.fulcro.application/only-refresh #{}
           :com.fulcrologic.fulcro.application/to-refresh #{})))
     (doseq [render-listener (-> runtime-atom deref :com.fulcrologic.fulcro.application/render-listeners vals)]
       (try
         (render-listener app options)
         (catch #?(:clj Exception :cljs :default) e
           (log/error e "Render listener failed.")))))))

(let [go! #?(:cljs (debounce (fn [app options]
                               (sched/schedule-animation! app :com.fulcrologic.fulcro.application/render-scheduled? #(render! app options))) 16)
             :clj (fn [app options]
                    (sched/schedule-animation! app :com.fulcrologic.fulcro.application/render-scheduled? #(render! app options))))]
  (defn schedule-render!
    "Schedule a render on the next animation frame."
    ([app]
     (schedule-render! app {:force-root? false}))
    ([app options]
     (go! app options))))

(defn default-remote-error?
  "Default detection of network errors. Returns true if the status-code of the given result
  map is not 200."
  [{:keys [status-code]}]
  (not= 200 status-code))

(defn default-global-eql-transform
  "The default query transform function.  It makes sure the following items on a component query
  are never sent to the server:

  - Props whose namespace is `ui`
  - Any prop or join that is namespaced to com.fulcrologic.fulcro*
  - Any ident (as a prop or join) whose table name is namespaced ui or com.fulcrologic.fulcro*

  Takes an AST and returns the modified AST.
  "
  [ast]
  (let [kw-namespace (fn [k] (and (keyword? k) (namespace k)))]
    (df/elide-ast-nodes ast (fn [k]
                              (let [ns       (some-> k kw-namespace)
                                    ident-ns (when (eql/ident? k) (some-> (first k) kw-namespace))]
                                (or
                                  (and
                                    (string? ns)
                                    (or
                                      (= "ui" ns)
                                      (str/starts-with? ns "com.fulcrologic.fulcro.")))
                                  (and
                                    (string? ident-ns)
                                    (or
                                      (= "ui" ident-ns)
                                      (str/starts-with? ident-ns "com.fulcrologic.fulcro.")))))))))

(defn initialize-state!
  "Initialize the app state using `root` component's app state. This will deep merge against any data that is already
  in the state atom of the app. Can be called before `mount!`, in which case you should tell mount not to (re) initialize
  state."
  [app root]
  (when #?(:clj true :cljs goog.DEBUG)
    (comp/check-component-registry!))
  (let [initial-db   (-> app :com.fulcrologic.fulcro.application/state-atom deref)
        root-query   (comp/get-query root initial-db)
        _            (util/dev-check-query root-query comp/component-name)
        initial-tree (comp/get-initial-state root)
        db-from-ui   (if root-query
                       (-> (fnorm/tree->db root-query initial-tree true (merge/pre-merge-transform initial-tree))
                         (merge/merge-alternate-union-elements root))
                       initial-tree)
        db           (util/deep-merge initial-db db-from-ui)]
    (reset! (:com.fulcrologic.fulcro.application/state-atom app) db)))

(def ^:deprecated default-tx! txn/default-tx!)

(defn fulcro-app
  "Create a new Fulcro application.

  This version creates an app that is not attached to React, and has no default root or optimized render.

  `options`: A map of initial options

   * `:initial-db` a *map* containing a *normalized* Fulcro app db.  Normally Fulcro will populate app state with
     your component tree's initial state.  Use `mount!` options to toggle the initial state pull from root.
   * `:optimized-render!` - A function that can analyze the state of the application and optimally refresh the screen.
     Defaults to `multiple-roots-renderer` (highly recommended), but other options are available in the rendering package.
     Further customizations are
     also possible.  Most applications will likely be best with the default. Standard Fulcro components are also pure
     (unless you supply `shouldComponentUpdate` to change that) to prevent rendering when props have not changed.
   * `default-result-action!` - A `(fn [env])` that will be used in your mutations defined with `defmutation` as the
     default `:result-action` when none is supplied. Normally defaults to a function that supports mutation joins, targeting,
     and ok/error actions. WARNING: Overriding this is for advanced users and can break important functionality. The
     default is value for this option is `com.fulcrologic.fulcro.mutations/default-result-action!`, which could be used
     as an element of your own custom implementation.
   * `:global-eql-transform` - A `(fn [AST] new-AST)` that will be asked to rewrite the AST of all transactions just
     before they are placed on the network layer.
   * `:client-will-mount` - A `(fn [app])` that is called after the application is fully initialized, but just before
   it mounts. This is triggered when you call `app/mount!`, but after all internals have been properly initialized.
   * `:client-did-mount` - A `(fn [app])` that is called when the application mounts the first time. WARNING: Due to
     the async nature of js and React this function is not guaranteed to be called after the application is
     completely on the DOM.  If you need that guarantee then consider using `:componentDidMount` on your application's
     root component.
   * `:remotes` - A map from remote name to a remote handler, which is defined as a map that contains at least
     a `:transmit!` key whose value is a `(fn [send-node])`. See `networking.http-remote`.
   * `:shared` - A (static) map of data that should be visible in all components through `comp/shared`.
   * `:shared-fn` - A function on root props that can select/augment shared whenever a forced root render
     or explicit call to `app/update-shared!` happens.
   * `:props-middleware` - A function that can add data to the 4th (optional) argument of
     `defsc`.  Useful for allowing users to quickly destructure extra data created by
     component extensions. See the fulcro-garden-css project on github for an example usage.
   * `:render-middleware` - A `(fn [this real-render])`. If supplied it will be called for every Fulcro component
     render, and *must* call (and return the result of) `real-render`.  This can be used to wrap the real render
     function in order to do things like measure performance, set dynamic vars, or augment the UI in arbitrary ways.
     `this` is the component being rendered.
   * `:remote-error?` - A `(fn [result] boolean)`. It can examine the network result and should only return
     true when the result is an error. The `result` will contain both a `:body` and `:status-code` when using
     the normal remotes.  The default version of this returns true if the status code isn't 200.
   * `:global-error-action` - A `(fn [env] ...)` that is run on any remote error (as defined by `remote-error?`).
   * `:load-mutation` - A symbol. Defines which mutation to use as an implementation of low-level load operations. See
     Developer's Guide
   * `:query-transform-default` - DEPRECATED. This will break things in unexpected ways. Prefer `:global-eql-transform`.
   * `:load-marker-default` - A default value to use for load markers. Defaults to false.
   * `:core-render!` - A (fn [app] side-effect) that is called by schedule render.
   * `:render-root!` - The function to call in order to render the root of your application. Defaults
     to `js/ReactDOM.render`.
   * `:hydrate-root!` - The function to call in order to hydrate the root of your application. Defaults
     to `js/ReactDOM.hydrate`.
   * `:unmount-root!` - The function to call in order to unmount the root of your application. Defaults
     to `js/ReactDOM.unmountComponentAtNode`.
   * `:root-class` - The component class that will be the root. This can be specified just with `mount!`, but
   giving it here allows you to do a number of tasks against the app before it is actually mounted. You can also use `app/set-root!`.
   * `:submit-transaction!` - A function to implement how to submit transactions. This allows you to override how transactions
     are processed in Fulcro.  Calls to `comp/transact!` will come through this algorithm.
   * `:abort-transaction!` - The function that can abort submitted transactions. Must be provided if you override
     `:submit-transaction!`, since the two are related."
  ([] (fulcro-app {}))
  ([{:keys [props-middleware
            global-eql-transform
            global-error-action
            default-result-action!
            core-render!
            optimized-render!
            render-root!
            hydrate-root!
            unmount-root!
            submit-transaction!
            abort-transaction!
            render-middleware
            initial-db
            client-will-mount
            client-did-mount
            remote-error?
            remotes
            query-transform-default
            load-marker-default
            load-mutation
            root-class
            shared
            external-config
            shared-fn] :as options}]
   (let [tx! (or submit-transaction! txn/default-tx!)]
     {:com.fulcrologic.fulcro.application/id           (tempid/uuid)
      :com.fulcrologic.fulcro.application/state-atom   (atom (or initial-db {}))
      :com.fulcrologic.fulcro.application/config       {:load-marker-default     load-marker-default
                                                        :client-did-mount        (or client-did-mount (:started-callback options))
                                                        :client-will-mount       client-will-mount
                                                        :external-config         external-config
                                                        :query-transform-default query-transform-default
                                                        :load-mutation           load-mutation}
      :com.fulcrologic.fulcro.application/algorithms   {:com.fulcrologic.fulcro.algorithm/tx!                    tx!
                                                        :com.fulcrologic.fulcro.algorithm/abort!                 (or abort-transaction! txn/abort!)
                                                        :com.fulcrologic.fulcro.algorithm/core-render!           (or core-render! identity)
                                                        :com.fulcrologic.fulcro.algorithm/optimized-render!      (or optimized-render! identity)
                                                        :com.fulcrologic.fulcro.algorithm/initialize-state!      initialize-state!
                                                        :com.fulcrologic.fulcro.algorithm/shared-fn              shared-fn
                                                        :com.fulcrologic.fulcro.algorithm/render-root!           render-root!
                                                        :com.fulcrologic.fulcro.algorithm/hydrate-root!          hydrate-root!
                                                        :com.fulcrologic.fulcro.algorithm/unmount-root!          unmount-root!
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
      :com.fulcrologic.fulcro.application/runtime-atom (atom
                                                         {:com.fulcrologic.fulcro.application/app-root            nil
                                                          :com.fulcrologic.fulcro.application/mount-node          nil
                                                          :com.fulcrologic.fulcro.application/root-class          root-class
                                                          :com.fulcrologic.fulcro.application/root-factory        nil
                                                          :com.fulcrologic.fulcro.application/basis-t             1
                                                          :com.fulcrologic.fulcro.application/last-rendered-state {}

                                                          :com.fulcrologic.fulcro.application/static-shared-props shared
                                                          :com.fulcrologic.fulcro.application/shared-props        {}

                                                          :com.fulcrologic.fulcro.application/remotes             (or remotes
                                                                                                                    {:remote {:transmit! (fn [{::txn/keys [result-handler]}]
                                                                                                                                           (log/fatal "Remote requested, but no remote defined.")
                                                                                                                                           (result-handler {:status-code 418 :body {}}))}})
                                                          :com.fulcrologic.fulcro.application/indexes             {:ident->components {}}
                                                          :com.fulcrologic.fulcro.application/mutate              mut/mutate
                                                          :com.fulcrologic.fulcro.application/render-listeners    (cond-> {}
                                                                                                                    (= tx! txn/default-tx!) (assoc ::txn/after-render txn/application-rendered!))
                                                          ::txn/activation-scheduled?                             false
                                                          ::txn/queue-processing-scheduled?                       false
                                                          ::txn/sends-scheduled?                                  false
                                                          ::txn/submission-queue                                  []
                                                          ::txn/active-queue                                      []
                                                          ::txn/send-queues                                       {}})})))

(defn fulcro-app?
  "Returns true if the given `x` is a Fulcro application."
  [x]
  (boolean
    (and (map? x) (contains? x :com.fulcrologic.fulcro.application/state-atom) (contains? x :com.fulcrologic.fulcro.application/runtime-atom))))

(defn abort!
  "Attempt to abort the send queue entries with the given abort ID.

  NOTE: This can be redefined on an application. If you change your transaction processing routing, then the built-in
  version will not work, and this docstring does not apply.

  Will notify any aborted operations (e.g. result-handler
  will be invoked, remote-error? will be used to decide if you consider that an error, etc.).
  The result map from an abort will include `{::txn/aborted? true}`, but will not include `:status-code` or `:body`.

  This function affects both started and non-started items in the send queues, but will not affect submissions that have not yet
  made it to the network processing layer (things still in top-level transaction submission queue).

  So the sequence of calls:

  ```
  (comp/transact! this `[(f)] {:abort-id :a})
  (app/abort! this :a)
  ```

  will cancel anything active with abort id `:a`, but since you've held the thread the entire time the submission of
  mutation `(f)` is still on the submission queue and will not be aborted.

  - `app-ish`: Anything that can be coerced to an app with comp/any->app.
  - `abort-id`: The abort ID of the operations to be aborted.
  "
  [app-ish abort-id]
  (let [app (comp/any->app app-ish)]
    (when-let [abort! (ah/app-algorithm app :abort!)]
      (abort! app abort-id))))

(defn add-render-listener!
  "Add (or replace) a render listener named `nm`. `listener` is a `(fn [app options] )` that will be called
   after each render."
  [app nm listener]
  (swap! (:com.fulcrologic.fulcro.application/runtime-atom app) assoc-in [:com.fulcrologic.fulcro.application/render-listeners nm] listener))

(defn remove-render-listener!
  "Remove the render listener named `nm`."
  [app nm]
  (swap! (:com.fulcrologic.fulcro.application/runtime-atom app) update :com.fulcrologic.fulcro.application/render-listeners dissoc nm))

(defn headless-synchronous-app
  "Returns a new instance from `fulcro-app` that is pre-configured to use synchronous transaction processing
   and no rendering. This is particularly useful when you want to write integration tests around a Fulcro
   app so that the tests need no async support. The `faux-root` must be a component (which need have no body).

   The returned application will be properly initialized, and will have the initial state declared in `faux-component`
   already merged into the app's state (i.e. the returned app is ready for operations).

   `options` can be anything from `fulcro-app`, but :submit-transaction!, :render-root!, and
   :optimized-render! are ignored."
  ([faux-root]
   (headless-synchronous-app faux-root {}))
  ([faux-root options]
   (let [app (stx/with-synchronous-transactions
               (fulcro-app (merge options
                             {:render-root!      (constantly true)
                              :optimized-render! (constantly true)})))]
     (initialize-state! app faux-root)
     app)))

(defn set-remote!
  "Add/replace a remote on the given app. `remote-name` is a keyword, and `remote` is a Fulcro remote (map containing
  at least `transmit!`).

  This function is *generally* safe to call at any time. Requests that are in-flight on an old version of the remote will complete
  on that remote, and any that are queued will be processed by the new one; however, if the old remote supported abort
  operations then an abort on in-flight requests of the old remote will not work (since you're replaced the remote that the details
  about that request).

  This function changes the content of the application's runtime atom so you do not need to capture the return value, which
  is the app you passed in."
  [app remote-name remote]
  (swap! (:com.fulcrologic.fulcro.application/runtime-atom app) assoc-in [:com.fulcrologic.fulcro.application/remotes remote-name] remote)
  app)
