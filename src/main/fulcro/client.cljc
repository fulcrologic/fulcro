(ns fulcro.client
  #?(:cljs (:require-macros
             [cljs.core.async.macros :refer [go]]))
  (:require
    [fulcro.client.primitives :as prim]
    [fulcro.client.impl.application :as app]
    #?(:cljs fulcro.client.mutations)                       ; DO NOT REMOVE. Ensures built-in mutations load on start
    [fulcro.client.network :as net]
    [fulcro.logging :as log]
    [clojure.core.async :as async]
    [fulcro.client.impl.protocols :as proto]
    [fulcro.util :as util]
    [fulcro.client.util :as cutil]
    [clojure.set :as set]
    #?(:cljs [goog.dom :as gdom])
    [clojure.spec.alpha :as s]
    [fulcro.history :as hist]
    [fulcro.client.impl.protocols :as p]
    [fulcro.client.mutations :as m])
  #?(:cljs (:import goog.Uri)))

(declare map->Application merge-alternate-union-elements! merge-state! new-fulcro-client new-fulcro-test-client)

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
  #?(:cljs (if (or
                 (implements? net/FulcroRemoteI networking)
                 (implements? net/FulcroNetwork networking)) {:remote networking} networking)
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

; easier to access, so the user don't have to require a impl namespace to reference
(def mutate app/write-entry-point)


(defn make-fulcro-client
  "Entry point for creating a new fulcro client. Instantiates an Application with default values, unless
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

  `:network-error-callback` is a function of three arguments, the app state atom, status code, and the error, which will be invoked for
  every network error (status code >= 400, or no network found). Only works if you choose to use the default built-in
  networking (ignored if you also specify :networking).

  `:migrate` is optional. It is a (fn [state tid->rid] ... state') that should return a new state where all tempids
  (the keys of `tid->rid`) are rewritten to real ids (the values of tid->rid). This defaults to a full recursive
  algorithm against all data in the app-state, which is correct but possibly slow).  Note that tempids will have an tempid data type.
  See reconciler documentation for further information.

  `:transit-handlers` (optional). A map with keys for `:read` and `:write`, which contain maps to be used for the read
  and write side of transit to extend the supported data types. See `make-fulcro-network` in network.cljs. Only used
  when you default to the built-in networking.

  `:shared` (optional). A map of arbitrary values to be shared across all components, accessible to them via (prim/shared this)

  `:load-marker-default` (optional) Location where the fulcro load markers will be added for network requests (default is false).

  `:read-local` (optional). An read function for the Parser. (fn [env k params] ...). If supplied,
  it will be called once for each root-level query key. If it returns `nil` or `false` for that key then the built-in Fulcro read will handle that
  branch of the root query. If it returns a map with the shape `{:value ...}`, then that will be used for the response. This is *not*
  recursive. If you begin handling a *branch* (e.g. a join), you must finish doing so (though if using recursion, you can technically handle just
  the properties that need your custom handling). At any time you can use `prim/db->tree` to get raw graph data from the database for a branch.
  NOTE: *it will be allowed* to trigger remote reads. This is not recommended, as you will probably have to augment the networking layer to
  get it to do what you mean. Use `load` instead. You have been warned. Triggering remote reads is allowed, but discouraged and unsupported.

  `:query-interpreter` (optional). A custom query engine (parser and interpreter) that will be used to satisfy all
  local client queries from the local state database. Cannot be used with `:read-local`.
  It must be a `(fn [env query] )` that returns the result for the given query. It will not be given mutations.
  The `env` will contain the `:state` atom, `:shared`, and `:parser`. It may also contain the `:reconciler`.

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
  ([] (make-fulcro-client {}))
  ([{:keys [initial-state mutation-merge started-callback networking reconciler-options query-interpreter
            read-local request-transform network-error-callback migrate transit-handlers shared load-marker-default]
     :or   {initial-state {} read-local (constantly false) started-callback (constantly nil) network-error-callback (constantly nil)
            migrate       nil shared nil load-marker-default false}
     :as   options}]
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
                        :query-interpreter  query-interpreter
                        :reconciler-options (merge (cond-> {}
                                                     tx-listen (assoc :tx-listen tx-listen)
                                                     instrument (assoc :instrument instrument)
                                                     lifecycle (assoc :lifecycle lifecycle)
                                                     migrate (assoc :migrate migrate)
                                                     shared (assoc :shared shared))
                                              {:load-marker-default load-marker-default}
                                              reconciler-options)
                        :networking         networking}))))


