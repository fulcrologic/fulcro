(ns fulcro.client.impl.application
  (:require [fulcro.logging :as log]
            [fulcro.client.primitives :as prim]
            [fulcro.client.mutations :as m]
            [fulcro.history :as hist]
            [clojure.set :as set]
            [fulcro.client.impl.data-fetch :as f]
            [fulcro.util :as futil]
            [fulcro.client.util :as util]
    #?(:clj
            [clojure.future :refer :all])
            [clojure.spec.alpha :as s]
    #?(:cljs [cljs.core.async :as async]
       :clj
            [clojure.core.async :as async :refer [go]])
            [fulcro.client.network :as net]
            [fulcro.client.impl.protocols :as p])
  #?(:cljs (:require-macros
             [cljs.core.async.macros :refer [go]])))

(defn fallback-handler
  "This internal function is responsible for generating and returning a function that can accomplish calling the fallbacks that
  appear in an incoming transaction, which is in turn used by the error-handling logic of the plumbing."
  [{:keys [reconciler]} query]
  (fn [error]
    (swap! (prim/app-state reconciler) assoc :fulcro/server-error error)
    (if-let [q (prim/fallback-tx query error)]
      (do (log/warn "Transaction failed. Running fallback." q)
          (prim/transact! reconciler q))
      (log/warn "Fallback triggered, but no fallbacks were defined."))))

;; this is here so we can do testing (can mock core async stuff out of the way)
(defn- enqueue
  "Enqueue a send to the network queue. This is a standalone function because we cannot mock core async functions."
  [q v]
  (go (async/>! q v)))

(s/fdef enqueue
  :args (s/cat :queue any? :payload ::f/payload))

(defn real-send
  "Do a properly-plumbed network send. This function recursively strips ui attributes from the tx and pushes the tx over
  the network. It installs the given on-load and on-error handlers to deal with the network response. DEPRECATED: If
  you're doing something really low-level with networking, use send-with-history-tracking."
  ([net {:keys [reconciler tx on-done on-error on-load abort-id]}]
    ; server-side rendering doesn't do networking. Don't care.
    #?(:cljs
       (let [progress-tx #(m/progressive-update-transaction tx %)
             tx          (prim/strip-ui tx)]
         (cond
           (implements? net/ProgressiveTransfer net) (net/updating-send net tx on-done on-error on-load)
           (implements? net/FulcroNetwork net) (net/send net tx on-done on-error)
           (implements? net/FulcroRemoteI net)
           (let [on-done  (fn [{:keys [body transaction]}] (on-done body transaction))
                 on-error (fn [{:keys [body]}] (on-error body))
                 on-load  (fn [progress] (when reconciler (prim/transact! reconciler (progress-tx progress))))]
             (net/transmit net {::net/edn              tx
                                ::net/abort-id         abort-id
                                ::net/ok-handler       on-done
                                ::net/error-handler    on-error
                                ::net/progress-handler on-load}))))))
  ([net tx on-done on-error on-load]
   (real-send net {:tx tx :on-done on-done :on-error on-error :on-load on-load})))

