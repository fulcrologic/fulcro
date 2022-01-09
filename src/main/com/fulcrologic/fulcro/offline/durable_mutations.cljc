(ns com.fulcrologic.fulcro.offline.durable-mutations
  "Support for durable mutations that can be automatically retried against a remote until a positive
   confirmation of success is received, or a specific kind of error indicates it cannot succeed.

   You must configure your application in order to use this support. In particular, a durable storage for the mutations
   must be defined so that they can survive application restarts, power failures, etc.  Your implementation of that storage will determine
   the overall reliability of these mutations. Additionally, you *may* define a custom optimistic tempid strategy that can do
   tempid remappings before ever talking to the server.

   The mutations in a durable transaction *must* be:

   * Idempotent
   * Order-independent

   This system asserts that the mutation in question *will eventually* run. However, it exposes the details of
   what is really going on at the mutation layer so that you can customize the behavior as needed.

   IMPORTANT: The optimistic side of the mutation will run any time the mutation is tried. You can check (via
   `durable-mutations/retry?`) if this is a retry, and decide if there is anything to do in the UI.

   Optimistic temporary ID remapping is supported, but requires that you define a TempidStrategy.
   This can be done by either pre-allocating a range from the server, or by allowing the client to use generated
   UUIDs (i.e. the UUID within the tempid itself). *Your server must agree on this strategy for consistent operation*.

   It is a valid strategy to *not* resolve the tempids on the client, in which case your app state will have tempids
   on the items submitted until the real mutation completes. Tempid rewrites from the server are always applied, even
   if you had previously remapped them on the client.

   Server-side implementations of durable mutations cannot have any meaningful return value other than `:tempids` since
   there is no guarantee when the real mutation will run.

   There is a closure goog.define of BACKOFF_LIMIT_MS which defaults to 30000 (ms). This limits the maximum wait time between
   mutation retries and can be modified via compiler settings. See compiler docs.

   NOTE: The backoff will cause mutations to appear somewhat slowly when network communication resumes. This is by design,
   and will prevent a flood of requests after a server outage. Caution: Setting this too low can cause blockage on retry
   logic when the network is down.

   There is also a LOOP_TIMEOUT_MS (default 20ms). Any durable mutation transact submission, on average, appear
   to have this much latency. Setting it too low will cause the browser to waste a lot of CPU looking for trouble,
   setting it too high will annoy the user. DO NOT SET THIS TO 0.

   Installation: See `with-durable-mutations`.

   Usage: Use `transact!` from this ns, or add `:com.fulcrologic.fulcro.offline.durable-mutations/durable? true` to the
   options of your call to `comp/transact!`. Do not include follow-on reads in the transaction if using `comp/transact!`.
   "
  (:require
    [clojure.core.async :as async]
    #?@(:cljs [[com.fulcrologic.fulcro.algorithms.tempid :as tempid :refer [TempId]]]
        :clj  [[com.fulcrologic.fulcro.algorithms.tempid :as tempid]])
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.offline.durable-edn-store :as des]
    [com.fulcrologic.fulcro.offline.tempid-strategy :as tids]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [now]]
    [com.fulcrologic.fulcro.raw.components :as rc])
  #?(:clj
     (:import
       [java.util Date UUID]
       [com.fulcrologic.fulcro.algorithms.tempid TempId])))

;; The maximum time to wait, in ms, between network tries of a mutation
#?(:cljs (goog-define BACKOFF_LIMIT_MS 30000))
;; ms to delay between scanning stored mutations
#?(:cljs (goog-define LOOP_TIMEOUT_MS 20))