(defn new-fulcro-client
  "Deprecated: use make-fulcro-client instead."
  [& {:as options}]
  (let [load-marker-default (get options :load-marker-default true)
        options-with-marker-default (assoc options :load-marker-default load-marker-default)]
    (make-fulcro-client options-with-marker-default)))


(defprotocol FulcroApplication
  (mount [this root-component target-dom-id] "Start/replace the webapp on the given DOM ID or DOM Node.")
  (reset-state! [this new-state] "Replace the entire app state with the given (pre-normalized) state.")
  (reset-app! [this root-component callback] "Replace the entire app state with the initial app state defined on the root component (includes auto-merging of unions). callback can be nil, a function, or :original (to call original started-callback).")
  (clear-pending-remote-requests! [this remotes] "Remove all pending network requests on the given remote(s). Useful on failures to eliminate cascading failures. Remote can be a keyword, set, or nil. `nil` means all remotes.")
  (refresh [this] "Refresh the UI (force re-render).")
  (history [this] "Return the current UI history of the application, suitable for network transfer")
  (abort-request! [this abort-id] "Abort the given request on all remotes. abort-id is a self-assigned ID for the remote interaction.")
  (reset-history! [this] "Returns the application with history reset to its initial, empty state. Resets application history to its initial, empty state. Suitable for resetting the app for situations such as user log out."))

(defn- start-networking
  "Starts all remotes in a map. If a remote's `start` returns something that implements `FulcroNetwork`,
  update the network map with this value. Returns possibly updated `network-map`."
  [network-map]
  #?(:cljs (into {} (for [[k remote] network-map
                          :let [started (when (implements? net/FulcroNetwork remote) (net/start remote))
                                valid   (if (implements? net/FulcroNetwork started) started remote)]]
                      [k valid]))
     :clj  {}))

