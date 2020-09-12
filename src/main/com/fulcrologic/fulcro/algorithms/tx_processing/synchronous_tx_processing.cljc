(ns com.fulcrologic.fulcro.algorithms.tx-processing.synchronous-tx-processing
  "A transaction processing system that does as much synchronously as possible, and removes various elements
   of complexity that were inherited from Fulcro 2 in the standard tx processing.

   See `with-synchronous-transactions` for how to install it.

   This tx processing system does as much work synchronously as possible, though it does try to preserve the
   call-order *semantics* of the standard transaction processing: That is to say that if the optimistic action
   of a transaction submits a new transaction then that new submission will run *after* the current already-in-progress
   transaction has finished processing:

   ```
   (defmutation g [_]
     (action [{:keys [state]}] (swap! state ...))
     (ok-action [{:keys [app]}] (transact! app [(h)]))
     (remote [_] true))

   (defmutation f [_]
     (action [{:keys [state app]}]
       (swap! state ...)
       (transact! app [(g)])))

   ...
   (dom/a {:onClick (fn []
                      (transact! this [(f {:x 1})])
                      (transact! this [(f {:x 2})])
                      (transact! this [(f {:x 3})])))
   ```

   A user clicking the above link with std processing could see any of the following:

   ```
   f,f,f,g,g,g,h,h,h
   f,f,f,g,h,g,g,h,h
   f,f,f,g,g,h,g,h,h
   etc.
   ```

   In sync tx processing, you would more likely see:

   ```
   f,g,f,g,f,g,h,h,h
   ```

   because there is *no guarantee* in Fulcro's semantics about the space between two calls to `transact!`. If your
   application relies on the groupings that happen with the standard tx processing (submissions while holding a thread
   go into the queue first) then your application may break when you switch to sync processing.

   Note that transactions *are* treated as atomically as possible. So, if you want a specific grouping you should submit
   it as a single tx:

   ```
   (transact! [(f) (g)])
   (transact! [(f) (g)])
   ```

   is guaranteed to do `f,g,f,g`, and never `f,f,g,g`, though it is still possible to see `f,g,h,f,g,h`.

   This sync transaction processing system allows you to push most (if not all) behavior of even nested transactions into a single
   synchronous operation. This will lead to significant improvements in the snappiness of the UI for optimistic operation
   and should also reduce over-rendering (multiple calls to render due to multiple async operations).

   If your remote is mocked as a synchronous operation, then you can also leverage this tx processor to enable
   completely synchronous testing of your headless Fulcro application.

   WARNING: This tx processing system does *not* support:

   * `ptransact!`: Pessimistic transactions are a legacy feature of Fulcro 2 that is no longer necessary. New
   applications should not use the feature, and this sync tx processing system does not support it. The call
   will succeed, but will behave as a normal `transact!`.
   "
  #?(:cljs (:require-macros [com.fulcrologic.fulcro.algorithms.tx-processing.synchronous-tx-processing :refer [in-transaction]]))
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.specs]
    [com.fulcrologic.fulcro.inspect.inspect-client :refer [ido ilet]]
    com.fulcrologic.fulcro.specs
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(defonce apps-in-tx (atom {}))

(declare run-queue! available-work?)

