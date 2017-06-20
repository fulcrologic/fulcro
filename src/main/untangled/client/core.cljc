(ns untangled.client.core
  (:require
    [om.next :as om]
    [untangled.client.impl.application :as app]
    #?(:cljs untangled.client.mutations)                    ; DO NOT REMOVE. Ensures built-in mutations load on start
    [untangled.client.network :as net]
    [untangled.client.logging :as log]
    #?(:clj
    [clojure.core.async :as async] :cljs [cljs.core.async :as async])
    [om.next.protocols :as omp]
    [untangled.client.util :as util]
    [untangled.client.impl.om-plumbing :as plumbing]
    [clojure.set :as set]
    #?(:cljs [om.next.cache :as omc])
    #?(:cljs [goog.dom :as gdom]))
  #?(:cljs (:import goog.Uri)))

(declare map->Application merge-alternate-union-elements! merge-state! new-untangled-client new-untangled-test-client)

(defn new-untangled-client
  "Entrypoint for creating a new untangled client. Instantiates an Application with default values, unless
  overridden by the parameters. If you do not supply a networking object, one will be provided that connects to the
  same server the application was served from, at `/api`.

  If you supply a `:request-transform` it must be a function:

  ```
 (fn [edn headers] [edn' headers'])
  ```

  it can replace the outgoing EDN or headers (returning both as a vector). NOTE: Both of these are clojurescript types.
  The edn will be encoded with transit, and the headers will be converted to a js map. IMPORTANT: Only supported
  when using the default built-in single-remote networking.

  `:initial-state` is your applications initial state. If it is an atom, it *must* be normalized. Untangled databases
  always have normalization turned on (for server data merging). If it is not an atom, it will be auto-normalized.

  `:started-callback` is an optional function that will receive the intiailized untangled application after it is
  mounted in the DOM, and is useful for triggering initial loads, routing mutations, etc. The Om reconciler is available
  under the `:reconciler` key (and you can access the app state, root node, etc from there.)

  `:network-error-callback` is a function of two arguments, the app state atom and the error, which will be invoked for
  every network error (status code >= 400, or no network found), should you choose to use the default built-in
  networking.

  `:migrate` is optional. It is a (fn [state tid->rid] ... state') that should return a new state where all tempids
  (the keys of `tid->rid`) are rewritten to real ids (the values of tid->rid). This defaults to a full recursive
  algorithm against all data in the app-state, which is correct but possibly slow).  Note that tempids will have an Om tempid data type.
  See Om reconciler documentation for further information.

  `:transit-handlers` (optional). A map with keys for `:read` and `:write`, which contain maps to be used for the read
  and write side of transit to extend the supported data types. See `make-untangled-network` in network.cljs. Only used
  when you default to the built-in networking.

  `:shared` (optional). A map of arbitrary values to be shared across all components, accessible to them via (om/shared this)

  `:networking` (optional). An instance of UntangledNetwork that will act as the default remote (named :remote). If
  you want to support multiple remotes, then this should be a map whose keys are the keyword names of the remotes
  and whose values are UntangledNetwork instances.

  `:mutation-merge (optional). A function `(fn [state mutation-symbol return-value])` that receives the app state as a
  map (NOT an atom) and should return the new state as a map. This function is run when network results are being merged,
  and is called once for each mutation that had a return value on the server. Returning nil from this function is safe, and will be ignored
  with a console message for debugging. If you need information about the original mutation arguments then you must reflect
  them back from the server in your return value. By default such values are discarded.

  There is currently no way to circumvent the encoding of the body into transit. If you want to talk to other endpoints
  via alternate protocols you must currently implement that outside of the framework (e.g. global functions/state).
  "
  [& {:keys [initial-state mutation-merge started-callback networking reconciler-options
             request-transform network-error-callback migrate transit-handlers shared]
      :or   {initial-state {} started-callback (constantly nil) network-error-callback (constantly nil)
             migrate       nil shared nil}}]
  (map->Application {:initial-state      initial-state
                     :mutation-merge     mutation-merge
                     :started-callback   started-callback
                     :reconciler-options (merge (cond-> {}
                                                  migrate (assoc :migrate migrate)
                                                  shared (assoc :shared shared))
                                           reconciler-options)
                     :networking         (or networking #?(:clj nil :cljs (net/make-untangled-network "/api"
                                                                            :request-transform request-transform
                                                                            :transit-handlers transit-handlers
                                                                            :global-error-callback network-error-callback)))}))

(defprotocol InitialAppState
  (initial-state [clz params] "Get the initial state to be used for this component in app state. You are responsible for composing these together."))

(defn get-initial-state
  "Get the initial state of a component. Needed because calling the protocol method from a defui component in clj will not work as expected."
  [class params]
  #?(:clj  (when-let [initial-state (-> class meta :initial-state)]
             (initial-state class params))
     :cljs (when (implements? InitialAppState class)
             (initial-state class params))))

(defprotocol UntangledApplication
  (mount [this root-component target-dom-id] "Start/replace the webapp on the given DOM ID or DOM Node.")
  (reset-state! [this new-state] "Replace the entire app state with the given (pre-normalized) state.")
  (reset-app! [this root-component callback] "Replace the entire app state with the initial app state defined on the root component (includes auto-merging of unions). callback can be nil, a function, or :original (to call original started-callback).")
  (clear-pending-remote-requests! [this remotes] "Remove all pending network requests on the given remote(s). Useful on failures to eliminate cascading failures. Remote can be a keyword, set, or nil. `nil` means all remotes.")
  (refresh [this] "Refresh the UI (force re-render). NOTE: You MUST support :key on your root DOM element with the :ui/react-key value from app state for this to work.")
  (history [this] "Return a serialized version of the current history of the application, suitable for network transfer")
  (reset-history! [this] "Returns the application with history reset to its initial, empty state. Resets application history to its initial, empty state. Suitable for resetting the app for situations such as user log out."))

;q: {:a (gq A) :b (gq B)
;is: (is A)  <-- default branch
;state:   { kw { id [:page :a]  }}
#?(:cljs
   (defn- merge-alternate-union-elements! [app root-component]
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
               (let [default-initial-state   (and parent-union (implements? InitialAppState parent-union) (initial-state parent-union {}))
                     to-many?                (vector? default-initial-state)
                     component-initial-state (and component (implements? InitialAppState component) (initial-state component {}))]
                 (when-not default-initial-state
                   (log/warn "Subelements of union " (.. parent-union -displayName) " have initial state, but the union itself has no initial state. Your app state may suffer."))
                 (when (and component component-initial-state parent-union (not to-many?) (not= default-initial-state component-initial-state))
                   (merge-state! app parent-union component-initial-state))))]
       (walk-ast
         (om/query->ast (om/get-query root-component))
         merge-union))))

(defn- initialize
  "Initialize the untangled Application. Creates network queue, sets up i18n, creates reconciler, mounts it, and returns
  the initialized app"
  [{:keys [networking started-callback] :as app} initial-state root-component dom-id-or-node reconciler-options]
  (let [network-map #?(:cljs (if (implements? net/UntangledNetwork networking) {:remote networking} networking)
                       :clj {})
        remotes             (keys network-map)
        send-queues         (zipmap remotes (map #(async/chan 1024) remotes))
        response-channels   (zipmap remotes (map #(async/chan) remotes))
        parser              (om/parser {:read plumbing/read-local :mutate plumbing/write-entry-point})
        initial-app         (assoc app :send-queues send-queues :response-channels response-channels
                                       :parser parser :mounted? true :networking network-map)
        rec                 (app/generate-reconciler initial-app initial-state parser reconciler-options)
        completed-app       (assoc initial-app :reconciler rec)
        node #?(:cljs (if (string? dom-id-or-node)
                        (gdom/getElement dom-id-or-node)
                        dom-id-or-node)
                :clj        dom-id-or-node)]
    (doseq [r remotes]
      (net/start (get network-map r) completed-app))
    (app/initialize-internationalization rec)
    (app/initialize-global-error-callbacks completed-app)
    (app/start-network-sequential-processing completed-app)
    (om/add-root! rec root-component node)
    (merge-alternate-union-elements! completed-app root-component)
    (when started-callback
      (started-callback completed-app))
    completed-app))

(defn clear-queue
  "Needed for mocking in tests. Do not use directly. Use UntangledApplication protocol methods instead."
  [queue]
  (loop [element (async/poll! queue)]
    (if element
      (recur (async/poll! queue)))))

(defn reset-history-impl
  "Needed for mocking in tests. Use UntangledApplication protocol methods instead."
  [app]
  #?(:cljs (assoc app :reconciler (update-in (:reconciler app) [:config :history] #(omc/cache (.-size %))))))

(defn refresh* [{:keys [reconciler] :as app}]
  (log/info "RERENDER: NOTE: If your UI doesn't change, make sure you query for :ui/react-key on your Root and embed that as :key in your top-level DOM element")
  (util/force-render reconciler))

(defn mount* [{:keys [mounted? initial-state reconciler-options] :as app} root-component dom-id-or-node]
  (if mounted?
    (do (refresh* app) app)
    (let [uses-initial-app-state? #?(:cljs (implements? InitialAppState root-component)
                                     :clj (satisfies? InitialAppState root-component))
          ui-declared-state               (and uses-initial-app-state? (untangled.client.core/initial-state root-component nil))
          atom-supplied?                  (util/atom? initial-state)
          init-conflict?                  (and (or atom-supplied? (seq initial-state)) #?(:cljs (implements? InitialAppState root-component)
                                                                                          :clj  (satisfies? InitialAppState root-component)))
          state                           (cond
                                            (not uses-initial-app-state?) (if initial-state initial-state {})
                                            atom-supplied? (do
                                                             (reset! initial-state (om/tree->db root-component ui-declared-state true))
                                                             initial-state)
                                            :otherwise ui-declared-state)]
      (when init-conflict?
        (log/warn "You supplied an initial state AND a root component with initial state. Using root's InitialAppState (atom overwritten)!"))
      (initialize app state root-component dom-id-or-node reconciler-options))))

(defrecord Application [initial-state mutation-merge started-callback remotes networking send-queues response-channels reconciler parser mounted? reconciler-options]
  UntangledApplication
  (mount [this root-component dom-id-or-node] (mount* this root-component dom-id-or-node))

  (reset-state! [this new-state] (reset! (om/app-state reconciler) new-state))

  (reset-app! [this root-component callback]
    (if (not #?(:cljs (implements? InitialAppState root-component)
                :clj  (satisfies? InitialAppState root-component)))
      (log/error "The specified root component does not implement InitialAppState!")
      (let [base-state (om/tree->db root-component (untangled.client.core/initial-state root-component nil) true)]
        (clear-pending-remote-requests! this nil)
        (reset! (om/app-state reconciler) base-state)
        (reset-history! this)
        (merge-alternate-union-elements! this root-component)
        (log/info "updated app state to original " (om/app-state reconciler))
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

  (history [this]
    #?(:cljs
       (let [history-steps (-> reconciler :config :history .-arr)
             history-map   (-> reconciler :config :history .-index deref)]
         {:steps   history-steps
          :history (into {} (map (fn [[k v]]
                                   [k (assoc v :untangled/meta (meta v))]) history-map))})))
  (reset-history! [this]
    (reset-history-impl this))

  (refresh [this]
    (log/info "RERENDER: NOTE: If your UI doesn't change, make sure you query for :ui/react-key on your Root and embed that as :key in your top-level DOM element")
    (util/force-render reconciler)))

(defn new-untangled-test-client
  "Create a test client that has no networking. Useful for UI testing with a real Untangled app container."
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

(defn get-class-ident
  "Get the ident using a component class and data. Om's simulated statics are elided by
  advanced compilation. This function compensates."
  [comp data]
  (om/ident comp data))

(defn- component-merge-query
  "Calculates the query that can be used to pull (or merge) a component with an ident
  to/from a normalized app database. Requires a tree of data that represents the instance of
  the component in question (e.g. ident will work on it)"
  [component object-data]
  (let [ident        (om/ident component object-data)
        object-query (om/get-query component)]
    [{ident object-query}]))

(defn- preprocess-merge
  "Does the steps necessary to honor the data merge technique defined by Untangled with respect
  to data overwrites in the app database."
  [state-atom component object-data]
  (let [ident         (get-class-ident component object-data)
        object-query  (om/get-query component)
        object-query  (if (map? object-query) [object-query] object-query)
        base-query    (component-merge-query component object-data)
        ;; :untangled/merge is way to make unions merge properly when joined by idents
        merge-query   [{:untangled/merge base-query}]
        existing-data (get (om/db->tree base-query @state-atom @state-atom) ident {})
        marked-data   (plumbing/mark-missing object-data object-query)
        merge-data    {:untangled/merge {ident (util/deep-merge existing-data marked-data)}}]
    {:merge-query merge-query
     :merge-data  merge-data}))

(defn- is-atom?
  "Returns TRUE when x is an atom."
  [x]
  (instance? #?(:cljs cljs.core.Atom
                :clj  clojure.lang.Atom) x))

(defn integrate-ident
  "Integrate an ident into any number of places in the app state. This function is safe to use within mutation
  implementations as a general helper function.

  The named parameters can be specified any number of times. They are:

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

  This function honors the data merge story for Untangled: attributes that are queried for but do not appear in the
  data will be removed from the application. This function also uses the initial state for the component as a base
  for merge if there was no state for the object already in the database.

  This function will also trigger re-renders of components that directly render object merged, as well as any components
  into which you integrate that data via the named-parameters.

  This function is primarily meant to be used from things like server push and setTimeout/setInterval, where you're outside
  of the normal mutation story. Do not use this function within abstract mutations.

  - app-or-reconciler: The Untangled application or Om reconciler
  - component: The class of the component that corresponsds to the data. Must have an ident.
  - object-data: A map (tree) of data to merge. Will be normalized for you.
  - named-parameter: Post-processing ident integration steps. see integrate-ident!

  Any keywords that appear in ident integration steps will be added to the re-render queue.
  "
  [app-or-reconciler component object-data & named-parameters]
  (when-not #?(:cljs (implements? om/Ident component)
               :clj  (satisfies? om/Ident component)) (log/warn "merge-state!: component must implement Ident"))
  (let [ident          (get-class-ident component object-data)
        reconciler     (if #?(:cljs (implements? UntangledApplication app-or-reconciler)
                              :clj  (satisfies? UntangledApplication app-or-reconciler))
                         (:reconciler app-or-reconciler)
                         app-or-reconciler)
        state          (om/app-state reconciler)
        data-path-keys (->> named-parameters (partition 2) (map second) flatten (filter keyword?) set vec)
        {:keys [merge-data merge-query]} (preprocess-merge state component object-data)]
    (om/merge! reconciler merge-data merge-query)
    (swap! state dissoc :untangled/merge)
    (apply integrate-ident! state ident named-parameters)
    (omp/queue! reconciler data-path-keys)
    @state))


