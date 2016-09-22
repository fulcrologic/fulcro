(ns untangled.client.core
  (:require
    [om.next :as om]
    [om.next.cache :as omc]
    [untangled.client.impl.application :as app]
    [goog.dom :as gdom]
    untangled.client.impl.built-in-mutations                ; DO NOT REMOVE. Ensures built-in mutations load on start
    [untangled.client.impl.network :as net]
    [untangled.client.logging :as log]
    [untangled.dom :as udom]
    [cljs.core.async :as async]
    [om.next.protocols :as omp]
    [untangled.client.impl.util :as util]
    [untangled.client.impl.om-plumbing :as plumbing]
    [clojure.set :as set])
  (:import goog.Uri))

(declare map->Application merge-alternate-union-elements! merge-state!)

(defn new-untangled-client
  "Entrypoint for creating a new untangled client. Instantiates an Application with default values, unless
  overridden by the parameters. If you do not supply a networking object, one will be provided that connects to the
  same server the application was served from, at `/api`.

  If you supply a `:request-transform` it must be a function:

  ```
 (fn [edn headers] [edn' headers'])
  ```

  it can replace the outgoing EDN or headers (returning both as a vector). NOTE: Both of these are clojurescript types.
  The edn will be encoded with transit, and the headers will be converted to a js map.

  `:initial-state` is your applications initial state. If it is an atom, it *must* be normalized. Untangled databases
  always have normalization turned on (for server data merging). If it is not an atom, it will be auto-normalized.

  `:started-callback` is an optional function that will receive the intiailized untangled application after it is
  mounted in the DOM, and is useful for triggering initial loads, routing mutations, etc. The Om reconciler is available
  under the `:reconciler` key (and you can access the app state, root node, etc from there.)

  `:network-error-callback` is a function of two arguments, the app state atom and the error, which will be invoked for
  every network error (status code >= 400, or no network found), should you choose to use the built-in networking record.

  `:migrate` is optional. It is a (fn [state tid->rid] ... state') that should return a new state where all tempids
  (the keys of `tid->rid`) are rewritten to real ids (the values of tid->rid). This defaults to a full recursive
  algorithm against all data in the app-state, which is correct but possibly slow).  Note that tempids will have an Om tempid data type.
  See Om reconciler documentation for further information.

  There is currently no way to circumvent the encoding of the body into transit. If you want to talk to other endpoints
  via alternate protocols you must currently implement that outside of the framework (e.g. global functions/state).
  "
  [& {:keys [initial-state started-callback networking request-transform network-error-callback migrate]
      :or   {initial-state {} started-callback (constantly nil) network-error-callback (constantly nil) migrate nil}}]
  (map->Application {:initial-state      initial-state
                     :started-callback   started-callback
                     :reconciler-options {:migrate migrate}
                     :networking         (or networking (net/make-untangled-network "/api"
                                                                                    :request-transform request-transform
                                                                                    :global-error-callback network-error-callback))}))

(defprotocol InitialAppState
  (initial-state [clz params] "Get the initial state to be used for this component in app state. You are responsible for composing these together."))

(defprotocol UntangledApplication
  (mount [this root-component target-dom-id] "Start/replace the webapp on the given DOM ID or DOM Node.")
  (reset-state! [this new-state] "Replace the entire app state with the given (pre-normalized) state.")
  (reset-app! [this root-component callback] "Replace the entire app state with the initial app state defined on the root component (includes auto-merging of unions). callback can be nil, a function, or :original (to call original started-callback).")
  (clear-pending-remote-requests! [this] "Remove all pending network requests. Useful on failures to eliminate cascading failures.")
  (refresh [this] "Refresh the UI (force re-render). NOTE: You MUST support :key on your root DOM element with the :ui/react-key value from app state for this to work.")
  (history [this] "Return a serialized version of the current history of the application, suitable for network transfer")
  (reset-history! [this] "Returns the application with history reset to its initial, empty state. Resets application history to its initial, empty state. Suitable for resetting the app for situations such as user log out."))

(defn- merge-alternate-union-elements! [app root-component]
  (letfn [(walk-ast
            ([ast visitor]
             (walk-ast ast visitor nil))
            ([ast visitor last-join-component]
             (visitor ast last-join-component)
             (when (:children ast)
               (let [join-component (if (= :join (:type ast))
                                      (:component ast)
                                      last-join-component)]
                 (doseq [c (:children ast)]
                   (walk-ast c visitor join-component))))))
          (merge-union [{:keys [type component query children] :as n} last-join-component]
            (when (= :union type)
              (let [default-branch (and last-join-component (implements? InitialAppState last-join-component) (initial-state last-join-component nil))
                    to-many? (vector? default-branch)]
                (doseq [element (->> query vals (map (comp :component meta)))]
                  (if-let [state (and (implements? InitialAppState element) (initial-state element nil))]
                    (cond
                      (and state (not default-branch)) (log/warn "Subelements of union with query " query " have initial state, but the union component itself has no initial app state. Your app state may not have been initialized correctly.")
                      (not to-many?) (merge-state! app last-join-component state)))))))]
    (walk-ast
      (om/query->ast (om/get-query root-component))
      merge-union)))