(def loop-timeout-ms #?(:clj 1000 :cljs LOOP_TIMEOUT_MS))
(def backoff-limit-ms #?(:clj 1000 :cljs BACKOFF_LIMIT_MS))

(defn- next-id []
  #?(:clj (UUID/randomUUID) :cljs (random-uuid)))

(defn current-mutation-store
  "Returns the current mutation store of the given Fulcro app"
  [app]
  (get-in app [:com.fulcrologic.fulcro.application/config ::mutation-store]))

(defn current-tempid-strategy
  "Returns the current tempid strategy of the given Fulcro app"
  [app]
  (get-in app [:com.fulcrologic.fulcro.application/config ::tempid-strategy]))

(defn- vanilla-transact!
  "Calls the non-augmented transaction submission."
  [app txn options]
  (let [tx! (get-in app [:com.fulcrologic.fulcro.application/algorithms ::original-tx!])]
    (tx! app txn options)))

(defn- with-durable-transact
  "Augments the app with a customized transaction handler that adds durable mutation support."
  [app]
  (let [normal-transact! (get-in app [:com.fulcrologic.fulcro.application/algorithms :com.fulcrologic.fulcro.algorithm/tx!])]
    (log/debug "Installing write-through transact support")
    (-> app
      (assoc-in [:com.fulcrologic.fulcro.application/algorithms ::original-tx!] normal-transact!)
      (assoc-in [:com.fulcrologic.fulcro.application/algorithms :com.fulcrologic.fulcro.algorithm/tx!]
        (fn write-through-transact!
          ([app tx]
           (write-through-transact! app tx {}))
          ([app txn options]
           (if (::durable? options)
             (let [store   (current-mutation-store app)
                   ts      (current-tempid-strategy app)
                   t->r    (tids/-resolve-tempids ts txn)
                   txn     (tids/-rewrite-txn ts txn t->r)
                   ;; Persistent mutations will run from an async loop based on this store.
                   id      (next-id)
                   options (update options :component (fn [c] (when c (rc/class->registry-key c))))]
               (async/go
                 (if (async/<!
                       (des/-save-edn! store id {:id           id
                                                 :txn          txn
                                                 :created      (inst-ms (now))
                                                 :last-attempt 0
                                                 :attempt      0
                                                 :options      (assoc options ::tempid->real-id t->r)}))
                   (log/debug "Saved rewritten" txn "at id" id)
                   (do
                     (log/error "Save failed. Running transaction now, non-durably. See https://book.fulcrologic.com/#err-dm-save-failed")
                     (normal-transact! app txn options))))
               id)
             (do
               (log/debug "NORMAL transaction" txn)
               (normal-transact! app txn options)))))))))

(defn- mark-active!
  "Mark a transaction as in-progress on the current app, so that we don't double-submit it."
  [{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app} txn-id ??]
  (if ??
    (swap! runtime-atom update ::active-transactions (fnil conj #{}) txn-id)
    (swap! runtime-atom update ::active-transactions disj txn-id)))

(defn- active?
  "Check to see if the transaction with txn-id is actively on the network."
  [{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app} txn-id]
  (contains? (get @runtime-atom ::active-transactions #{}) txn-id))


(defn- transaction-loop!
  "Runs an infinite loop on the transaction store and runs non-active transactions, in order of creation, one at a time
   to ensure original submission order."
  [app]
  (async/go-loop []
    (let [store (current-mutation-store app)]
      (async/<! (async/timeout loop-timeout-ms))
      (doseq [{:keys [id txn options last-attempt attempt]} (->> (async/<! (des/-load-all store))
                                                              (map :value)
                                                              (sort-by :created))]
        (when (and id (not (active? app id)))
          (let [now       (inst-ms (now))
                last-time (or last-attempt 0)
                n         (min attempt 1000)
                delay     (min backoff-limit-ms (* n n 1000))
                options   (if (keyword? (:component options))
                            (update options :component rc/registry-key->class)
                            options)]
            (if (or (= 0 attempt) (> (- now last-time) delay))
              (do
                (log/debug "Found inactive pending transaction" txn)
                (log/debug "Attempts" attempt)
                (log/debug "Delay" delay)
                (vanilla-transact! app txn (assoc options
                                             ::id id
                                             ::attempt attempt
                                             ::retry? (> (or attempt 0) 0)))
                (mark-active! app id true)))))))
    (recur)))

(defn attempt
  "Returns the attempt number of the mutation (using that mutation's env)."
  [mutation-env]
  (-> mutation-env ::txn/options ::attempt))

(defn cancel-mutation!
  "Can be called from your mutation `error-action` (or `result-action`) to cancel any future attempts of the
   current instance of the current mutation."
  [{::txn/keys [options] :keys [app]}]
  (let [{::keys [id]} options]
    (when-not id
      (log/error "The transaction that submitted this mutation did not assign it a persistent store ID."
        "This probably means you did not submit it as a durable mutation. See https://book.fulcrologic.com/#err-dm-missing-store-id"))
    (when-let [storage (current-mutation-store app)]
      (des/-delete! storage id))))

(defn- mutation-post-processing
  "Part of the custom mutation result handler. Remembers that the given transaction is no longer on the network, and
   removes the mutation from the persistent store if it was successful."
  [env]
  (let [{::txn/keys [options]
         :keys      [app result]} env
        {::keys [durable? id tempid->real-id]} options
        remote-error? (ah/app-algorithm app :remote-error?)]
    (when durable?
      (if-not id
        (log/error "INTERNAL ERROR: TXN ID MISSING! See https://book.fulcrologic.com/#err-dm-int-txn-id-missing")
        (async/go
          (let [store (current-mutation-store app)]
            (when-not (empty? tempid->real-id)
              (tempid/resolve-tempids! app {'ignored {:tempids tempid->real-id}}))
            (if (remote-error? result)
              (do
                (log/debug "Mutation had an error.")
                (when-not (async/<!
                            (des/-update-edn! store id (fn [{:keys [attempt] :as entry}]
                                                         (assoc entry :attempt (inc attempt)
                                                                      :last-attempt (inst-ms (now))))))
                  (log/error "Failed to update durable mutation! See https://book.fulcrologic.com/#err-dm-update-failed")))
              (do
                (log/debug "Persistent mutation completed on server. Removing it from storage")
                (cancel-mutation! env)))
            (mark-active! app id false)))))
    env))

(defn- with-augmented-result-action [app]
  (let [result-action! (get-in app [:com.fulcrologic.fulcro.application/algorithms :com.fulcrologic.fulcro.algorithm/default-result-action!])]
    (assoc-in app [:com.fulcrologic.fulcro.application/algorithms :com.fulcrologic.fulcro.algorithm/default-result-action!]
      (fn result-action [env]
        (-> env
          mutation-post-processing
          (result-action!))))))

(defn with-durable-mutations
  "Augments the given app with support for write-through mutations. RETURNS A NEW APP. You must use this like so:

   ```
   (defonce app (-> (fulcro-app ...)
                  (with-durable-mutations mutation-store tempid-strategy)))
   ```

   * `mutation-store` is an implementation of MutationStore.
   * `tempid-strategy` is an implementation of TempIdStrategy"
  [app mutation-store tempid-strategy]
  (let [app (-> app
              (update :com.fulcrologic.fulcro.application/config merge {::mutation-store  mutation-store
                                          ::tempid-strategy tempid-strategy})
              (with-augmented-result-action)
              (with-durable-transact))]
    (transaction-loop! app)
    app))

(defn is-retry?
  "Returns true if the mutation env represents an environment where the mutation is being retried. Returns false only
   on the initial submission of the mutation"
  [mutation-env]
  (::txn/options mutation-env)
  (boolean (some-> mutation-env ::txn/options ::retry?)))
(def retry? is-retry?)

(defn transact!
  "Similar to comp/transact!, but the mutations in the transaction will first be written to a persistent store (on mutation
   per entry), and then each will be retried until they succeed or are explicitly cancelled. Transactions submitted with
   this function must be safe to run in parallel in any order, and any number of times (the semantic is that they
   will be retried until they succeed *at least once*).

   WARNING: YOU MUST ENSURE ORDER DOES NOT MATTER ON A PER-*MUTATION* BASIS (order cannot be preserved
   without risking deadlock). This means `[(f) (g) (h)]` might run on the server as, for example,
   `[(g)]`, two hours pass `[(f)]`, 5 seconds pass `[(h)]`.

   Success is defined (by default) by your application's `remote-error?`. When there is no detected error the mutation
   will be removed from durable storage and your mutation's `ok-action` will be called. The mutation's `error-action` section
   can also decide that the attempt was \"good enough\" and explicitly cancel further retries with `dm/cancel-mutation!`.

   Your mutation's `action` and `result-action` (or if you use them: `error-action` or `ok-action`) will be called
   *on every attempt*.

   Your mutation's `action` body can use `(dm/retry? env)` to determine if this is the first attempt, and `(dm/attempt env)`
   to find out how many times it has been tried.

   The `result-action` (or `error-action`) sections of your mutation can call `(dm/cancel-mutation! env)` to remove
   the durable mutation and stop retrying it. For example, you might decide that it is an application error that should
   be reported (e.g. for support purposes) via a different (possibly durable) mutation.

   If the remote succeeds (`remote-error?` of your app reports `false`), then the mutation will automatically be
   removed from the persistent storage and your `ok-action` will be triggered.

   The durability of these mutation across application restarts is dependent upon the implementation of the EDN store
   used with this facility.

   NOTES:

   * The optimistic actions of the mutations in the transaction will fire *every time the mutation is tried*.
   * Operations submitted via this `transact!` are essentially considered to be legal to run or repeat at any time.
     This also means that the optimistic actions of the transaction are *not* guaranteed to run in any total order
     compared to any other transactions in Fulcro.
   * If you submit a transaction with more than one mutation it will actually be split into multiple transaction
     submissions (one mutation per transaction).  This is to ensure that retries happen on a mutation granularity,
     since we cannot enforce full-stack transactional semantics.

  WARNING: This function *is exactly equivalent to* `comp/transact!` unless you install support for it on your application.
  See `with-durable-mutations`."
  ([app-ish txn]
   (transact! app-ish txn {::durable? true}))
  ([app-ish txn options]
   (when #?(:clj true :cljs js/goog.DEBUG)
     (when-not (= 1 (count txn))
       (log/warn "Write-through transactions with multiple mutations will be rewritten to submit one per mutation. See https://book.fulcrologic.com/#warn-multiple-mutations-rewritten")))
   (doseq [element txn]
     (when-not (keyword? element)                           ; ignore F2-style follow-on reads
       (rc/transact! app-ish [element] (assoc options ::durable? true))))))