(defn -mutation-query?
  "PRIVATE"
  [tx]
  (boolean (some #(util/mutation? %) tx)))

(defn- split-parser
  "Generate a parser that splits reads and writes across two parsers: the supplied query parser for queries, and the built-in
  parser for mutations."
  [query-parser]
  (let [mutation-parser (prim/parser {:read (constantly nil) :mutate app/write-entry-point})]
    (fn split-parser*
      ([env query target]
       (if (-mutation-query? query)
         (mutation-parser env query target)
         (query-parser env query)))
      ([env query] (split-parser* env query nil)))))

(defn -initialize
  "PRIVATE.

  Initialize the fulcro Application. Creates network queue, sets up i18n, creates reconciler, mounts it, and returns
  the initialized app"
  [{:keys [networking read-local started-callback query-interpreter] :as app} initial-state root-component dom-id-or-node reconciler-options]
  (let [network-map         (normalize-network networking)
        reconciler-options  (if (-> reconciler-options :id not)
                              (assoc reconciler-options :id (if (string? dom-id-or-node) dom-id-or-node (util/unique-key)))
                              reconciler-options)
        remotes             (keys network-map)
        send-queues         (zipmap remotes (map #(async/chan 1024) remotes))
        response-channels   (zipmap remotes (map #(async/chan) remotes))
        parser              (if query-interpreter
                              (split-parser query-interpreter)
                              (prim/parser {:read (partial app/read-local read-local) :mutate app/write-entry-point}))
        initial-app         (assoc app :send-queues send-queues :response-channels response-channels
                                       :parser parser :mounted? true)
        app-with-networking (assoc initial-app :networking (start-networking network-map))
        rec                 (app/generate-reconciler app-with-networking initial-state parser reconciler-options)
        completed-app       (assoc app-with-networking :reconciler rec)
        node #?(:cljs (if (string? dom-id-or-node)
                        (gdom/getElement dom-id-or-node)
                        dom-id-or-node)
                :clj        dom-id-or-node)]
    (app/initialize-global-error-callbacks completed-app)
    (app/start-network-sequential-processing completed-app)
    (prim/add-root! rec root-component node)
    ; this has to come after add root so normalization is complete
    (merge-alternate-union-elements! completed-app root-component)
    (when started-callback
      (started-callback completed-app))
    completed-app))

(defn clear-queue
  "Needed for mocking in tests. Do not use directly. Use FulcroApplication protocol methods instead."
  [queue]
  (loop [element (async/poll! queue)]
    (when element
      (recur (async/poll! queue)))))

(defn -abort-items-on-queue
  "PRIVATE"
  [queue abort-id]
  (let [elements (atom [])]
    (loop [element (async/poll! queue)]
      (when element
        (when-not (some-> element ::net/abort-id (= abort-id))
          (swap! elements conj element))
        (recur (async/poll! queue))))
    (doseq [e @elements]
      (async/offer! queue e))))

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
  (cutil/force-render reconciler))

(defn mount* [{:keys [mounted? initial-state reconciler-options] :as app} root-component dom-id-or-node]
  (if mounted?
    (do
      (refresh* app root-component dom-id-or-node)
      app)
    (let [uses-initial-app-state? (prim/has-initial-app-state? root-component)
          ui-declared-state       (and uses-initial-app-state? (fulcro.client.primitives/initial-state root-component nil))
          explicit-state?         (or (util/atom? initial-state) (and (seq initial-state) (map? initial-state)))
          state                   (cond
                                    explicit-state? (if initial-state initial-state {})
                                    ui-declared-state ui-declared-state
                                    :otherwise {})]
      (-initialize app state root-component dom-id-or-node reconciler-options))))

(defrecord Application [initial-state mutation-merge started-callback remotes networking send-queues
                        response-channels reconciler read-local query-interpreter mounted? reconciler-options]
  FulcroApplication
  (mount [this root-component dom-id-or-node] (mount* this root-component dom-id-or-node))

  (reset-state! [this new-state] (reset! (prim/app-state reconciler) new-state))

  (reset-app! [this root-component callback]
    (if (not (prim/has-initial-app-state? root-component))
      (log/error "The specified root component does not implement InitialAppState!")
      (let [initial-state (fulcro.client.primitives/initial-state root-component nil)
            base-state (prim/tree->db root-component initial-state true
                         (fulcro.client.primitives/pre-merge-transform initial-state))]
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

  (abort-request! [this abort-id]
    #?(:cljs
       (doseq [r (keys networking)]
         (let [remote-net (get networking r)]
           (when (implements? net/FulcroRemoteI remote-net)
             (net/abort remote-net abort-id)
             (-abort-items-on-queue (get send-queues r) abort-id))))))

  (history [this] (prim/get-history reconciler))
  (reset-history! [this]
    (reset-history-impl this))

  (refresh [this] (cutil/force-render reconciler)))

(defn new-fulcro-test-client
  "DEPRECATED: use make-fulcro-test-client instead"
  [& {:keys [initial-state started-callback reconciler-options]
      :or   {initial-state {} started-callback nil}}]
  (map->Application {:initial-state      initial-state
                     :started-callback   started-callback
                     :reconciler-options reconciler-options
                     :networking         (net/mock-network)}))

(defn make-fulcro-test-client
  "Create a test client that has no networking. Useful for UI testing with a real Fulcro app container."
  [& {:keys [initial-state started-callback reconciler-options]
      :or   {initial-state {} started-callback nil}}]
  (map->Application {:initial-state      initial-state
                     :started-callback   started-callback
                     :reconciler-options (merge reconciler-options {:load-marker-default false})
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

(def merge-component "DEPRECATED: Now defined in fulcro.client.primitives" prim/merge-component)
(def merge-alternate-unions "DEPRECATED: Now defined in fulcro.client.primitives" prim/merge-alternate-unions)
(def merge-alternate-union-elements! "DEPRECATED: Now defined in fulcro.client.primitives" prim/merge-alternate-union-elements!)
(def merge-alternate-union-elements "DEPRECATED: Now defined in fulcro.client.primitives" prim/merge-alternate-union-elements)
(def integrate-ident "DEPRECATED: Now defined in fulcro.client.primitives" prim/integrate-ident)
(def integrate-ident! "DEPRECATED: Now defined in fulcro.client.primitives" prim/integrate-ident!)
(defn merge-state! "See primitives/merge-component!" [app-or-reconciler component object-data & named-params]
  (log/info app-or-reconciler)
  (let [reconciler (if #?(:cljs (implements? FulcroApplication app-or-reconciler)
                          :clj  (satisfies? FulcroApplication app-or-reconciler))
                     (:reconciler app-or-reconciler)
                     app-or-reconciler)]
    (apply prim/merge-component! reconciler component object-data named-params)))
(def iinitial-app-state? "DEPRECATED: Now defined in fulcro.client.primitives" prim/has-initial-app-state?)
(def iident? "DEPRECATED: Now defined in fulcro.client.primitives" prim/has-ident?)