(defn- initialize
  "Initialize the untangled Application. Creates network queue, sets up i18n, creates reconciler, mounts it, and returns
  the initialized app"
  [{:keys [networking started-callback] :as app} initial-state root-component dom-id-or-node reconciler-options]
  (let [queue (async/chan 1024)
        rc (async/chan)
        parser (om/parser {:read plumbing/read-local :mutate plumbing/write-entry-point})
        initial-app (assoc app :queue queue :response-channel rc :parser parser :mounted? true
                               :networking networking)
        rec (app/generate-reconciler initial-app initial-state parser reconciler-options)
        completed-app (assoc initial-app :reconciler rec)
        node (if (string? dom-id-or-node)
               (gdom/getElement dom-id-or-node)
               dom-id-or-node)]

    (net/start networking completed-app)
    (app/initialize-internationalization rec)
    (app/initialize-global-error-callback completed-app)
    (app/start-network-sequential-processing completed-app)
    (om/add-root! rec root-component node)
    (merge-alternate-union-elements! completed-app root-component)
    (when started-callback
      (started-callback completed-app))
    completed-app))

(defn clear-queue
  "Needed for mocking in tests. Do not use directly"
  [queue]
  (loop [element (async/poll! queue)]
    (if element
      (recur (async/poll! queue)))))