(defn send-with-history-tracking
  "Does a real send but includes history activity tracking to prevent the gc of history that is related to active
  network requests. If you're doing something really low level in the networking, you want this over real-send."
  ([net {:keys [reconciler payload tx on-done on-error on-load]}]
   (let [{:keys [::hist/history-atom ::hist/tx-time ::prim/remote ::net/abort-id]} payload
         uuid                           (futil/unique-key)
         network-activity               (prim/get-network-activity reconciler)
         with-network-activity-tracking (fn [handler]
                                          (fn [resp items-or-tx]
                                            (swap! network-activity update-in [remote :active-requests] #(dissoc % uuid))
                                            #?(:cljs (js/setTimeout ; delay necessary so we don't mark things inactive too soon
                                                      #(when (= (-> @network-activity (get remote) :active-requests count) 0)
                                                         (swap! (prim/app-state reconciler) assoc-in [::net/status remote] :idle)
                                                         (swap! network-activity assoc-in [remote :status] :idle)
                                                         (p/queue! reconciler [::net/status]))
                                                      0))
                                            (handler resp items-or-tx)))
         with-history-recording         (fn [handler]
                                          (fn [resp items-or-tx]
                                            (when (and history-atom remote tx-time)
                                              (swap! history-atom hist/remote-activity-finished remote tx-time))
                                            (handler resp items-or-tx)))
         on-done                        (with-network-activity-tracking (with-history-recording on-done))
         on-error                       (with-network-activity-tracking (with-history-recording on-error))]
     (if (and history-atom tx-time remote)
       (swap! history-atom hist/remote-activity-started remote tx-time)
       (log/warn "Payload had no history details."))
     (swap! network-activity update-in [remote :active-requests] assoc uuid {:query    (::prim/query payload)
                                                                             :abort-id (::net/abort-id payload)})
     (real-send net {:reconciler reconciler :tx tx :on-done on-done :on-error on-error :on-load on-load :abort-id abort-id})))
  ([payload net tx on-done on-error on-load]
   (send-with-history-tracking net {:payload payload :tx tx :on-done on-done :on-error on-error :on-load on-load})))

(defn split-mutations
  "Split a tx that contains mutations.

   Examples:
   [(f) (g)] => [[(f) (g)]]
   [(f) (g) (f) (k)] => [[(f) (g)] [(f) (k)]]
   [(f) (g) (f) (k) (g)] => [[(f) (g)] [(f) (k) (g)]]

   This function splits any mutation that uses the same dispatch symbol more than once (since returns from server go
   into a map, and that is the only way to get return values from both), and also when the mutations do not share abort
   IDs (so that mutations do not get grouped into a transaction that could cause them to get cancelled incorrectly).

   Returns a sequence that contains one or more transactions."
  [tx]
  (if-not (and (vector? tx) (every? (fn [t] (or (futil/mutation-join? t) (and (list? t) (symbol? (first t))))) tx))
    (do
      (log/error "INTERNAL ERROR: split-mutations was asked to split a tx that contained things other than mutations." tx)
      [tx])
    (if (empty? tx)
      []
      (let [dispatch-symbols  (fn [tx]
                                (into #{}
                                  (comp (map :key) (filter symbol?))
                                  (some-> tx prim/query->ast :children)))
            compatible-abort? (fn [tx1 tx2]
                                (let [a1 (m/abort-ids tx1)
                                      a2 (m/abort-ids tx2)]
                                  (or
                                    (and (= 1 (count a1)) (= a1 a2))
                                    (and (empty? a1) (empty? a2)))))
            can-be-included?  (fn [tx expr]
                                (or
                                  (empty? tx)
                                  (and
                                    (compatible-abort? tx [expr])
                                    (empty? (set/intersection (dispatch-symbols tx) (dispatch-symbols [expr]))))))
            {:keys [transactions current]} (reduce
                                             (fn [{:keys [current] :as acc} expr]
                                               (if (can-be-included? current expr)
                                                 (update acc :current conj expr)
                                                 (-> acc
                                                   (update :transactions conj current)
                                                   (assoc :current [expr]))))
                                             {:transactions [] :current []}
                                             tx)]
        (if (empty? current)
          transactions
          (conj transactions current))))))