#?(:clj
   (defmacro in-transaction [app-sym & body]
     `(let [id# (:com.fulcrologic.fulcro.application/id ~app-sym)]
        (swap! apps-in-tx update id# (fnil inc 0))
        (try
          ~@body
          (finally
            (swap! apps-in-tx update id# dec))))))

(defn top-level?
  "Returns true if the current thread is running non-nested transaction processing code."
  [{:com.fulcrologic.fulcro.application/keys [id]}]
  (= (-> apps-in-tx deref (get id 0)) 0))

(defn swap-submission-queue! [app & args] (apply swap! (get-in app [::config ::submission-queue]) args))
(defn reset-submission-queue! [app v] (reset! (get-in app [::config ::submission-queue]) v))
(defn submission-queue [app] @(get-in app [::config ::submission-queue]))
(defn swap-post-processing-steps! [app & args] (apply swap! (get-in app [::config ::post-processing-steps]) args))
(defn reset-post-processing-steps! [app v] (reset! (get-in app [::config ::post-processing-steps]) v))
(defn post-processing-steps [app] @(get-in app [::config ::post-processing-steps]))
(defn swap-active-queue! [app & args] (apply swap! (get-in app [::config ::active-queue]) args))
(defn reset-active-queue! [app v] (reset! (get-in app [::config ::active-queue]) v))
(defn active-queue [app] @(get-in app [::config ::active-queue]))
(defn swap-send-queue! [app remote & args] (apply swap! (get-in app [::config ::send-queues remote]) args))
(defn reset-send-queue! [app remote v] (reset! (get-in app [::config ::send-queues remote]) v))
(defn send-queue [app remote] @(get-in app [::config ::send-queues remote]))

(defn run-after!
  "Add `f` as a function that will run after the current transaction has been fully processed."
  [app f]
  (swap-post-processing-steps! app (fnil conj []) f))

(defn post-processing?
  "Is there post processing to do?"
  [app]
  (boolean (seq (post-processing-steps app))))

(defn do-post-processing!
  "Runs the queued post processing steps until the post-processing queue is empty."
  [app]
  (loop [steps (post-processing-steps app)]
    (reset-post-processing-steps! app [])
    (doseq [f steps]
      (try
        (f)
        (catch #?(:clj Exception :cljs :default) e
          (log/error e "Post processing step failed."))))
    (when-let [next-steps (seq (post-processing-steps app))]
      (recur next-steps))))

(defn in-transaction?
  "Returns true if the current thread is in the midst of running the optimistic actions of a new transaction."
  [{:com.fulcrologic.fulcro.application/keys [id] :as app}]
  (not= 0 (get @apps-in-tx id 0)))

(defn release-post-render-tasks!
  "Should be called after the application renders to ensure that transactions blocked until the next render become
   unblocked. Schedules an activation."
  [app]
  (swap-submission-queue! app (fn [queue] (mapv (fn [node] (update node ::txn/options dissoc :after-render?)) queue))))

(defn dispatch-result!
  "Figure out the dispatch routine to trigger for the given network result.  If it exists, send the result
  to it.

  Returns the tx-element with the remote marked complete."
  [app tx-node {::txn/keys [results dispatch desired-ast-nodes transmitted-ast-nodes original-ast-node] :as tx-element} remote]
  (let [result  (get results remote)
        handler (get dispatch :result-action)]
    (when handler
      (let [env (txn/build-env app tx-node {:dispatch        dispatch
                                            :transacted-ast  original-ast-node
                                            :mutation-ast    (get desired-ast-nodes remote)
                                            :transmitted-ast (get transmitted-ast-nodes remote)
                                            :result          result})]
        (try
          (handler env)
          (catch #?(:cljs :default :clj Exception) e
            (log/error e "The result-action mutation handler for mutation" (:dispatch-key original-ast-node) "threw an exception."))))))
  (update tx-element ::txn/complete? conj remote))

(defn distribute-element-results!
  "Distribute results and mark the remotes for those elements as complete."
  [app tx-node {:keys [::txn/results ::txn/complete?] :as tx-element}]
  (reduce
    (fn [new-element remote]
      (if (complete? remote)
        new-element
        (dispatch-result! app tx-node new-element remote)))
    tx-element
    (keys results)))

(defn- node-index [queue txn-id]
  (let [n   (count queue)
        idx (reduce
              (fn [idx {:keys [::txn/id]}]
                (if (= id txn-id)
                  (reduced idx)
                  (inc idx)))
              0
              queue)]
    (when (< idx n)
      idx)))

(defn distribute-results!
  "Side-effects against the app state to distribute the result for txn-id element at ele-idx. This will call the result
   handler and mark that remote as complete."
  [app txn-id ele-idx]
  (let [active-queue (active-queue app)
        idx          (node-index active-queue txn-id)
        tx-node      (get active-queue idx)]
    (swap-active-queue! app update-in [idx ::txn/elements ele-idx]
      #(distribute-element-results! app tx-node %))))

(defn record-result!
  "Deal with a network result on the given txn/element."
  ([app txn-id ele-idx remote result result-key]
   (let [active-queue (active-queue app)
         txn-idx      (node-index active-queue txn-id)
         not-found?   (or (>= txn-idx (count active-queue)) (not= txn-id (::txn/id (get active-queue txn-idx))))]
     (if not-found?
       (log/error "Network result for" remote "does not have a valid node on the active queue!")
       (do
         (swap-active-queue! app assoc-in [txn-idx ::txn/elements ele-idx result-key remote] result)
         (distribute-results! app txn-id ele-idx)))))
  ([app txn-id ele-idx remote result]
   (record-result! app txn-id ele-idx remote result ::txn/results)))

(defn remove-send!
  "Removes the send node (if present) from the send queue on the given remote."
  [app remote txn-id ele-idx]
  (swap-send-queue! app remote (fn [old-queue]
                                 (filterv (fn [{::txn/keys [id idx]}]
                                            (not (and (= txn-id id) (= ele-idx idx)))) old-queue))))

(defn add-send!
  "Generate a new send node and add it to the appropriate send queue."
  [app {::txn/keys [id options] :as tx-node} ele-idx remote]
  (let [update-handler (fn progress-handler* [result]
                         (in-transaction app
                           (record-result! app id ele-idx remote result ::txn/progress)
                           (run-queue! app {})))
        ast            (get-in tx-node [::txn/elements ele-idx ::txn/transmitted-ast-nodes remote])
        handler        (fn result-handler* [result]
                         (in-transaction app
                           (record-result! app id ele-idx remote result)
                           (remove-send! app remote id ele-idx)
                           (run-queue! app {})))
        send-node      {::txn/id             id
                        ::txn/idx            ele-idx
                        ::txn/ast            ast
                        ::txn/options        options
                        ::txn/active?        false
                        ::txn/result-handler handler
                        ::txn/update-handler update-handler}]
    (if ast
      (swap-send-queue! app remote (fnil conj []) send-node)
      (handler {:status-code 200 :body {}}))
    nil))

(defn queue-element-sends!
  "Queue all (unqueued) remote actions for the given element.  Returns the (possibly updated) node."
  [app tx-node {::txn/keys [idx dispatch started?]}]
  (let [remotes     (set/intersection (set (keys dispatch)) (txn/app->remote-names app))
        to-dispatch (set/difference remotes started?)]
    (reduce
      (fn [node remote]
        (if (contains? (get-in node [::txn/elements idx ::txn/started?] #{}) remote)
          node
          (let [updated-node (-> node
                               (update-in [::txn/elements idx] (fn [tx-element] (txn/compute-desired-ast-node app remote node tx-element)))
                               (update-in [::txn/elements idx ::txn/started?] conj remote))]
            (add-send! app updated-node idx remote)
            updated-node)))
      tx-node
      to-dispatch)))

(defn queue-sends!
  "Finds any item(s) on the given node that are ready to be placed on the network queues and adds them. Non-optimistic
  multi-element nodes will only queue one remote operation at a time."
  [app {:keys [::txn/options ::txn/elements] :as tx-node}]
  (reduce
    (fn [node element]
      (queue-element-sends! app node element))
    tx-node
    elements))

(defn process-tx-node!
  [app {:keys [::txn/options] :as tx-node}]
  (if (txn/fully-complete? app tx-node)
    nil
    (->> tx-node
      (txn/run-actions! app)
      (queue-sends! app)
      (txn/update-progress! app))))

(defn process-send-queues!
  "Process the send queues against the remotes, which will cause idle remotes with queued work to issue network requests."
  [app]
  (let [remote-names (txn/app->remote-names app)
        operations   (atom [])]
    (doseq [remote remote-names]
      (let [send-queue (send-queue app remote)
            [p serial] (txn/extract-parallel send-queue)
            front      (first serial)]
        ;; parallel items are removed from the queues, since they don't block anything
        (doseq [item p]
          (swap! operations conj #(txn/net-send! app item remote)))
        ;; sequential items are kept in queue to prevent out-of-order operation
        (if (::active? front)
          (reset-send-queue! app remote serial)
          (let [{::txn/keys [send-queue send-node]} (txn/combine-sends app remote serial)]
            (when send-node
              (swap! operations conj #(txn/net-send! app send-node remote)))
            (reset-send-queue! app remote send-queue)))))
    ;; Actual net sends are done after we set the queues, in case the remote behave synchronously and immediately gives
    ;; results (like errors). Otherwise, nested send queue updates in those handlers could confuse our notion of what's going on.
    (doseq [op @operations]
      (op))))

(defn process-queue!
  "Run through the active queue and do a processing step."
  [{:com.fulcrologic.fulcro.application/keys [state-atom runtime-atom] :as app}]
  (let [old-queue        (active-queue app)
        new-queue        (reduce
                           (fn *pstep [new-queue n]
                             (if-let [new-node (process-tx-node! app n)]
                               (conj new-queue new-node)
                               new-queue))
                           []
                           old-queue)
        accumulate       (fn [r items] (into (set r) items))
        remotes          (txn/app->remote-names app)
        explicit-refresh (txn/requested-refreshes app new-queue)
        remotes-active?  (txn/active-remotes new-queue remotes)]
    (when (not= old-queue (active-queue app))
      (log/error "Old queue changed!"))
    (swap! state-atom assoc :com.fulcrologic.fulcro.application/active-remotes remotes-active?)
    (reset-active-queue! app new-queue)
    (when (seq explicit-refresh)
      (swap! runtime-atom update :com.fulcrologic.fulcro.application/to-refresh accumulate explicit-refresh))
    (process-send-queues! app)
    nil))

(defn available-work?
  "Returns true if the submission queue has work on it that can proceed without any restrictions."
  [app]
  (let [{ready false} (group-by (comp boolean :after-render? ::txn/options) (submission-queue app))]
    (boolean (seq ready))))

(defn activate-submissions!
  "Activate all of the transactions that have been submitted since the last activation. After the items are activated
  a single processing step will run for the active queue.

  Activation can be blocked by the tx-node options for things like waiting for the next render frame."
  [app]
  (let [{blocked true ready false} (group-by (comp boolean :after-render? ::txn/options) (submission-queue app))
        _                (reset-submission-queue! app (vec blocked))
        dispatched-nodes (mapv #(txn/dispatch-elements % (txn/build-env app %) m/mutate) ready)]
    (swap-active-queue! app (fnil into []) dispatched-nodes)
    (process-queue! app)))

(defn run-all-immediate-work!
  "Runs the submission queue. If the submission queue's optimistic actions submit more to the submission queue, then those
   are processed as well until the submission queue remains empty. This can start network requests."
  [app]
  (try
    (activate-submissions! app)
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Error processing tx queue!"))))

(defn run-queue! [app {:keys [component synchronous?] :as options}]
  (loop []
    (run-all-immediate-work! app)
    (when (available-work? app)
      (recur)))
  (if (and synchronous? component)
    (comp/refresh-component! component)
    (when-let [render! (ah/app-algorithm app :render!)]
      (render! app options)))
  (release-post-render-tasks! app)
  (when (available-work? app)
    (recur app {})))

(defn sync-tx! [& args] (throw (ex-info "BREAKING CHANGE. Please use `with-synchronous-transaction` to add sync transaction support to your Fulcro app" {})))

(defn submit-sync-tx!
  ([app tx]
   (submit-sync-tx! app tx {}))
  ([{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app} tx options]
   (let [{:keys [refresh only-refresh ref] :as options} options
         follow-on-reads (into #{} (filter #(or (keyword? %) (eql/ident? %)) tx))
         node            (txn/tx-node tx options)
         accumulate      (fn [r items] (into (set r) items))
         refresh         (cond-> (set refresh)
                           (seq follow-on-reads) (into follow-on-reads)
                           ref (conj ref))]
     (swap-submission-queue! app (fnil conj []) node)
     (swap! runtime-atom (fn [s] (cond-> s
                                   ;; refresh sets are cumulative because rendering is debounced
                                   (seq refresh) (update :com.fulcrologic.fulcro.application/to-refresh accumulate refresh)
                                   (seq only-refresh) (update :com.fulcrologic.fulcro.application/only-refresh accumulate only-refresh))))
     (when-not (in-transaction? app)
       (in-transaction app
         (run-queue! app options)))
     (::txn/id node))))

(def abort!
  "[app abort-id]

   Implementation of abort when using this tx processing"
  txn/abort!)

(defn with-synchronous-transactions
  "Installs synchronous transaction processing on a fulcro application.

  ```
  (defonce app (stx/with-synchronous-transactions
                 (app/fulcro-app {...})))
  ```

  This plug-in attempts to do as much work as possible synchronously, including the processing of \"remotes\" that
  can behave synchronously. This processing system
  preserves transactional ordering semantics for nested submissions, but cannot guarantee that the overall sequence of
  operations will exactly match what you'd see if using the standard tx processing.

  The options map you can pass to `transact!` supports most of the same things as the standard tx processing, with the significant exception of
  `:optimistic? false` (pessimistic transactions). It also *always* assumes synchronous operation, thought the
  `synchronous?` option (if used) does imply that only the current component should be refreshed in the UI.

  - `:ref` - ident. The component ident to include in the transaction env.
  - `:component` - React element. The instance of the component that should appear in the transaction env.
  - `:synchronous?` - When true, causes the rendering to only refresh the calling component (if possible), since the implication
  is for fast-as-possible refresh semantics, even though this tx processing is already sync.
  - `:refresh` - A hint. Vector containing idents (of components) and keywords (of props). Things that have changed and should be re-rendered
    on screen. Only necessary when the underlying rendering algorithm won't auto-detect, such as when UI is derived from the
    state of other components or outside of the directly queried props. Interpretation depends on the renderer selected:
    The ident-optimized render treats these as \"extras\".
  - `:only-refresh` - A hint. Vector of idents/keywords.  If the underlying configured rendering algorithm supports it: The
    components using these are the *only* things that will be refreshed in the UI, and they may be refreshed immediately on
    `transact!`. This can be used to avoid the overhead of looking for stale data when you know exactly what
    you want to refresh on screen as an extra optimization. Idents are *not* checked against queries.

  If the `options` include `:ref` (which comp/transact! sets), then it will be auto-included on the `:refresh` list.

  Returns the transaction ID of the submitted transaction.
  "
  [app]
  (let [remotes     (-> app :com.fulcrologic.fulcro.application/runtime-atom deref
                      :com.fulcrologic.fulcro.application/remotes keys)
        send-queues (zipmap remotes (repeatedly #(atom [])))]
    (-> app
      (update :com.fulcrologic.fulcro.application/algorithms assoc
        :com.fulcrologic.fulcro.algorithm/tx! submit-sync-tx!
        :com.fulcrologic.fulcro.algorithm/abort! abort!)
      (assoc ::config {::submission-queue      (atom [])
                       ::post-processing-steps (atom [])
                       ::active-queue          (atom [])
                       ::send-queues           send-queues}))))