(ns fulcro.client.core
  #?(:cljs (:require-macros [fulcro.client.core :refer [defsc]]))
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
    #?(:clj [clojure.future :refer :all])
    [clojure.set :as set]
    #?(:cljs [goog.dom :as gdom])
    [clojure.spec.alpha :as s]
    [fulcro.history :as hist]
    [fulcro.client.impl.protocols :as p])
  #?(:cljs (:import goog.Uri)))

(declare map->Application merge-alternate-union-elements! merge-state! new-fulcro-client new-fulcro-test-client InitialAppState)

(defonce fulcro-tools (atom {}))

(s/def ::tool-id (s/and keyword? namespace))
(s/def ::tx-listen (s/fspec :args (s/cat :env map? :tx-info map?) :ret any?))
(s/def ::network-wrapper (s/fspec :args (s/cat :networks map?) :ret map?))
(s/def ::app-started (s/fspec :args (s/cat :fulcro-app map?) :ret any?))
(s/def ::instrument (s/fspec :args (s/cat :args (s/keys :req-un [::props ::children ::class ::factory])) :ret any?))
(s/def ::instrument-wrapper (s/fspec :args (s/cat :existing-instrument ::instrument) :ret ::instrument))
(s/def ::tool-registry (s/keys :req [::tool-id] :opt [::tx-listen ::network-wrapper ::app-started ::instrument-wrapper]))

(defn register-tool
  "Register a debug tool. When an app starts, the debug tool can have several hooks that are notified:

  ::tool-id some identifier to place the tool into the tool map
  ::tx-listen is a (fn [tx info] ...) that will be called on every `transact!` of the app. Return value is ignored.
  ::network-wrapper is (fn [network-map] network-map') that will be given the networking config BEFORE it is initialized. You can wrap
  them, but you MUST return a compatible map out or you'll disable networking.
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
      (fn [[start net listen instrument] {:keys [::tool-id ::tx-listen ::network-wrapper ::app-started ::instrument-wrapper]}]
        (let [start      (if app-started (fn [app] (app-started app) (start app)) start)
              net        (if network-wrapper (network-wrapper net) net)
              listen     (if tx-listen (fn [env info] (tx-listen env info)) listen)
              instrument (if instrument-wrapper (instrument-wrapper instrument) instrument)]
          [start net listen instrument]))
      [started net listen original-instrument]
      (vals @fulcro-tools))))

(defn iinitial-app-state?
  "Returns true if the class has the static InitialAppState protocol."
  #?(:cljs {:tag boolean})
  [x]
  #?(:clj  (if (fn? x)
             (some? (-> x meta :initial-state))
             (let [class (cond-> x (prim/component? x) class)]
               (extends? InitialAppState class)))
     :cljs (implements? InitialAppState x)))

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
        [started-callback networking tx-listen instrument] (add-tools started-callback networking (:tx-listen reconciler-options) (:instrument reconciler-options))]
    (map->Application {:initial-state      initial-state
                       :read-local         read-local
                       :mutation-merge     mutation-merge
                       :started-callback   started-callback
                       :reconciler-options (merge (cond-> {}
                                                    tx-listen (assoc :tx-listen tx-listen)
                                                    instrument (assoc :instrument instrument)
                                                    migrate (assoc :migrate migrate)
                                                    shared (assoc :shared shared))
                                             reconciler-options)
                       :networking         networking})))

(defprotocol InitialAppState
  (initial-state [clz params] "Get the initial state to be used for this component in app state. You are responsible for composing these together."))

