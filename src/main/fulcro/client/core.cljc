(ns fulcro.client.core
  (:require
    [fulcro.client.primitives :as prim]
    [fulcro.client.impl.application :as app]
    #?(:cljs fulcro.client.mutations)                       ; DO NOT REMOVE. Ensures built-in mutations load on start
    [fulcro.client.network :as net]
    [fulcro.client.logging :as log]
    #?(:clj
    [clojure.core.async :as async] :cljs [cljs.core.async :as async])
    [fulcro.client.impl.protocols :as proto]
    [fulcro.util :as util]
    [fulcro.client.util :as cutil]
    #?(:clj
    [clojure.future :refer :all])
    [clojure.set :as set]
    #?(:cljs [goog.dom :as gdom])
    [clojure.spec.alpha :as s]
    [fulcro.history :as hist]
    [fulcro.client.impl.protocols :as p])
  #?(:cljs (:import goog.Uri)))

(declare map->Application merge-alternate-union-elements! merge-state! new-fulcro-client new-fulcro-test-client )

(defonce fulcro-tools (atom {}))

(s/def ::tool-id (s/and keyword? namespace))
(s/def ::tx-listen (s/fspec :args (s/cat :env map? :tx-info map?) :ret any?))
(s/def ::network-wrapper (s/fspec :args (s/cat :networks map?) :ret map?))
(s/def ::app-started (s/fspec :args (s/cat :fulcro-app map?) :ret any?))
(s/def ::lifecycle-event #{:mounted :unmounted})
(s/def ::instrument (s/fspec :args (s/cat :args (s/keys :req-un [::props ::children ::class ::factory])) :ret any?))
(s/def ::component-lifecycle (s/fspec :args (s/cat :react-component prim/component? :event ::lifecycle-event) :ret any?))
(s/def ::instrument-wrapper (s/fspec :args (s/cat :existing-instrument ::instrument) :ret ::instrument))
(s/def ::tool-registry (s/keys :req [::tool-id] :opt [::tx-listen ::network-wrapper ::app-started ::component-lifecycle ::instrument-wrapper]))

(defn register-tool
  "Register a debug tool. When an app starts, the debug tool can have several hooks that are notified:

  ::tool-id some identifier to place the tool into the tool map
  ::tx-listen is a (fn [tx info] ...) that will be called on every `transact!` of the app. Return value is ignored.
  ::network-wrapper is (fn [network-map] network-map') that will be given the networking config BEFORE it is initialized. You can wrap
  them, but you MUST return a compatible map out or you'll disable networking.
  ::component-lifecycle is (fn [component evt]) that is called with evt of :mounted and :unmounted to tell you when the given component mounts/unmounts.
  ::instrument-wrapper is a (fn [instrument] instrument') that allows you to wrap your own instrumentation (for rendering) around any existing (which may be nil)
  ::app-started (fn [app] ...) that will be called once the app is mounted, just like started-callback. Return value ignored."
  [{:keys [::tool-id] :as tool-registry}]
  ;(util/conform! ::tool-registry tool-registry)
  (swap! fulcro-tools assoc tool-id tool-registry))

(defn- normalize-network [networking]
  #?(:cljs (if (implements? net/FulcroNetwork networking) {:remote networking} networking)
     :clj  {}))

(defn- add-tools [original-start original-net original-tx-listen original-instrument]
  (let [net     (normalize-network original-net)
        listen  (or original-tx-listen (constantly nil))
        started (or original-start (constantly nil))]
    (reduce
      (fn [[start net listen instrument lifecycle] {:keys [::tool-id ::tx-listen ::network-wrapper ::app-started ::instrument-wrapper ::component-lifecycle]}]
        (let [start      (if app-started (fn [app] (app-started app) (start app)) start)
              net        (if network-wrapper (network-wrapper net) net)
              listen     (if tx-listen (fn [env info] (tx-listen env info)) listen)
              instrument (if instrument-wrapper (instrument-wrapper instrument) instrument)
              lifecycle  (if component-lifecycle (fn [c e] (component-lifecycle c e) (when lifecycle (lifecycle c e))) lifecycle)]
          [start net listen instrument lifecycle]))
      [started net listen nil nil]
      (vals @fulcro-tools))))

(defn iinitial-app-state?
  "Returns true if the class has the static InitialAppState protocol."
  #?(:cljs {:tag boolean})
  [x]
  #?(:clj  (if (fn? x)
             (some? (-> x meta :initial-state))
             (let [class (cond-> x (prim/component? x) class)]
               (extends? prim/InitialAppState class)))
     :cljs (implements? prim/InitialAppState x)))