(defn reset-history-impl
  "Needed for mocking in tests"
  [app]
  (assoc app :reconciler (update-in (:reconciler app) [:config :history] #(omc/cache (.-size %)))))

(defrecord Application [initial-state started-callback networking queue response-channel reconciler parser mounted? reconciler-options]
  UntangledApplication
  (mount [this root-component dom-id-or-node]

    (let [state (or (and (implements? InitialAppState root-component) (untangled.client.core/initial-state root-component nil)) initial-state)]
      (if mounted?
        (do (refresh this) this)
        (do
          (when (and (or (= Atom (type initial-state)) (seq initial-state)) (implements? InitialAppState root-component))
            (log/warn "You supplied an initial state AND a root component with a constructor. Using InitialAppState!"))
          (initialize this state root-component dom-id-or-node reconciler-options)))))

  (reset-state! [this new-state] (reset! (om/app-state reconciler) new-state))

  (reset-app! [this root-component callback]
    (if (not (implements? InitialAppState root-component))
      (log/error "The specified root component does not implement InitialAppState!")
      (let [base-state (om/tree->db root-component (untangled.client.core/initial-state root-component nil) true)]
        (clear-pending-remote-requests! this)
        (reset! (om/app-state reconciler) base-state)
        (reset-history! this)
        (merge-alternate-union-elements! this root-component)
        (log/info "updated app state to original " (om/app-state reconciler))
        (cond
          (= callback :original) (started-callback this)
          callback (callback this))
        (refresh this))))

  (clear-pending-remote-requests! [this] (clear-queue queue))

  (history [this]
    (let [history-steps (-> reconciler :config :history .-arr)
          history-map (-> reconciler :config :history .-index deref)]
      {:steps   history-steps
       :history (into {} (map (fn [[k v]]
                                [k (assoc v :untangled/meta (meta v))]) history-map))}))
  (reset-history! [this]
    (reset-history-impl this))

  (refresh [this]
    (log/info "RERENDER: NOTE: If your UI doesn't change, make sure you query for :ui/react-key on your Root and embed that as :key in your top-level DOM element")
    (udom/force-render reconciler)))

(defn new-untangled-test-client
  "A test client that has no networking. Useful for UI testing with a real Untangled app container."
  [& {:keys [initial-state started-callback]
      :or   {initial-state {} started-callback nil}}]
  (map->Application {:initial-state    initial-state
                     :started-callback started-callback
                     :networking       (net/mock-network)}))

(defn get-url
  "Get the current window location from the browser"
  [] (-> js/window .-location .-href))

(defn uri-params
  "Get the current URI parameters from the browser url or one you supply"
  ([] (uri-params (get-url)))
  ([url]
   (let [query-data (.getQueryData (goog.Uri. url))]
     (into {}
           (for [k (.getKeys query-data)]
             [k (.get query-data k)])))))

(defn get-url-param
  "Get the value of the named parameter from the browser URL (or an explicit one)"
  ([param-name] (get-url-param (get-url) param-name))
  ([url param-name]
   (get (uri-params url) param-name)))

(defn get-class-ident
  "Get the ident using a component class and data. Om's simulated statics are elided by
  advanced compilation. This function compensates."
  [comp data]
  (if (implements? om/Ident comp)
    (om/ident comp data)
    ;; in advanced, statics will get killed
    (when (goog/isFunction comp)
      (let [resurrection (js/Object.create (. comp -prototype))]
        (when (implements? om/Ident resurrection)
          (om/ident resurrection data))))))

(defn- component-merge-query
  "Calculates the query that can be used to pull (or merge) a component with an ident
  to/from a normalized app database. Requires a tree of data that represents the instance of
  the component in question (e.g. ident will work on it)"
  [component object-data]
  (let [ident (get-class-ident component object-data)
        object-query (om/get-query component)]
    [{ident object-query}]))

(defn- preprocess-merge
  "Does the steps necessary to honor the data merge technique defined by Untangled with respect
  to data overwrites in the app database."
  [state-atom component object-data]
  (let [ident (get-class-ident component object-data)
        object-query (om/get-query component)
        base-query (component-merge-query component object-data)
        ;; :untangled/merge is way to make unions merge properly when joined by idents
        merge-query [{:untangled/merge base-query}]
        existing-data (get (om/db->tree base-query @state-atom @state-atom) ident {})
        marked-data (plumbing/mark-missing object-data object-query)
        merge-data {:untangled/merge {ident (util/deep-merge existing-data marked-data)}}]
    {:merge-query merge-query
     :merge-data  merge-data}))

(defn integrate-ident!
  "Integrate an ident into any number of places in the app state. This function is safe to use within mutation
  implementations as a general helper function.

  The named parameters can be specified any number of times. They are:

  - append:  A vector (path) to a list in your app state where this new object's ident should be appended. Will not append
  the ident if that ident is already in the list.
  - prepend: A vector (path) to a list in your app state where this new object's ident should be prepended. Will not append
  the ident if that ident is already in the list.
  - replace: A vector (path) to a specific locaation in app-state where this object's ident should be placed. Can target a to-one or to-many.
   If the target is a vector element then that element must already exist in the vector.
  "
  [state ident & named-parameters]
  (let [already-has-ident-at-path? (fn [data-path] (boolean (seq (filter #(= % ident) (get-in @state data-path)))))
        actions (partition 2 named-parameters)]
    (doseq [[command data-path] actions]
      (case command
        :prepend (when-not (already-has-ident-at-path? data-path)
                   (assert (vector? (get-in @state data-path)) (str "Path " data-path " for prepend must target an app-state vector."))
                   (swap! state update-in data-path #(into [ident] %)))
        :append (when-not (already-has-ident-at-path? data-path)
                  (assert (vector? (get-in @state data-path)) (str "Path " data-path " for append must target an app-state vector."))
                  (swap! state update-in data-path conj ident))
        :replace (let [path-to-vector (butlast data-path)
                       to-many? (and (seq path-to-vector) (vector? (get-in @state path-to-vector)))
                       index (last data-path)
                       vector (get-in @state path-to-vector)]
                   (assert (vector? data-path) (str "Replacement path must be a vector. You passed: " data-path))
                   (when to-many?
                     (do
                       (assert (vector? vector) "Path for replacement must be a vector")
                       (assert (number? index) "Path for replacement must end in a vector index")
                       (assert (contains? vector index) (str "Target vector for replacement does not have an item at index " index))))
                   (swap! state assoc-in data-path ident))
        (throw (ex-info "Unknown post-op to merge-state!: " {:command command :arg data-path}))))))

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
  (when-not (implements? om/Ident component) (log/warn "merge-state!: component must implement Ident"))
  (let [ident (get-class-ident component object-data)
        reconciler (if (= untangled.client.core/Application (type app-or-reconciler))
                     (:reconciler app-or-reconciler)
                     app-or-reconciler)
        state (om/app-state reconciler)
        data-path-keys (->> named-parameters (partition 2) (map second) flatten (filter keyword?) set vec)
        {:keys [merge-data merge-query]} (preprocess-merge state component object-data)]
    (om/merge! reconciler merge-data merge-query)
    (swap! state dissoc :untangled/merge)
    (apply integrate-ident! state ident named-parameters)
    (omp/queue! reconciler data-path-keys)
    @state))


