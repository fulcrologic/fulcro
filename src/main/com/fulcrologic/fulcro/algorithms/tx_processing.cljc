(ns com.fulcrologic.fulcro.algorithms.tx-processing
  (:require
    [com.fulcrologic.fulcro.algorithms.scheduling :refer [schedule!]]
    [com.fulcrologic.fulcro.algorithms.misc :as futil]
    [com.fulcrologic.fulcro.mutations :as m]
    [taoensso.timbre :as log]
    [taoensso.encore :as enc]
    [edn-query-language.core :as eql]
    [clojure.set :as set]
    [com.fulcrologic.fulcro.algorithms.application-helpers :as ah]))

(declare schedule-activation! process-queue! remove-send!)

(defn- app->remotes
  "returns the remotes map from an app"
  [app]
  (-> app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/remotes))

(defn- app->remote-names
  "returns a set of the names of the remotes from an app"
  [app]
  (-> app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/remotes keys set))

(defn extract-parallel
  "Splits the given send queue into two send queues:
  [parallel-items sequential-items]."
  [sends]
  (let [parallel? (fn [{:keys [::options]}]
                    (boolean (or (:parallel? options) (::parallel? options))))
        {parallel   true
         sequential false} (group-by parallel? sends)]
    [(vec parallel) (vec sequential)]))

(defn every-ast?
  "Check if the given `test` predicate is true for an AST node or all the immediate children of an AST tree."
  [ast-node-or-tree test]
  (if (= :root (:type ast-node-or-tree))
    (every? test (:children ast-node-or-tree))
    (test ast-node-or-tree)))

