(ns untangled.client.core
  (:require
    [om.next :as om]
    [untangled.client.impl.application :as app]
    untangled.client.impl.built-in-mutations                ; DO NOT REMOVE. Ensures built-in mutations load on start
    [untangled.client.impl.network :as net]
    [untangled.client.logging :as log]
    [untangled.dom :as udom]
    [om.next.protocols :as omp])
  (:import goog.Uri))

(declare map->Application)

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

(defprotocol Constructor
  (initial-state [clz params] "Get the initial state when params are passed. params will be nil when called internally, "))

(defprotocol UntangledApplication
  (mount [this root-component target-dom-id] "Start/replace the webapp on the given DOM ID or DOM Node.")
  (reset-state! [this new-state] "Replace the entire app state with the given (pre-normalized) state.")
  (refresh [this] "Refresh the UI (force re-render). NOTE: You MUST support :key on your root DOM element with the :ui/react-key value from app state for this to work.")
  (history [this] "Return a serialized version of the current history of the application, suitable for network transfer"))

(defrecord Application [initial-state started-callback networking queue response-channel reconciler parser mounted? reconciler-options]
  UntangledApplication
  (mount [this root-component dom-id-or-node]

    (let [state (or (and (implements? Constructor root-component) (untangled.client.core/initial-state root-component nil)) initial-state)]
      (if mounted?
        (do (refresh this) this)
        (do
          (when (and (or (= Atom (type initial-state)) (seq initial-state)) (implements? Constructor root-component))
            (log/warn "You supplied an initial state AND a root component with a constructor. Using Constructor!"))
          (app/initialize this state root-component dom-id-or-node reconciler-options)))))

  (reset-state! [this new-state] (reset! (om/app-state reconciler) new-state))

  (history [this]
    (let [history-steps (-> reconciler :config :history .-arr)
          history-map (-> reconciler :config :history .-index deref)]
      {:steps   history-steps
       :history (into {} (map (fn [[k v]]
                                [k (assoc v :untangled/meta (meta v))]) history-map))}))

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

(defn merge-state!
  "Merge a (sub)tree of application state into the application.

  - app-or-reconciler: The Untangled application or Om reconciler
  - component: The class of the component that corresponsds to the data. Must have an ident.
  - object-data: A map (tree) of data to merge. Will be normalized for you.
  - append-to: Named parameter. A vector (path) to a list in your app state where this new object's ident should be appended.
  - prepend-to: Named parameter. A vector (path) to a list in your app state where this new object's ident should be prepended.
  - replace: Named parameter. A vector (path) to the specific element of an app-state vector where this objects ident should be placed. MUST ALREADY EXIST.
  "
  [app-or-reconciler component object-data & {:keys [append-to replace prepend-to]}]
  (assert (implements? om/Ident component) "Component must implement Ident")
  (let [ident (om/ident component object-data)
        empty-object (if (implements? Constructor component)
                       (initial-state component nil)
                       {})
        merge-query [{ident (om/get-query component)}]
        merge-data {ident (merge empty-object object-data)}
        reconciler (if (= untangled.client.core/Application (type app-or-reconciler))
                     (:reconciler app-or-reconciler)
                     app-or-reconciler)
        state (om/app-state reconciler)]
    (om/merge! reconciler merge-data merge-query)
    (cond
      prepend-to (do
                   (assert (vector? (get-in @state prepend-to)) (str "Path " prepend-to " for prepend-to must target an app-state vector."))
                   (swap! state update-in prepend-to #(into [ident] %))
                   (omp/queue! reconciler prepend-to))
      append-to (do
                  (assert (vector? (get-in @state append-to)) (str "Path " append-to " for append-to must target an app-state vector."))
                  (swap! state update-in append-to conj ident)
                  (omp/queue! reconciler append-to))
      replace (let [path-to-vector (butlast replace)
                    to-many? (and (seq path-to-vector) (vector? (get-in @state path-to-vector)))
                    index (last replace)
                    vector (get-in @state path-to-vector)]
                (assert (vector? replace) (str "Replacement path must be a vector. You passed: " replace))
                (when to-many?
                  (do
                    (assert (vector? vector) "Path for replacement must be a vector")
                    (assert (number? index) "Path for replacement must end in a vector index")
                    (assert (contains? vector index) (str "Target vector for replacement does not have an item at index " index))))
                (swap! state assoc-in replace ident)
                (omp/queue! reconciler replace)))
    @state))