(defn iident?
  "Returns true if the class has the stati Ident protocol."
  #?(:cljs {:tag boolean})
  [x]
  #?(:clj  (if (fn? x)
             (some? (-> x meta :ident))
             (let [class (cond-> x (prim/component? x) class)]
               (extends? prim/Ident class)))
     :cljs (implements? prim/Ident x)))


(defn new-fulcro-client
  "Entrypoint for creating a new fulcro client. Instantiates an Application with default values, unless
  overridden by the parameters. If you do not supply a networking object, one will be provided that connects to the
  same server the application was served from, at `/api`.

  If you supply a `:request-transform` it must be a function:

  ```
 (fn [{:keys [body headers]}] {:body body' :headers headers'})
  ```

  it can replace the outgoing EDN of body or headers (returning both as a vector). NOTE: Both of these are clojurescript types.
  The edn will be encoded with transit, and the headers will be converted to a js map. IMPORTANT: Only supported
  when using the default built-in single-remote networking.

  `:initial-state` is your applications initial state. If it is an atom, it *must* be normalized. Fulcro databases
  always have normalization turned on (for server data merging). If it is not an atom, it will be auto-normalized.

  `:started-callback` is an optional function that will receive the intiailized fulcro application after it is
  mounted in the DOM, and is useful for triggering initial loads, routing mutations, etc. The reconciler is available
  under the `:reconciler` key (and you can access the app state, root node, etc from there.)

  `:network-error-callback` is a function of two arguments, the app state atom and the error, which will be invoked for
  every network error (status code >= 400, or no network found), should you choose to use the default built-in
  networking.

  `:migrate` is optional. It is a (fn [state tid->rid] ... state') that should return a new state where all tempids
  (the keys of `tid->rid`) are rewritten to real ids (the values of tid->rid). This defaults to a full recursive
  algorithm against all data in the app-state, which is correct but possibly slow).  Note that tempids will have an tempid data type.
  See reconciler documentation for further information.

  `:transit-handlers` (optional). A map with keys for `:read` and `:write`, which contain maps to be used for the read
  and write side of transit to extend the supported data types. See `make-fulcro-network` in network.cljs. Only used
  when you default to the built-in networking.

  `:shared` (optional). A map of arbitrary values to be shared across all components, accessible to them via (prim/shared this)

  `:read-local` (optional). An read function for the Parser. (fn [env k params] ...). If supplied,
  it will be called once for each root-level query key. If it returns `nil` or `false` for that key then the built-in Fulcro read will handle that
  branch of the root query. If it returns a map with the shape `{:value ...}`, then that will be used for the response. This is *not*
  recursive. If you begin handling a *branch* (e.g. a join), you must finish doing so (though if using recursion, you can technically handle just
  the properties that need your custom handling). At any time you can use `prim/db->tree` to get raw graph data from the database for a branch.
  NOTE: *it will be allowed* to trigger remote reads. This is not recommended, as you will probably have to augment the networking layer to
  get it to do what you mean. Use `load` instead. You have been warned. Triggering remote reads is allowed, but discouraged and unsupported.

  `:networking` (optional). An instance of FulcroNetwork that will act as the default remote (named :remote). If
  you want to support multiple remotes, then this should be a map whose keys are the keyword names of the remotes
  and whose values are FulcroNetwork instances.

  `:mutation-merge (optional). A function `(fn [state mutation-symbol return-value])` that receives the app state as a
  map (NOT an atom) and should return the new state as a map. This function is run when network results are being merged,
  and is called once for each mutation that had a return value on the server. Returning nil from this function is safe, and will be ignored
  with a console message for debugging. If you need information about the original mutation arguments then you must reflect
  them back from the server in your return value. By default such values are discarded.
  
  `:reconciler-options (optional). A map that will be merged into the reconciler options. Currently it's mostly
  useful to override things like :root-render and :root-unmount for React Native Apps.`

  There is currently no way to circumvent the encoding of the body into transit. If you want to talk to other endpoints
  via alternate protocols you must currently implement that outside of the framework (e.g. global functions/state).
  "
  [& {:keys [initial-state mutation-merge started-callback networking reconciler-options
             read-local request-transform network-error-callback migrate transit-handlers shared]
      :or   {initial-state {} read-local (constantly false) started-callback (constantly nil) network-error-callback (constantly nil)
             migrate       nil shared nil}}]
  (let [networking (or networking #?(:clj nil :cljs (net/make-fulcro-network "/api"
                                                      :request-transform request-transform
                                                      :transit-handlers transit-handlers
                                                      :global-error-callback network-error-callback)))
        [started-callback networking tx-listen instrument lifecycle] (add-tools started-callback networking (:tx-listen reconciler-options)
                                                                       (:instrument reconciler-options))]
    (map->Application {:initial-state      initial-state
                       :read-local         read-local
                       :mutation-merge     mutation-merge
                       :started-callback   started-callback
                       :lifecycle          lifecycle
                       :reconciler-options (merge (cond-> {}
                                                    tx-listen (assoc :tx-listen tx-listen)
                                                    instrument (assoc :instrument instrument)
                                                    lifecycle (assoc :lifecycle lifecycle)
                                                    migrate (assoc :migrate migrate)
                                                    shared (assoc :shared shared))
                                             reconciler-options)
                       :networking         networking})))


(defprotocol FulcroApplication
  (mount [this root-component target-dom-id] "Start/replace the webapp on the given DOM ID or DOM Node.")
  (reset-state! [this new-state] "Replace the entire app state with the given (pre-normalized) state.")
  (reset-app! [this root-component callback] "Replace the entire app state with the initial app state defined on the root component (includes auto-merging of unions). callback can be nil, a function, or :original (to call original started-callback).")
  (clear-pending-remote-requests! [this remotes] "Remove all pending network requests on the given remote(s). Useful on failures to eliminate cascading failures. Remote can be a keyword, set, or nil. `nil` means all remotes.")
  (refresh [this] "Refresh the UI (force re-render). NOTE: You MUST support :key on your root DOM element with the :ui/react-key value from app state for this to work.")
  (history [this] "Return the current UI history of the application, suitable for network transfer")
  (reset-history! [this] "Returns the application with history reset to its initial, empty state. Resets application history to its initial, empty state. Suitable for resetting the app for situations such as user log out."))

(defn merge-component
  "Given a state map of the application database, a component, and a tree of component-data: normalizes
   the tree of data and merges the component table entries into the state, returning a new state map.
   Since there is not an implied root, the component itself won't be linked into your graph (though it will
   remain correctly linked for its own consistency).
   Therefore, this function is just for dropping normalized things into tables
   when they themselves have a recursive nature. This function is useful when you want to create a new component instance
   and put it in the database, but the component instance has recursive normalized state. This is a basically a
   thin wrapper around `prim/tree->db`."
  [state-map component component-data]
  (if-let [top-ident (prim/get-ident component component-data)]
    (let [query          [{top-ident (prim/get-query component)}]
          state-to-merge {top-ident component-data}
          table-entries  (-> (prim/tree->db query state-to-merge true)
                           (dissoc ::prim/tables top-ident))]
      (util/deep-merge state-map table-entries))
    state-map))

(defn merge-alternate-unions
  "Walks the given query and calls (merge-fn parent-union-component union-child-initial-state) for each non-default element of a union that has initial app state.
  You probably want to use merge-alternate-union-elements[!] on a state map or app."
  [merge-fn root-component]
  (letfn [(walk-ast
            ([ast visitor]
             (walk-ast ast visitor nil))
            ([{:keys [children component type dispatch-key union-key key] :as parent-ast} visitor parent-union]
             (when (and component parent-union (= :union-entry type))
               (visitor component parent-union))
             (when children
               (doseq [ast children]
                 (cond
                   (= (:type ast) :union) (walk-ast ast visitor component) ; the union's component is on the parent join
                   (= (:type ast) :union-entry) (walk-ast ast visitor parent-union)
                   ast (walk-ast ast visitor nil))))))
          (merge-union [component parent-union]
            (let [default-initial-state   (and parent-union (iinitial-app-state? parent-union) (prim/get-initial-state parent-union {}))
                  to-many?                (vector? default-initial-state)
                  component-initial-state (and component (iinitial-app-state? component) (prim/get-initial-state component {}))]
              (when-not default-initial-state
                (log/warn "WARNING: Subelements of union " (.. parent-union -displayName) " have initial state. This means your default branch of the union will not have initial application state."))
              (when (and component component-initial-state parent-union (not to-many?) (not= default-initial-state component-initial-state))
                (merge-fn parent-union component-initial-state))))]
    (walk-ast
      (prim/query->ast (prim/get-query root-component))
      merge-union)))

;q: {:a (gq A) :b (gq B)
;is: (is A)  <-- default branch
;state:   { kw { id [:page :a]  }}
(defn merge-alternate-union-elements!
  "Walks the query and initial state of root-component and merges the alternate sides of unions with initial state into
  the application state database. See also `merge-alternate-union-elements`, which can be used on a state map and
  is handy for server-side rendering. This function side-effects on your app, and returns nothing."
  [app root-component]
  (merge-alternate-unions (partial merge-state! app) root-component))

(defn merge-alternate-union-elements
  "Just like merge-alternate-union-elements!, but usable from within mutations and on server-side rendering. Ensures
  that when a component has initial state it will end up in the state map, even if it isn't currently in the
  initial state of the union component (which can only point to one at a time)."
  [state-map root-component]
  (let [initial-state  (prim/get-initial-state root-component nil)
        state-map-atom (atom state-map)
        merge-to-state (fn [comp tree] (swap! state-map-atom merge-component comp tree))
        _              (merge-alternate-unions merge-to-state root-component)
        new-state      @state-map-atom]
    new-state))

(defn- start-networking
  "Starts all remotes in a map. If a remote's `start` returns something that implements `FulcroNetwork`,
  update the network map with this value. Returns possibly updated `network-map`."
  [network-map]
  #?(:cljs (into {} (for [[k remote] network-map
                          :let [started (net/start remote)
                                valid   (if (implements? net/FulcroNetwork started) started remote)]]
                      [k valid]))
     :clj  {}))

(defn- initialize
  "Initialize the fulcro Application. Creates network queue, sets up i18n, creates reconciler, mounts it, and returns
  the initialized app"
  [{:keys [networking read-local started-callback lifecycle] :as app} initial-state root-component dom-id-or-node reconciler-options]
  (let [network-map         (normalize-network networking)
        remotes             (keys network-map)
        send-queues         (zipmap remotes (map #(async/chan 1024) remotes))
        response-channels   (zipmap remotes (map #(async/chan) remotes))
        parser              (prim/parser {:elide-paths true :read (partial app/read-local read-local) :mutate app/write-entry-point})
        initial-app         (assoc app :send-queues send-queues :response-channels response-channels
                                       :parser parser :mounted? true)
        app-with-networking (assoc initial-app :networking (start-networking network-map))
        rec                 (app/generate-reconciler app-with-networking initial-state parser reconciler-options)
        completed-app       (assoc app-with-networking :reconciler rec)
        node #?(:cljs (if (string? dom-id-or-node)
                        (gdom/getElement dom-id-or-node)
                        dom-id-or-node)
                :clj        dom-id-or-node)]
    (app/initialize-internationalization rec)
    (app/initialize-global-error-callbacks completed-app)
    (app/start-network-sequential-processing completed-app)
    (merge-alternate-union-elements! completed-app root-component)
    (prim/add-root! rec root-component node)
    (when started-callback
      (started-callback completed-app))
    completed-app))

(defn clear-queue
  "Needed for mocking in tests. Do not use directly. Use FulcroApplication protocol methods instead."
  [queue]
  (loop [element (async/poll! queue)]
    (when element
      (recur (async/poll! queue)))))

(defn reset-history-impl
  "Needed for mocking in tests. Use FulcroApplication protocol methods instead."
  [{:keys [reconciler]}]
  #?(:cljs (when-let [hist-atom (prim/get-history reconciler)]
             (swap! hist-atom (fn [{:keys [::hist/max-size]}] (hist/new-history max-size))))))

(defn refresh* [{:keys [reconciler] :as app} root target]
  ; NOTE: from devcards, the mount target node could have changed. So, we re-call add-root
  (let [old-target     (-> reconciler :state deref :target)
        target #?(:clj target
                  :cljs (if (string? target) (gdom/getElement target) target))]
    (when (and old-target (not (identical? old-target target)))
      (log/info "Mounting on newly supplied target.")
      (prim/remove-root! reconciler old-target)
      (prim/add-root! reconciler root target)))
  (log/info "RERENDER: NOTE: If your UI doesn't change, make sure you query for :ui/react-key on your Root and embed that as :key in your top-level DOM element")
  (cutil/force-render reconciler))

(defn mount* [{:keys [mounted? initial-state reconciler-options] :as app} root-component dom-id-or-node]
  (if mounted?
    (do
      (refresh* app root-component dom-id-or-node)
      app)
    (let [uses-initial-app-state? (iinitial-app-state? root-component)
          ui-declared-state       (and uses-initial-app-state? (fulcro.client.primitives/initial-state root-component nil))
          explicit-state?         (or (util/atom? initial-state) (and (seq initial-state) (map? initial-state)))
          init-conflict?          (and explicit-state? (iinitial-app-state? root-component))
          state                   (cond
                                    explicit-state? (if initial-state initial-state {})
                                    ui-declared-state ui-declared-state
                                    :otherwise {})]
      (initialize app state root-component dom-id-or-node reconciler-options))))

(defrecord Application [initial-state mutation-merge started-callback remotes networking send-queues response-channels reconciler read-local parser mounted? reconciler-options]
  FulcroApplication
  (mount [this root-component dom-id-or-node] (mount* this root-component dom-id-or-node))

  (reset-state! [this new-state] (reset! (prim/app-state reconciler) new-state))

  (reset-app! [this root-component callback]
    (if (not (iinitial-app-state? root-component))
      (log/error "The specified root component does not implement InitialAppState!")
      (let [base-state (prim/tree->db root-component (fulcro.client.primitives/initial-state root-component nil) true)]
        (clear-pending-remote-requests! this nil)
        (reset! (prim/app-state reconciler) base-state)
        (reset-history! this)
        (merge-alternate-union-elements! this root-component)
        (log/info "updated app state to original " (prim/app-state reconciler))
        (cond
          (= callback :original) (started-callback this)
          callback (callback this))
        (refresh this))))

  (clear-pending-remote-requests! [this remotes]
    (let [remotes (cond
                    (nil? remotes) (keys send-queues)
                    (keyword? remotes) [remotes]
                    :else remotes)]
      (doseq [r remotes]
        (clear-queue (get send-queues r)))))

  (history [this] (prim/get-history reconciler))
  (reset-history! [this]
    (reset-history-impl this))

  (refresh [this]
    (log/info "RERENDER: NOTE: If your UI doesn't change, make sure you query for :ui/react-key on your Root and embed that as :key in your top-level DOM element")
    (cutil/force-render reconciler)))

(defn new-fulcro-test-client
  "Create a test client that has no networking. Useful for UI testing with a real Fulcro app container."
  [& {:keys [initial-state started-callback reconciler-options]
      :or   {initial-state {} started-callback nil}}]
  (map->Application {:initial-state      initial-state
                     :started-callback   started-callback
                     :reconciler-options reconciler-options
                     :networking         (net/mock-network)}))

#?(:cljs
   (defn get-url
     "Get the current window location from the browser"
     [] (-> js/window .-location .-href)))

#?(:cljs
   (defn uri-params
     "Get the current URI parameters from the browser url or one you supply"
     ([] (uri-params (get-url)))
     ([url]
      (let [query-data (.getQueryData (goog.Uri. url))]
        (into {}
          (for [k (.getKeys query-data)]
            [k (.get query-data k)]))))))

#?(:cljs
   (defn get-url-param
     "Get the value of the named parameter from the browser URL (or an explicit one)"
     ([param-name] (get-url-param (get-url) param-name))
     ([url param-name]
      (get (uri-params url) param-name))))

(defn- component-merge-query
  "Calculates the query that can be used to pull (or merge) a component with an ident
  to/from a normalized app database. Requires a tree of data that represents the instance of
  the component in question (e.g. ident will work on it)"
  [component object-data]
  (let [ident        (prim/ident component object-data)
        object-query (prim/get-query component)]
    [{ident object-query}]))

(defn- preprocess-merge
  "Does the steps necessary to honor the data merge technique defined by Fulcro with respect
  to data overwrites in the app database."
  [state-atom component object-data]
  (let [ident         (prim/get-ident component object-data)
        object-query  (prim/get-query component)
        object-query  (if (map? object-query) [object-query] object-query)
        base-query    (component-merge-query component object-data)
        ;; :fulcro/merge is way to make unions merge properly when joined by idents
        merge-query   [{:fulcro/merge base-query}]
        existing-data (get (prim/db->tree base-query @state-atom @state-atom) ident {})
        marked-data   (prim/mark-missing object-data object-query)
        merge-data    {:fulcro/merge {ident (util/deep-merge existing-data marked-data)}}]
    {:merge-query merge-query
     :merge-data  merge-data}))

(defn- is-atom?
  "Returns TRUE when x is an atom."
  [x]
  (instance? #?(:cljs cljs.core.Atom
                :clj  clojure.lang.Atom) x))

(def integrate-ident
  "Integrate an ident into any number of places in the app state. This function is safe to use within mutation
  implementations as a general helper function.

  The named parameters can be specified any number of times. They are:

  - append:  A vector (path) to a list in your app state where this new object's ident should be appended. Will not append
  the ident if that ident is already in the list.
  - prepend: A vector (path) to a list in your app state where this new object's ident should be prepended. Will not append
  the ident if that ident is already in the list.
  - replace: A vector (path) to a specific location in app-state where this object's ident should be placed. Can target a to-one or to-many.
   If the target is a vector element then that element must already exist in the vector."
  cutil/integrate-ident)

(defn integrate-ident!
  "Integrate an ident into any number of places in the app state. This function is safe to use within mutation
  implementations as a general helper function.

  The named parameters can be specified any number of times. They are:

  - append:  A vector (path) to a list in your app state where this new object's ident should be appended. Will not append
  the ident if that ident is already in the list.
  - prepend: A vector (path) to a list in your app state where this new object's ident should be prepended. Will not append
  the ident if that ident is already in the list.
  - replace: A vector (path) to a specific location in app-state where this object's ident should be placed. Can target a to-one or to-many.
   If the target is a vector element then that element must already exist in the vector.
  "
  [state ident & named-parameters]
  (assert (is-atom? state)
    "The state has to be an atom. Use 'integrate-ident' instead.")
  (apply swap! state integrate-ident ident named-parameters))


(defn merge-state!
  "Normalize and merge a (sub)tree of application state into the application using a known UI component's query and ident.

  This utility function obtains the ident of the incoming object-data using the UI component's ident function. Once obtained,
  it uses the component's query and ident to normalize the data and place the resulting objects in the correct tables.
  It is also quite common to want those new objects to be linked into lists in other spot in app state, so this function
  supports optional named parameters for doing this. These named parameters can be repeated as many times as you like in order
  to place the ident of the new object into other data structures of app state.

  This function honors the data merge story for Fulcro: attributes that are queried for but do not appear in the
  data will be removed from the application. This function also uses the initial state for the component as a base
  for merge if there was no state for the object already in the database.

  This function will also trigger re-renders of components that directly render object merged, as well as any components
  into which you integrate that data via the named-parameters.

  This function is primarily meant to be used from things like server push and setTimeout/setInterval, where you're outside
  of the normal mutation story. Do not use this function within abstract mutations.

  - app-or-reconciler: The Fulcro application or reconciler
  - component: The class of the component that corresponsds to the data. Must have an ident.
  - object-data: A map (tree) of data to merge. Will be normalized for you.
  - named-parameter: Post-processing ident integration steps. see integrate-ident!

  Any keywords that appear in ident integration steps will be added to the re-render queue.

  See also `fulcro.client.primitives/merge!`.
  "
  [app-or-reconciler component object-data & named-parameters]
  (when-not (iident? component) (log/warn "merge-state!: component must implement Ident"))
  (let [ident          (prim/get-ident component object-data)
        reconciler     (if #?(:cljs (implements? FulcroApplication app-or-reconciler)
                              :clj  (satisfies? FulcroApplication app-or-reconciler))
                         (:reconciler app-or-reconciler)
                         app-or-reconciler)
        state          (prim/app-state reconciler)
        data-path-keys (->> named-parameters (partition 2) (map second) flatten (filter keyword?) set vec)
        {:keys [merge-data merge-query]} (preprocess-merge state component object-data)]
    (prim/merge! reconciler merge-data merge-query)
    (swap! state dissoc :fulcro/merge)
    (apply integrate-ident! state ident named-parameters)
    (proto/queue! reconciler data-path-keys)
    @state))