(defn mutation-ast?
  "Returns true if the given AST node or tree represents a mutation or sequence of mutations."
  [ast-node-or-tree]
  (every-ast? ast-node-or-tree #(= :call (:type %))))

(defn query-ast?
  "Returns true if the given AST node or tree represents a mutation or sequence of mutations."
  [ast-node-or-tree]
  (every-ast? ast-node-or-tree #(not= :call (:type %))))

(defn sort-queue-writes-before-reads
  "Sort function on a send queue.  Leaves any active nodes in front, and sorts the remainder of the queue so that writes
  appear before reads, without changing the relative order in blocks of reads/writes."
  [send-queue]
  (let [[active-queue send-queue] (split-with ::active? send-queue)
        id-sequence (mapv (fn [n] (-> n first ::id)) (partition-by ::id send-queue))
        clusters    (group-by ::id (vec send-queue))
        {:keys [reads writes]} (reduce
                                 (fn [result id]
                                   (let [[{:keys [::ast] :as n} & _ :as cluster] (get clusters id)]
                                     (cond
                                       (nil? ast) result
                                       (query-ast? ast) (update result :reads into cluster)
                                       (mutation-ast? ast) (update result :writes into cluster)
                                       :else result)))
                                 {:reads [] :writes []}
                                 id-sequence)
        send-queue  (into [] (concat active-queue writes reads))]
    send-queue))

(defn top-keys
  [{:keys [type key children] :as ast}]
  (if (= :root type)
    (into #{} (map :key) children)
    #{key}))

(defn combine-sends
  "Takes a send queue and returns a map containing a new combined send node that can act as a single network request,
  along with the updated send queue."
  [app remote-name send-queue]
  (let [[active-nodes send-queue] (split-with ::active? send-queue)
        send-queue        (sort-queue-writes-before-reads (vec send-queue))
        id-to-send        (-> send-queue first ::id)
        [to-send to-defer] (split-with #(= id-to-send (::id %)) send-queue)
        tx                (reduce
                            (fn [acc {:keys [::ast]}]
                              (let [tx (if (= :root (:type ast))
                                         (eql/ast->query ast)
                                         [(eql/ast->query ast)])]
                                (into acc tx)))
                            []
                            to-send)
        ast               (eql/query->ast tx)
        combined-node-id  (futil/uuid)
        combined-node-idx 0
        combined-node     {::id             combined-node-id
                           ::idx            combined-node-idx
                           ::ast            ast
                           ::update-handler (fn [{:keys [body] :as combined-result}]
                                              (doseq [{:keys [::ast ::update-handler]} to-send]
                                                (when update-handler
                                                  (update-handler combined-result))))
                           ::result-handler (fn [{:keys [body] :as combined-result}]
                                              (doseq [{:keys [::ast ::result-handler]} to-send]
                                                (let [new-body (select-keys body (top-keys ast))
                                                      result   (assoc combined-result :body new-body)]
                                                  (result-handler result)))
                                              (remove-send! app remote-name combined-node-id combined-node-idx))
                           ::active?        true}]
    (if (seq to-send)
      {::send-node  combined-node
       ::send-queue (into [] (concat active-nodes [combined-node] to-defer))}
      {::send-queue send-queue})))

(defn net-send!
  "Process the send against the user-defined remote. Catches exceptions and calls error handler with status code 500
  if the remote itself throws exceptions."
  [app send-node remote-name]
  (enc/if-let [remote (get (app->remotes app) remote-name)
               transmit! (get remote :transmit!)]
    (try
      (transmit! remote send-node)
      (catch #?(:cljs :default :clj Exception) e
        (log/error e "Send threw an exception!")
        (try
          ((::result-handler send-node) {:status-code      500
                                         :client-exception e})
          (catch #?(:cljs :default :clj Exception) e
            (log/fatal e "Error handler failed to handle exception!")))))
    (do
      (log/error "Transmit was not defined on remote" remote-name)
      ((::result-handler send-node) {:status-code 500
                                     :message     "Transmit missing on remote."}))))



(defn process-send-queues!
  "Process the send queues against the remotes. Updates the send queues on the app and returns the updated send queues."
  [{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app}]
  (let [send-queues     (-> runtime-atom deref ::send-queues)
        remote-names    (app->remote-names app)
        new-send-queues (reduce
                          (fn [new-send-queues remote]
                            (let [send-queue (get send-queues remote [])
                                  [p serial] (extract-parallel send-queue)
                                  front      (first serial)]
                              ;; parallel items are removed from the queues, since they don't block anything
                              (doseq [item p]
                                (net-send! app item remote))
                              ;; sequential items are kept in queue to prevent out-of-order operation
                              (if (::active? front)
                                (assoc new-send-queues remote serial)
                                (let [{:keys [::send-queue ::send-node]} (combine-sends app remote serial)]
                                  (when send-node
                                    (net-send! app send-node remote))
                                  (assoc new-send-queues remote send-queue)))))
                          {}
                          remote-names)]
    (swap! runtime-atom assoc ::send-queues new-send-queues)
    new-send-queues))



(defn tx-node
  ([tx]
   (tx-node tx {}))
  ([tx options]
   (let [ast       (eql/query->ast tx)
         ast-nodes (:children ast)
         elements  (into []
                     (comp
                       (filter (fn txfilt* [n]
                                 (if (= :call (:type n))
                                   true
                                   (do
                                     (log/warn "tx no longer supports (or needs) follow-on reads. See tx" tx)
                                     false))))
                       (map-indexed
                         (fn ->txnode* [idx ast-node]
                           {::idx               idx
                            ::original-ast-node ast-node
                            ::started?          #{}
                            ::complete?         #{}
                            ::results           {}
                            ::dispatch          {}})))
                     ast-nodes)]
     {::id       (futil/uuid)
      ::created  (futil/now)
      ::options  options
      ::tx       tx
      ::elements elements})))

(defn build-env
  ([app tx-node addl]
   (merge addl (build-env app tx-node)))
  ([app {:keys [::options] :as tx-node}]
   (let [{:keys [ref component]} options]
     (cond-> {:state (-> app :com.fulcrologic.fulcro.application/state-atom)
              :app   app}
       options (assoc ::options options)
       ref (assoc :ref ref)
       component (assoc :component component)))))

(defn dispatch-elements
  "Run through the elements on the given tx-node and do the side-effect-free dispatch.  This generates the dispatch map
  of things to do on that node."
  [tx-node env dispatch-fn]
  (let [do-dispatch  (fn run* [env]
                       (try
                         (dispatch-fn env)
                         (catch #?(:clj Exception :cljs :default) e
                           (log/error e "Dispatch of mutation failed with an exception. No dispatch generated.")
                           {})))
        dispatch     (fn dispatch* [{:keys [::original-ast-node] :as ele}]
                       (let [{:keys [type]} original-ast-node
                             env (assoc env :ast original-ast-node)]
                         (cond-> ele
                           (= :call type) (assoc ::dispatch (do-dispatch env)))))
        dispatch-all (fn [eles] (mapv dispatch eles))]
    (update tx-node ::elements dispatch-all)))

(defn activate-submissions!
  "Activate all of the transactions that have been submitted since the last activation. After the items are activated
  a single processing step will run for the active queue."
  [{:keys [:com.fulcrologic.fulcro.application/runtime-atom] :as app}]
  (let [dispatched-nodes (mapv #(dispatch-elements % (build-env app %) m/mutate) (::submission-queue @runtime-atom))]
    (swap! runtime-atom (fn [a]
                          (-> a
                            (update ::active-queue #(reduce conj % dispatched-nodes))
                            (assoc ::submission-queue []))))
    (process-queue! app)))

(defn schedule-activation!
  "Schedule activation of submitted transactions.  The default implementation copies all submitted transactions onto
   the active queue and immediately does an active queue processing step.  If `tm` is not supplied (in ms) it defaults to 10ms."
  ([app tm]
   (schedule! app ::activation-scheduled? activate-submissions! tm))
  ([app]
   (schedule-activation! app 10)))

(defn schedule-queue-processing!
  "Schedule a processing of the active queue, which will advance the active transactions by a step.
   If `tm` is not supplied (in ms) it defaults to 10ms."
  ([app tm]
   (schedule! app ::queue-processing-scheduled? process-queue! tm))
  ([app]
   (schedule-queue-processing! app 10)))

(defn schedule-sends!
  "Schedule actual network activity.  If `tm` is not supplied (in ms) it defaults to 0ms."
  ([app tm]
   (schedule! app ::sends-scheduled? process-send-queues! tm))
  ([app]
   (schedule-sends! app 0)))

(defn advance-actions!
  "Runs any incomplete and non-blocked optimistic operations on a node."
  [app {:keys [::elements] :as node}]
  (let [remotes      (app->remote-names app)
        reduction    (reduce
                       (fn [{:keys [done? new-elements] :as acc} element]
                         (if done?
                           (update acc :new-elements conj element)
                           (let [{:keys [::complete? ::dispatch]} element
                                 {:keys [action]} dispatch
                                 remote-set      (set/intersection remotes (set (keys dispatch)))
                                 exec?           (and action (not (or done? (complete? :action))))
                                 fully-complete? (and (or exec? (complete? :action)) (empty? (set/difference remote-set complete?)))
                                 state-before    (-> app :com.fulcrologic.fulcro.application/state-atom deref)
                                 updated-element (if exec? (-> element
                                                             (assoc ::state-before-action state-before)
                                                             (update ::complete? conj :action)) element)
                                 done?           (not fully-complete?)
                                 new-acc         {:done?        done?
                                                  :new-elements (conj new-elements updated-element)}
                                 env             (build-env app node {:ast (:original-ast-node element)})]
                             (when exec?
                               (try
                                 (when action
                                   (action env))
                                 (catch #?(:cljs :default :clj Exception) e
                                   (log/error e "Failure dispatching optimistic action for AST node" element "of transaction node" node))))
                             new-acc)))
                       {:done? false :new-elements []}
                       elements)
        new-elements (:new-elements reduction)]
    (assoc node ::elements new-elements)))

(defn run-actions!
  [app {:keys [::elements] :as node}]
  (let [new-elements (reduce
                       (fn [new-elements element]
                         (let [{:keys [::complete? ::dispatch]} element
                               {:keys [action]} dispatch
                               exec?        (and action (not (complete? :action)))
                               state-before (-> app :com.fulcrologic.fulcro.application/state-atom deref)
                               updated-node (if exec? (-> element
                                                        (assoc ::state-before-action state-before)
                                                        (update ::complete? conj :action)) element)
                               new-acc      (conj new-elements updated-node)
                               env          (build-env app node {:ast (:original-ast-node element)})]
                           (when exec?
                             (try
                               (action env)
                               (catch #?(:cljs :default :clj Exception) e
                                 (log/error e "Failure dispatching optimistic action for AST node" element "of transaction node" node))))
                           new-acc))
                       []
                       elements)]
    (assoc node ::elements new-elements)))

(defn fully-complete?
  [app {:keys [::elements] :as tx-node}]
  (let [element-complete? (fn [{:keys [::dispatch ::complete?]}]
                            (let [remotes     (app->remote-names app)
                                  active-keys (set/union #{:action} remotes)
                                  desired-set (set/intersection active-keys (set (keys dispatch)))]
                              (empty? (set/difference desired-set complete?))))]
    (every? element-complete? elements)))

(defn remove-send!
  "Removes the send node (if present) from the send queue on the given remote."
  [{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app} remote txn-id ele-idx]
  (let [{:keys [::send-queues]} @runtime-atom
        queue (get send-queues remote)
        queue (filterv (fn [{:keys [::id ::idx]}]
                         (not (and (= txn-id id) (= ele-idx idx)))) queue)]
    (swap! runtime-atom assoc-in [::send-queues remote] queue)))

(defn record-result!
  "Record a network result on the given txn/element.
   If result-key is given it is used, otherwise defaults to ::results. Also removes the network send from the send
   queue so that remaining items can proceed, and schedules send processing."
  ([{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app} txn-id ele-idx remote result result-key]
   (let [active-queue (::active-queue @runtime-atom)
         txn-idx      (reduce
                        (fn [idx {:keys [::id]}]
                          (if (= id txn-id)
                            (reduced idx)
                            (inc idx)))
                        0
                        active-queue)
         not-found?   (or (>= txn-idx (count active-queue)) (not= txn-id (::id (get active-queue txn-idx))))]
     (if not-found?
       (log/error "Network result for" remote "does not have a valid node on the active queue!")
       (swap! runtime-atom assoc-in [::active-queue txn-idx ::elements ele-idx result-key remote] result))))
  ([app txn-id ele-idx remote result]
   (record-result! app txn-id ele-idx remote result ::results)))

(defn add-send!
  "Generate a new send node and add it to the appropriate send queue. Returns the new send node."
  [{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app} {:keys [::id ::options] :as tx-node} ele-idx remote]
  (let [handler        (fn result-handler* [result]
                         (record-result! app id ele-idx remote result)
                         (remove-send! app remote id ele-idx)
                         (schedule-sends! app 1)
                         (schedule-queue-processing! app 0))
        update-handler (fn progress-handler* [result]
                         (record-result! app id ele-idx remote result ::progress)
                         (schedule-queue-processing! app 0))
        {:keys [::dispatch ::original-ast-node ::state-before-action]} (get-in tx-node [::elements ele-idx])
        env            (build-env app tx-node {:ast                 original-ast-node
                                               :state-before-action state-before-action})
        remote-fn      (get dispatch remote)
        ast            (when remote-fn (remote-fn env))
        ast            (cond
                         (nil? remote-fn) nil
                         (true? ast) original-ast-node
                         :else ast)
        send-node      {::id             id
                        ::idx            ele-idx
                        ::ast            ast
                        ::options        options
                        ::active?        false
                        ::result-handler handler
                        ::update-handler update-handler}]
    (if ast
      (do
        (swap! runtime-atom update-in [::send-queues remote] (fnil conj []) send-node)
        send-node)
      (do
        (handler {:status-code 500 :body "The remote AST was empty!"})
        nil))))

(defn queue-element-sends!
  "Queue all (unqueued) remote actions for the given element.  Returns the (possibly updated) node."
  [app tx-node {:keys [::idx ::dispatch ::started?]}]
  (let [remotes     (set/intersection (set (keys dispatch)) (app->remote-names app))
        to-dispatch (set/difference remotes started?)]
    (reduce
      (fn [node remote]
        (if (contains? (get-in node [::elements idx ::started?] #{}) remote)
          node
          (do
            (add-send! app node idx remote)
            (update-in node [::elements idx ::started?] conj remote))))
      tx-node
      to-dispatch)))

(defn idle-node?
  "Returns true if the given node has no active network operations."
  [{:keys [::elements] :as tx-node}]
  (every?
    (fn idle?* [{:keys [::started? ::complete?]}]
      (let [in-progress (set/difference started? complete?)]
        (empty? in-progress)))
    elements))

(defn element-with-work
  "Returns a txnode element iff it has remaining (remote) work that has not been queued. Returns nil if there
   is no such element.

  remote-names is the set of legal remote names."
  [remote-names {:keys [::dispatch ::started?] :as element}]
  (let [todo      (set/intersection remote-names (set (keys dispatch)))
        remaining (set/difference todo started?)]
    (when (seq remaining)
      element)))

(defn queue-next-send!
  "Assumes tx-node is to be processed pessimistically. Queues the next send if the node is currently idle
  on the network and there are any sends left to do. Adds to the send queue, and returns the updated
  tx-node."
  [app {:keys [::elements] :as tx-node}]
  (if (idle-node? tx-node)
    (let [remotes   (app->remote-names app)
          with-work (partial element-with-work remotes)
          element   (some with-work elements)]
      (queue-element-sends! app tx-node element))
    tx-node))

(defn queue-sends!
  "Finds any item(s) on the given node that are ready to be placed on the network queues and adds them. Non-optimistic
  multi-element nodes will only queue one remote operation at a time."
  [app {:keys [::options ::elements] :as tx-node}]
  (let [optimistic? (boolean (:optimistic? options))]
    (schedule-sends! app 0)
    (if optimistic?
      (reduce
        (fn [node element]
          (queue-element-sends! app node element))
        tx-node
        elements)
      (queue-next-send! app tx-node))))

(defn dispatch-result!
  "Figure out the dispatch routine to trigger for the given network result.  If it exists, send the result
  to it.

  Returns the tx-element with the remote marked complete."
  [app tx-node {:keys [::results ::dispatch ::original-ast-node] :as tx-element} remote]
  (schedule-queue-processing! app 0)
  (let [result  (get results remote)
        handler (get dispatch :result-action)]
    (when handler
      (let [env (assoc (build-env app tx-node)
                  :dispatch dispatch
                  :result result)]
        (try
          (handler env)
          (catch #?(:cljs :default :clj Exception) e
            (log/error e "The result-action mutation handler for mutation" (:dispatch-key original-ast-node) "threw an exception."))))))
  (update tx-element ::complete? conj remote))

(defn distribute-element-results!
  "Distribute results and mark the remotes for those elements as complete."
  [app tx-node {:keys [::results ::complete?] :as tx-element}]
  (reduce
    (fn [new-element remote]
      (if (complete? remote)
        new-element
        (dispatch-result! app tx-node new-element remote)))
    tx-element
    (keys results)))

(defn distribute-results!
  "Walk all elements of the tx-node and call result dispatch handlers for any results that have
  not been distributed."
  [app {:keys [::elements] :as tx-node}]
  (assoc tx-node
    ::elements (mapv
                 (fn [element] (distribute-element-results! app tx-node element))
                 elements)))

(defn update-progress!
  "Report all progress items to any registered progress dispatch and clear them from the tx-node.
  Returns the updated tx-node."
  [app {:keys [::elements] :as tx-node}]
  (let [get-env (fn get-env* [remote progress] (build-env app tx-node {:remote remote :progress progress}))]
    (reduce
      (fn [node {:keys [::idx ::progress ::dispatch] :as element}]
        (doseq [[remote value] progress]
          (let [env    (get-env remote value)
                action (get dispatch :progress-action)]
            (when action
              (try
                (action env)
                (catch #?(:cljs :default :clj Exception) e
                  (log/error e "Progress action threw an exception for txn element" element))))))
        (update-in node [::elements idx] dissoc ::progress))
      tx-node
      elements)))

(defn process-tx-node!
  [app {:keys [::options] :as tx-node}]
  (let [optimistic? (boolean (:optimistic? options))]
    (if (fully-complete? app tx-node)
      nil
      (-> tx-node
        (cond->>
          optimistic? (run-actions! app)
          (not optimistic?) (advance-actions! app))
        (->>
          (queue-sends! app)
          (update-progress! app)
          (distribute-results! app))))))

(defn process-queue!
  "Run through the active queue and do a processing step."
  [{:keys [com.fulcrologic.fulcro.application/runtime-atom] :as app}]
  (let [new-queue (reduce
                    (fn *pstep [new-queue n]
                      (if-let [new-node (process-tx-node! app n)]
                        (conj new-queue new-node)
                        new-queue))
                    []
                    (::active-queue @runtime-atom))
        render!   (ah/app-algorithm app :schedule-render!)]
    (swap! runtime-atom assoc ::active-queue new-queue)
    (render! app)
    nil))