(defn get-initial-state
  "Get the initial state of a component. Needed because calling the protocol method from a defui component in clj will not work as expected."
  [class params]
  #?(:clj  (when-let [initial-state (-> class meta :initial-state)]
             (initial-state class params))
     :cljs (when (implements? InitialAppState class)
             (initial-state class params))))

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
            (let [default-initial-state   (and parent-union (iinitial-app-state? parent-union) (get-initial-state parent-union {}))
                  to-many?                (vector? default-initial-state)
                  component-initial-state (and component (iinitial-app-state? component) (get-initial-state component {}))]
              (when-not default-initial-state
                (log/warn "Subelements of union " (.. parent-union -displayName) " have initial state, but the union itself has no initial state. Your app state may suffer."))
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
  (let [initial-state  (get-initial-state root-component nil)
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
  [{:keys [networking read-local started-callback] :as app} initial-state root-component dom-id-or-node reconciler-options]
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
          ui-declared-state       (and uses-initial-app-state? (fulcro.client.core/initial-state root-component nil))
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
      (let [base-state (prim/tree->db root-component (fulcro.client.core/initial-state root-component nil) true)]
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

  - set: A vector (path) to a list in your app state where this new object's ident should be set.
  - append:  A vector (path) to a list in your app state where this new object's ident should be appended. Will not append
  the ident if that ident is already in the list.
  - prepend: A vector (path) to a list in your app state where this new object's ident should be prepended. Will not append
  the ident if that ident is already in the list.
  - replace: A vector (path) to a specific location in app-state where this object's ident should be placed. Can target a to-one or to-many.
   If the target is a vector element then that element must already exist in the vector."
  [state ident & named-parameters]
  {:pre [(map? state)]}
  (let [actions (partition 2 named-parameters)]
    (reduce (fn [state [command data-path]]
              (let [already-has-ident-at-path? (fn [data-path] (some #(= % ident) (get-in state data-path)))]
                (case command
                  :set (assoc-in state data-path ident)
                  :prepend (if (already-has-ident-at-path? data-path)
                             state
                             (do
                               (assert (vector? (get-in state data-path)) (str "Path " data-path " for prepend must target an app-state vector."))
                               (update-in state data-path #(into [ident] %))))
                  :append (if (already-has-ident-at-path? data-path)
                            state
                            (do
                              (assert (vector? (get-in state data-path)) (str "Path " data-path " for append must target an app-state vector."))
                              (update-in state data-path conj ident)))
                  :replace (let [path-to-vector (butlast data-path)
                                 to-many?       (and (seq path-to-vector) (vector? (get-in state path-to-vector)))
                                 index          (last data-path)
                                 vector         (get-in state path-to-vector)]
                             (assert (vector? data-path) (str "Replacement path must be a vector. You passed: " data-path))
                             (when to-many?
                               (do
                                 (assert (vector? vector) "Path for replacement must be a vector")
                                 (assert (number? index) "Path for replacement must end in a vector index")
                                 (assert (contains? vector index) (str "Target vector for replacement does not have an item at index " index))))
                             (assoc-in state data-path ident))
                  (throw (ex-info "Unknown post-op to merge-state!: " {:command command :arg data-path})))))
      state actions)))

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

#?(:clj
   (defn- is-link? [query-element] (and (vector? query-element)
                                     (keyword? (first query-element))
                                     (= '_ (second query-element)))))
#?(:clj
   (defn- legal-keys [query]
     (set (keep #(cond
                   (keyword? %) %
                   (is-link? %) (first %)
                   (and (map? %) (keyword? (ffirst %))) (ffirst %)
                   (and (map? %) (is-link? (ffirst %))) (first (ffirst %))
                   :else nil) query))))


#?(:clj
   (defn- children-by-prop [query]
     (into {}
       (keep #(if (and (map? %) (or (is-link? (ffirst %)) (keyword? (ffirst %))))
                (let [k   (if (vector? (ffirst %))
                            (first (ffirst %))
                            (ffirst %))
                      cls (-> % first second second)]
                  [k cls])
                nil) query))))

(defn- replace-and-validate-fn
  "Replace the first sym in a list (the function name) with the given symbol."
  ([sym arity fn-form] (replace-and-validate-fn sym arity fn-form sym))
  ([sym arity fn-form user-known-sym]
   (when-not (= arity (count (second fn-form)))
     (throw (ex-info (str "Invalid arity for " user-known-sym) {})))
   (conj (rest fn-form) sym)))

#?(:clj
   (defn- build-query-forms
     "Validate that the property destructuring and query make sense with each other."
     [thissym propargs {:keys [template method]}]
     (cond
       template
       (do
         (assert (or (symbol? propargs) (map? propargs)) "Property args must be a symbol or destructuring expression.")
         (let [to-keyword        (fn [s] (cond
                                           (nil? s) nil
                                           (keyword? s) s
                                           :otherwise (let [nspc (namespace s)
                                                            nm   (name s)]
                                                        (keyword nspc nm))))
               destructured-keys (when (map? propargs) (->> (:keys propargs) (map to-keyword) set))
               queried-keywords  (legal-keys template)
               to-sym            (fn [k] (symbol (namespace k) (name k)))
               illegal-syms      (mapv to-sym (set/difference destructured-keys queried-keywords))]
           (when (seq illegal-syms)
             (throw (ex-info "Syntax error in defsc. One or more destructured parameters do not appear in your query!" {:offending-symbols illegal-syms})))
           `(~'static fulcro.client.primitives/IQuery (~'query [~thissym] ~template))))
       method
       `(~'static om.next/IQuery ~(replace-and-validate-fn 'query 1 method)))))

#?(:clj
   (defn- build-ident
     "Builds the ident form. If ident is a vector, then it generates the function and validates that the ID is
     in the query. Otherwise, if ident is of the form (ident [this props] ...) it simply generates the correct
     entry in defui without error checking."
     [{:keys [:method :template]} is-legal-key?]
     (cond
       method `(~'static om.next/Ident ~(replace-and-validate-fn 'ident 2 method))
       template (let [table   (first template)
                      id-prop (or (second template) :db/id)]
                  (cond
                    (nil? table) (throw (ex-info "TABLE part of ident template was nil" {}))
                    (not (is-legal-key? id-prop)) (throw (ex-info "ID property of :ident does not appear in your :query" {:id-property id-prop}))
                    :otherwise `(~'static fulcro.client.primitives/Ident (~'ident [~'this ~'props] [~table (~id-prop ~'props)])))))))

#?(:clj
   (defn- build-render [thissym propsym compsym childrensym body]
     `(~'Object
        (~'render [~thissym]
          (let [~propsym (fulcro.client.primitives/props ~thissym)
                ~compsym (fulcro.client.primitives/get-computed ~thissym)
                ~childrensym (fulcro.client.primitives/children ~thissym)]
            ~@body)))))

(defn make-state-map
  "Build a component's initial state using the defsc initial-state-data from
  options, the children from options, and the params from the invocation of get-initial-state."
  [initial-state children-by-query-key params]
  (let [join-keys (set (keys children-by-query-key))
        init-keys (set (keys initial-state))
        is-child? (fn [k] (contains? join-keys k))
        value-of  (fn value-of* [[k v]]
                    (let [param-name    (fn [v] (and (keyword? v) (= "param" (namespace v)) (keyword (name v))))
                          substitute    (fn [ele] (if-let [k (param-name ele)]
                                                    (get params k)
                                                    ele))
                          param-key     (param-name v)
                          param-exists? (contains? params param-key)
                          param-value   (get params param-key)
                          child-class   (get children-by-query-key k)]
                      (cond
                        (and param-key (not param-exists?)) nil
                        (and (map? v) (is-child? k)) [k (get-initial-state child-class (into {} (keep value-of* v)))]
                        (map? v) [k (into {} (keep value-of* v))]
                        (and (vector? v) (is-child? k)) [k (mapv (fn [m] (get-initial-state child-class (into {} (keep value-of* m)))) v)]
                        (and (vector? param-value) (is-child? k)) [k (mapv (fn [params] (get-initial-state child-class params)) param-value)]
                        (vector? v) [k (mapv (fn [ele] (substitute ele)) v)]
                        (and param-key (is-child? k) param-exists?) [k (get-initial-state child-class param-value)]
                        param-key [k param-value]
                        :else [k v])))]
    (into {} (keep value-of initial-state))))

#?(:clj
   (defn build-and-validate-initial-state-map [sym initial-state legal-keys children-by-query-key is-a-form?]
     (let [join-keys     (set (keys children-by-query-key))
           init-keys     (set (keys initial-state))
           illegal-keys  (set/difference init-keys legal-keys)
           is-child?     (fn [k] (contains? join-keys k))
           param-expr    (fn [v]
                           (if-let [kw (and (keyword? v) (= "param" (namespace v))
                                         (keyword (name v)))]
                             `(~kw ~'params)
                             v))
           parameterized (fn [init-map] (into {} (map (fn [[k v]] (if-let [expr (param-expr v)] [k expr] [k v])) init-map)))
           child-state   (fn [k]
                           (let [state-params    (get initial-state k)
                                 to-one?         (map? state-params)
                                 to-many?        (and (vector? state-params) (every? map? state-params))
                                 from-parameter? (and (keyword? state-params) (= "param" (namespace state-params)))
                                 child-class     (get children-by-query-key k)]
                             (cond
                               (not (or from-parameter? to-many? to-one?)) (throw (ex-info "Initial value for a child must be a map or vector of maps!" {:offending-child k}))
                               to-one? `(fulcro.client.core/get-initial-state ~child-class ~(parameterized state-params))
                               to-many? (mapv (fn [params]
                                                `(fulcro.client.core/get-initial-state ~child-class ~(parameterized params)))
                                          state-params)
                               from-parameter? `(fulcro.client.core/get-initial-state ~child-class ~(param-expr state-params))
                               :otherwise nil)))
           kv-pairs      (map (fn [k]
                                [k (if (is-child? k)
                                     (child-state k)
                                     (param-expr (get initial-state k)))]) init-keys)
           state-map     (into {} kv-pairs)]
       (when (seq illegal-keys)
         (throw (ex-info "Initial state includes keys that are not in your query." {:offending-keys illegal-keys})))
       (if is-a-form?
         `(~'static fulcro.client.core/InitialAppState
            (~'initial-state [~'c ~'params] (fulcro.ui.forms/build-form ~sym (fulcro.client.core/make-state-map ~initial-state ~children-by-query-key ~'params))))
         `(~'static fulcro.client.core/InitialAppState
            (~'initial-state [~'c ~'params] (fulcro.client.core/make-state-map ~initial-state ~children-by-query-key ~'params)))))))

#?(:clj
   (defn build-raw-initial-state
     "Given an initial state form that is a list (function-form), simple copy it into the form needed by defui."
     [method]
     `(~'static fulcro.client.core/InitialAppState
        ~(replace-and-validate-fn 'initial-state 2 method))))

#?(:clj
   (defn- build-initial-state [sym {:keys [template method]} legal-keys query-template-or-method is-a-form?]
     (when (and template (contains? query-template-or-method :method))
       (throw (ex-info "When query is a method, initial state MUST be as well." {:component sym})))
     (cond
       method (build-raw-initial-state method)
       template (let [query    (:template query-template-or-method)
                      children (or (children-by-prop query) {})]
                  (build-and-validate-initial-state-map sym template legal-keys children is-a-form?)))))

#?(:clj (s/def ::ident (s/or :template (s/and vector? #(= 2 (count %))) :method list?)))
#?(:clj (s/def ::query (s/or :template vector? :method list?)))
#?(:clj (s/def ::initial-state (s/or :template map? :method list?)))
#?(:clj (s/def ::css (s/or :template vector? :method list?)))
#?(:clj (s/def ::css-include (s/or :template (s/and vector? #(every? symbol? %)) :method list?)))

#?(:clj (s/def ::options (s/keys :opt-un [::query ::ident ::initial-state ::css ::css-include])))

#?(:clj (s/def ::defsc-args (s/cat
                              :sym symbol?
                              :doc (s/? string?)
                              :arglist (s/and vector? #(= 4 (count %)))
                              :options ::options
                              :body (s/+ (constantly true)))))
#?(:clj (s/def ::static #{'static}))
#?(:clj (s/def ::protocol-method list?))

#?(:clj (s/def ::protocols (s/* (s/cat :static (s/? ::static) :protocol symbol? :methods (s/+ ::protocol-method)))))

#?(:clj
   (defn- build-form [form-fields]
     (when form-fields
       `(~'static ~'fulcro.ui.forms/IForm
          (~'form-spec [~'this] ~form-fields)))))

#?(:clj
   (defn build-css [{css-method :method css-template :template} {include-method :method include-template :template}]
     (when (or css-method css-template include-method include-template)
       (let [local-form   (cond
                            css-template (if-not (vector? css-template)
                                           (throw (ex-info "css MUST be a vector of garden-syntax rules" {}))
                                           `(~'local-rules [~'_] ~css-template))
                            css-method (replace-and-validate-fn 'local-rules 1 css-method 'css)
                            :else '(local-rules [_] []))
             include-form (cond
                            include-template (if-not (and (vector? include-template) (every? symbol? include-template))
                                               (throw (ex-info "css-include must be a vector of component symbols" {}))
                                               `(~'include-children [~'_] ~include-template))
                            include-method (replace-and-validate-fn 'include-children 1 include-method 'css-include)
                            :else '(include-children [_] []))]
         `(~'static fulcro-css.css/CSS
            ~local-form
            ~include-form)))))

#?(:clj
   (defn defsc*
     [args]
     (if-not (s/valid? ::defsc-args args)
       (throw (ex-info "Invalid arguments"
                {:reason (str (-> (s/explain-data ::defsc-args args)
                                ::s/problems
                                first
                                :path) " is invalid.")})))
     (let [{:keys [sym doc arglist options body]} (s/conform ::defsc-args args)
           [thissym propsym computedsym childrensym] arglist
           {:keys [ident query initial-state protocols form-fields css css-include]} options
           ident-template-or-method         (into {} [ident]) ;clojure spec returns a map entry as a vector
           initial-state-template-or-method (into {} [initial-state])
           query-template-or-method         (into {} [query])
           css-template-or-method           (into {} [css])
           css-include-template-or-method  (into {} [css-include])
           validate-query?                  (:template query-template-or-method)
           legal-key-cheker                 (if validate-query?
                                              (or (legal-keys (:template query-template-or-method)) #{})
                                              (complement #{}))
           parsed-protocols                 (when protocols (group-by :protocol (s/conform ::protocols protocols)))
           object-methods                   (when (contains? parsed-protocols 'Object) (get-in parsed-protocols ['Object 0 :methods]))
           addl-protocols                   (some->> (dissoc parsed-protocols 'Object)
                                              vals
                                              (map (fn [[v]]
                                                     (if (contains? v :static)
                                                       (concat ['static (:protocol v)] (:methods v))
                                                       (concat [(:protocol v)] (:methods v)))))
                                              (mapcat identity))
           ident-forms                      (build-ident ident-template-or-method legal-key-cheker)
           state-forms                      (build-initial-state sym initial-state-template-or-method legal-key-cheker query-template-or-method (boolean (seq form-fields)))
           query-forms                      (build-query-forms thissym propsym query-template-or-method)
           form-forms                       (build-form form-fields)
           css-forms                        (build-css css-template-or-method css-include-template-or-method)
           render-forms                     (build-render thissym propsym computedsym childrensym body)]
       (assert (or (nil? protocols) (s/valid? ::protocols protocols)) "Protocols must be valid protocol declarations")
       `(fulcro.client.primitives/defui ~(with-meta sym {:once true})
          ~@addl-protocols
          ~@css-forms
          ~@state-forms
          ~@ident-forms
          ~@query-forms
          ~@form-forms
          ~@render-forms
          ~@object-methods))))

#?(:clj
   (defmacro ^{:doc      "Define a stateful component. This macro emits a React UI component with a query,
   optional ident (if :ident is specified in options), optional initial state, optional css,
   optional forms, and a render method. It can also emit additional protocols  that you specify.

   The argument list can include destructuring to pull items from props or computed. `children` will be a
   sequence (possibly nil) of child react components that were passed to the component's factory.

   Following the argument list is an options map:

   ```
   (defsc Component [this props computed children]
      { :query [:db/id :component/x {:component/child (prim/get-query Child)} {:component/other (prim/get-query Other)}]
        :form-fields [(f/id-field :db/id) ...] ; See IForm in forms for description of form fields
        :css [] ; fulcro-css local-rules
        :css-include [] ; fulcro-css include-children
        :ident [:COMPONENT/by-id :db/id]
        :protocols [Object
                    (shouldComponentUpdate [this] true)]
        ; Use :param/x to indicate a value that should come from the caller of get-initial-state on this component
        :initial-state {:db/id 4 :component/Child {} :component/other [{}]} }
      body)
   ```

   The options map supplies the necessary information to build the component's ident, query, initial state,
   render method, form support, component-local CSS rules, and any additional arbitrary protocols.
   It is also used to error check your code. For example, you may destructure props:

   ```
   (defsc Component [this {:keys [db/id component/x] :as props} computed children]
      { :query [:db/id :component/x]
      ...)
   ```

   If the destructuring of props tries to pull data that the options map does not have in `:query`, then an error will
   result at compile time, alerting you to your error. Many other things are also checked (that you query for the ID
   field, that initial state only initializes things you query, etc.). This can prevent a lot of common errors when
   building your UI.

   ## Initial State

   IMPORTANT: Initial state using the template (a map) treats the top-level map as the actual data. Anything
   nested in this map as keys *that match a join in the query* are automatically transformed into calls
   to `(fc/get-initial-state)`. This is so that error-checking can be performed. Any parameters you expect to
   receive in your initial state will be mapped via keywords in the `params` namespace:

   ```
   (defsc Component [this {:keys [db/id component/x] :as props} computed children]
      { :initial-state {:db/id 2 :component/x {:db/id :params/child-id}}
       :query [:db/id {:component/x (get-query X)}]
      ...)
   ```

   means:

   ```
   static fc/InitialAppState
   (initial-state [t {:keys [child-id]}] {:db/id 2 (fc/get-initial-state X {:db/id child-id})})
   ```

   If the initial state includes keys that do not appear in your query, then it will issue an error. You may
   avoid this by writing it as a method instead.

   ## Error Checking

   The above for uses templates to succinctly express the methods. When you use the template style you're more
   restricted in what you can do, but it is also less error prone because the defsc macro can check your component
   for many errors (such as a typo in desctructuring).

   You may write methods instead of data templates, which loosens the checking (depending on what you still leave
   as a template). For example, if you make `:ident` a method, the macro is still able to verify destructuring against
   `:query`.

   ## Using Methods Instead

   You may instead use methods on
   `:query`, `ident`, `initial-state`. Using the method forms turns off much of the error checking.

   ```
   (defsc Component [this props computed children]
     {:query (fn [this] [:x])
      :initial-state (fn [t p] {:x 1})
      :ident (fn [this props] [:table (:db/id props)])
      ...}
     (dom/div ...))
   ```

   See section M05-More-Concise-UI of the Developer's Guide for more details.

   NOTE: `defsc` automatically declares your component with `:once` metadata for correct operation with hot code reload.
   "
               :arglists '([this dbprops computedprops children])}
   defsc
     [& args]
     (let [location (str *ns* ":" (:line (meta &form)))]
       (try
         (defsc* args)
         (catch Exception e
           (throw (ex-info (str "Syntax Error at " location) {:cause e})))))))