(defn enqueue-mutations
  "Splits out the (remote) mutations and fallbacks in a transaction, creates an error handler that can
   trigger fallbacks, and enqueues the remote mutations on the network queue. If duplicate mutation names
   appear, then they will be separated into separate network requests.

   NOTE: If the mutation in the tx has duplicates, then the same fallback will be used for the
   resulting split tx. See `split-mutations` (which is used by this function to split dupes out of txes)."
  [{:keys [reconciler send-queues] :as app} remote-tx-map cb]
  ; NOTE: for history navigation we need to track the time at which the mutation was submitted. If we roll back, we want the db-before of that tx-time.
  (let [history (prim/get-history reconciler)]
    (doseq [remote (keys remote-tx-map)]
      (let [queue                    (get send-queues remote)
            full-remote-transaction  (get remote-tx-map remote)
            refresh-set              (or (some-> full-remote-transaction meta ::prim/refresh vec) [])
            tx-time                  (some-> full-remote-transaction meta ::hist/tx-time)
            ; IMPORTANT: fallbacks claim to be on every remote (otherwise we have to make much more complicated logic about
            ; the tx submission in transact and parsing). So, you will get a fallback handler for every **defined** remote.
            ; The remove-loads-and-fallbacks will return an empty list if all there are is fallbacks, keeping us from submitting
            ; a tx that contains only fallbacks.
            fallback                 (fallback-handler app full-remote-transaction)
            desired-remote-mutations (prim/remove-loads-and-fallbacks full-remote-transaction)
            tx-list                  (split-mutations desired-remote-mutations)
            has-mutations?           (fn [tx] (> (count tx) 0))
            payload                  (fn [tx]
                                       (let [abort-id (some-> tx m/abort-ids first)]
                                         {::prim/query        tx
                                          ::hist/tx-time      tx-time
                                          ::hist/history-atom history
                                          ::prim/remote       remote
                                          ::net/abort-id      abort-id
                                          ::f/on-load         (fn [result mtx]
                                                                ; middleware can modify tx, so we have to take as a param
                                                                (cb result (or mtx tx) remote))
                                          ::f/on-error        (fn [result] (fallback result))}))]
        (doseq [tx tx-list]
          (when (has-mutations? tx)
            (enqueue queue (payload tx))))))))

(defn enqueue-reads
  "Finds any loads marked `parallel` and triggers real network requests immediately. Remaining loads
  are pulled into a single fetch payload (combined into one query) and enqueued behind any prior mutations/reads that
  were already requested in a prior UI/event cycle. Thus non-parallel reads are processed in clusters grouped due to UI
  events (a single event might trigger many reads which will all go to the server as a single combined request).
  Further UI events that trigger remote interaction will end up waiting until prior network request(s) are complete.

  This ensures that default reasoning is simple and sequential in the face of optimistic UI updates (real network
  traffic characteristics could cause out of order processing, and you would not want
  a 'create list' to be processed on the server *after* an 'add an item to the list'). "
  [{:keys [send-queues reconciler networking]}]
  (doseq [remote (keys send-queues)]
    (let [queue            (get send-queues remote)
          network          (get networking remote)
          parallel-payload (f/mark-parallel-loading! remote reconciler)]
      (doseq [{:keys [::prim/query ::f/on-load ::f/on-error ::f/load-descriptors ::net/abort-id] :as payload} parallel-payload]
        (let [on-load'  #(on-load % load-descriptors)
              on-error' #(on-error % load-descriptors)]
          ; TODO: Update reporting is now possible with new FulcroRemote. Need to plumb (just for parallel loads, done in queue otherwise).
          (send-with-history-tracking network {:payload payload :reconciler reconciler :tx query
                                               :on-done on-load' :on-error on-error' :abort-id abort-id})))
      (loop [fetch-payload (f/mark-loading remote reconciler)]
        (when fetch-payload
          (enqueue queue (assoc fetch-payload :networking network))
          (recur (f/mark-loading remote reconciler)))))))

(defn detect-errant-remotes [{:keys [reconciler send-queues] :as app}]
  (let [state           (prim/app-state reconciler)
        all-items       (get @state :fulcro/ready-to-load)
        item-remotes    (into #{}
                          (map f/data-remote)
                          all-items)
        all-remotes     (set (keys send-queues))
        invalid-remotes (clojure.set/difference item-remotes all-remotes)]
    (when (not-empty invalid-remotes) (log/error "Use of invalid remote(s) detected! " invalid-remotes))))

(defn server-send
  "Puts queries/mutations (and their corresponding callbacks) onto the send queue. The networking code will pull these
  off one at a time and send them through the real networking layer. Reads are guaranteed to *follow* writes."
  [app remote-tx-map merge-result-callback]
  (detect-errant-remotes app)
  (enqueue-mutations app remote-tx-map merge-result-callback)
  (enqueue-reads app))

(defn- send-payload
  "Sends a network payload. There are two kinds of payloads in Fulcro. The first is
  for reads, which are tracked by load descriptors in the app state. These load descriptors
  tell the plumbing how to handle the response, and expect to only be merged in once. Mutations
  do not have a payload, and can technically receive progress updates from the network. The built-in
  networking does not (currently) give progress events, but plugin networking can. It is currently not
  supported to give an update on a load, so this function is careful to detect that a payload is a send
  and turns all but the last update into a no-op. The send-complete function comes from the
  network sequential processing loop, and when called unblocks the network processing to allow the
  next request to go. Be very careful with this code, as bugs will cause applications to stop responding
  to remote requests."
  [network reconciler payload send-complete]
  ; Note, only data-fetch reads will have load-descriptors,
  ; in which case the payload on-load is data-fetch/loaded-callback, and cannot handle updates.
  (let [{:keys [::prim/query ::f/on-load ::f/on-error ::f/load-descriptors ::net/abort-id]} payload
        merge-data (if load-descriptors #(on-load % load-descriptors) #(on-load %1 %2))
        on-update  (if load-descriptors identity merge-data)
        on-error   (if load-descriptors #(on-error % load-descriptors) on-error)
        on-error   (comp send-complete on-error)
        on-done    (comp send-complete merge-data)]
    (if (f/is-deferred-transaction? query)
      (on-done {})                                          ; immediately let the deferred tx go by pretending that the load is done
      (send-with-history-tracking network {:payload payload :tx query :reconciler reconciler
                                           :on-done on-done :on-error on-error
                                           :on-load on-update :abort-id abort-id}))))

(defn is-sequential? [network]
  (if #?(:clj false :cljs (satisfies? net/NetworkBehavior network))
    (net/serialize-requests? network)
    true))

(defn start-network-sequential-processing
  "Starts a async go loop that sends network requests on networking object's request queue.
   Gives the appearance of a separate networking 'thread' using core async."
  [{:keys [networking reconciler send-queues response-channels] :as app}]
  (doseq [remote (keys send-queues)]
    (let [queue            (get send-queues remote)
          network          (get networking remote)
          sequential?      (is-sequential? network)
          response-channel (get response-channels remote)
          send-complete    (if sequential?
                             (fn [] (go (async/>! response-channel :complete)))
                             identity)]
      (go
        (loop [payload (async/<! queue)]
          (send-payload network reconciler payload send-complete) ; async call. Calls send-complete when done
          (when sequential?
            (async/<! response-channel))                    ; block until send-complete
          (recur (async/<! queue)))))))

(defn generate-reconciler
  "The reconciler's send method calls FulcroApplication/server-send, which itself requires a reconciler with a
  send method already defined. This creates a catch-22 / circular dependency on the reconciler and :send field within
  the reconciler.

  To resolve the issue, we def an atom pointing to the reconciler that the send method will deref each time it is
  called. This allows us to define the reconciler with a send method that, at the time of initialization, has an app
  that points to a nil reconciler. By the end of this function, the app's reconciler reference has been properly set."
  [{:keys [send-queues mutation-merge] :as app} initial-state parser {:keys [migrate] :as reconciler-options}]
  (let [rec-atom                  (atom nil)
        remotes                   (keys send-queues)
        tempid-migrate            (fn [pure _ tempids]
                                    (doseq [queue (vals send-queues)]
                                      (prim/rewrite-tempids-in-request-queue queue tempids))
                                    (let [state-migrate (or migrate prim/resolve-tempids)]
                                      (state-migrate pure tempids)))
        complete-initial-state    (let [set-default-locale  (fn [s] (update s :ui/locale (fnil identity :en)))
                                        set-network-markers (fn [s] (assoc s ::net/status (zipmap remotes (repeat :idle))))
                                        is-atom?            (futil/atom? initial-state)]
                                    (if is-atom?
                                      (do
                                        (swap! initial-state set-default-locale)
                                        (swap! initial-state set-network-markers)
                                        initial-state)
                                      (do
                                        (-> initial-state
                                            set-default-locale
                                            set-network-markers))))
        config                    (merge {}
                                    reconciler-options
                                    {:migrate     tempid-migrate
                                     :state       complete-initial-state
                                     :send        (fn [sends-keyed-by-remote result-merge-callback]
                                                    (server-send (assoc app :reconciler @rec-atom) sends-keyed-by-remote result-merge-callback))
                                     :normalize   true
                                     :remotes     remotes
                                     :merge-ident (fn [reconciler app-state ident props]
                                                    (update-in app-state ident (comp prim/sweep-one merge) props))
                                     :merge-tree  (fn [target source]
                                                    (prim/merge-handler mutation-merge target source))
                                     :parser      parser})
        rec                       (prim/reconciler config)]
    (reset! rec-atom rec)
    rec))

(defn initialize-global-error-callbacks
  [app]
  (doseq [remote (keys (:networking app))]
    (let [cb-atom (get-in app [:networking remote :global-error-callback])]
      (when (futil/atom? cb-atom)
        (swap! cb-atom #(if (fn? %)
                          (partial % (prim/app-state (:reconciler app)))
                          (throw (ex-info "Networking error callback must be a function." {}))))))))

(defn read-local
  "Read function for the built-in parser.

  *** NOTE: This function only runs when it is called without a target -- it is not triggered for remote reads. To
  trigger a remote read, use the `fulcro/data-fetch` namespace. ***

  If a user-read is supplied, *it will be allowed* to trigger remote reads. This is not recommended, as you
  will probably have to augment the networking layer to get it to do what you mean. Use `load` instead. You have
  been warned. Triggering remote reads is allowed, but discouraged and unsupported.

  Returns the current locale when reading the :ui/locale keyword. Otherwise pulls data out of the app-state.
  "
  [user-read {:keys [query target state ast] :as env} dkey params]
  (if-let [custom-result (user-read env dkey params)]
    custom-result
    (when (not target)
      (let [top-level-prop (nil? query)
            key            (or (:key ast) dkey)
            by-ident?      (futil/ident? key)
            union?         (map? query)
            data           (if by-ident? (get-in @state key) (get @state key))]
        {:value
         (cond
           union? (get (prim/db->tree [{key query}] @state @state) key)
           top-level-prop data
           :else (prim/db->tree query data @state))}))))

(defn write-entry-point
  "This is the entry point for writes. In general this is simply a call to the multi-method
  defined by Fulcro (mutate); however, Fulcro supports the concept of a global `post-mutate`
  function that will be called anytime the general mutate has an action that is desired. This
  can be useful, for example, in cases where you have some post-processing that needs
  to happen for a given (sub)set of mutations (that perhaps you did not define)."
  [env k params]
  (let [rv     (try
                 (m/mutate env k params)
                 (catch #?(:cljs :default :clj Exception) e
                   (log/error "Mutation " k " failed with exception" e)
                   nil))
        action (:action rv)]
    (if action
      (assoc rv :action (fn []
                          (try
                            (let [action-result (action env k params)]
                              (try
                                (m/post-mutate env k params)
                                (catch #?(:cljs :default :clj Exception) e (log/error "Post mutate failed on dispatch to " k)))
                              action-result)
                            (catch #?(:cljs :default :clj Exception) e
                              (log/error "Mutation " k " failed with exception")
                              #?(:cljs (when goog.DEBUG (js/console.error e)))
                              (throw e)))))
      rv)))

