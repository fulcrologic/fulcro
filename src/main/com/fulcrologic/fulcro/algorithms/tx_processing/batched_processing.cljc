(ns com.fulcrologic.fulcro.algorithms.tx-processing.batched-processing
  "A pluggable transaction processing system that attempts to batch reads. Requires that the server be modified
   to support sequences of transactions."
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as futil]
    [com.fulcrologic.fulcro.algorithms.scheduling :refer [schedule!]]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.specs]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect :refer [ido ilet]]
    [com.fulcrologic.guardrails.core :refer [>defn => ? |]]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(declare schedule-activation! process-queue!)

(defn remove-send!
  "Removes the send node (if present) from the send queue on the given remote."
  [{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app} remote txn-id ele-idx]
  (let [{:keys [::txn/send-queues]} @runtime-atom
        old-queue (get send-queues remote)
        queue     (filterv (fn [{:keys [::txn/id ::txn/idx]}]
                             (not (and (= txn-id id) (= ele-idx idx)))) old-queue)]
    (swap! runtime-atom assoc-in [::txn/send-queues remote] queue)))

(def ^:dynamic *remove-send* remove-send!)

(defn app->remotes
  "Returns the remotes map from an app"
  [app]
  [:com.fulcrologic.fulcro.application/app => :com.fulcrologic.fulcro.application/remotes]
  (-> app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/remotes))

(defn app->remote-names
  "Returns a set of the names of the remotes from an app"
  [app]
  [:com.fulcrologic.fulcro.application/app => :com.fulcrologic.fulcro.application/remote-names]
  (-> app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/remotes keys set))

(defn extract-parallel
  "Splits the given send queue into two send queues:
  [parallel-items sequential-items]."
  [sends]
  [(s/coll-of ::txn/send-node :kind vector?) => (s/cat :p ::txn/send-queue :rest ::txn/send-queue)]
  (let [parallel? (fn [{:keys [::txn/options]}]
                    (boolean (or (:parallel? options) (::txn/parallel? options))))
        {parallel   true
         sequential false} (group-by parallel? sends)]
    [(vec parallel) (vec sequential)]))

(defn every-ast?
  "Check if the given `test` predicate is true for an AST node or for all the immediate children of an AST tree."
  [ast-node-or-tree test]
  [::txn/ast fn? => boolean?]
  (if (= :root (:type ast-node-or-tree))
    (every? test (:children ast-node-or-tree))
    (test ast-node-or-tree)))

(defn mutation-ast?
  "Returns true if the given AST node or tree represents a mutation or sequence of mutations."
  [ast-node-or-tree]
  [::txn/ast => boolean?]
  (every-ast? ast-node-or-tree #(= :call (:type %))))

(defn query-ast?
  "Returns true if the given AST node or tree represents a mutation or sequence of mutations."
  [ast-node-or-tree]
  [::txn/ast => boolean?]
  (every-ast? ast-node-or-tree #(not= :call (:type %))))

(defn sort-queue-writes-before-reads
  "Sort function on a send queue. Leaves any active nodes in front, and sorts the remainder of the queue so that writes
  appear before reads, without changing the relative order in blocks of reads/writes."
  [send-queue]
  [::txn/send-queue => ::txn/send-queue]
  (let [[active-queue send-queue] (split-with ::txn/active? send-queue)
        id-sequence (mapv (fn [n] (-> n first ::txn/id)) (partition-by ::txn/id send-queue))
        clusters    (group-by ::txn/id (vec send-queue))
        {:keys [reads writes]} (reduce
                                 (fn [result id]
                                   (let [[{:keys [::txn/ast] :as n} & _ :as cluster] (get clusters id)]
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
  [::txn/ast => (s/coll-of :edn-query-language.ast/key)]
  (if (= :root type)
    (into #{} (map :key) children)
    #{key}))

(defn create-combined-node [app remote-name [leader :as to-send]]
  (let [tx                (reduce
                            (fn [acc {:keys [::txn/ast]}]
                              (let [tx (futil/ast->query ast)]
                                (into acc tx)))
                            []
                            to-send)
        ast               (eql/query->ast tx)
        options           (::txn/options leader)
        combined-node-id  (tempid/uuid)
        combined-node-idx 0
        remove!           *remove-send*
        combined-node     {::txn/id             combined-node-id
                           ::txn/idx            combined-node-idx
                           ::txn/ast            ast
                           ::txn/options        options
                           ::txn/update-handler (fn [combined-result]
                                                  (doseq [{::txn/keys [update-handler]} to-send]
                                                    (when update-handler
                                                      (update-handler combined-result))))
                           ::txn/result-handler (fn [{:keys [body] :as combined-result}]
                                                  (remove! app remote-name combined-node-id combined-node-idx)
                                                  (doseq [{::txn/keys [ast result-handler]} to-send]
                                                    (let [new-body (if (map? body)
                                                                     (select-keys body (top-keys ast))
                                                                     body)
                                                          result   (assoc combined-result :body new-body)]
                                                      (inspect/ilet [{:keys [status-code body]} result]
                                                        (if (= 200 status-code)
                                                          (inspect/send-finished! app remote-name combined-node-id body)
                                                          (inspect/send-failed! app combined-node-id (str status-code))))
                                                      (result-handler result))))
                           ::txn/active?        true}]
    combined-node))

(defn- node-abort-id [n]
  (or
    (some-> n ::txn/options :abort-id)
    (some-> n ::txn/options ::txn/abort-id)))

(defn mutation-node?
  "Returns true if the given transaction node represents one or more mutations."
  [{::txn/keys [ast]}]
  (and
    (map? ast)
    (or
      (= :call (:type ast))
      (boolean (some #(= :call (:type %)) (:children ast))))))

(>defn batchable?
  "Returns true when ALL of the ::txn/send-node entries in `to-send` can be batched into an existing batch."
  [to-send leader]
  [(s/coll-of ::txn/send-node) (? ::txn/send-node) => boolean?]
  (let [abort-id (node-abort-id leader)]
    (boolean
      (and
        (seq to-send)
        (every? (fn [n] (= abort-id (node-abort-id n))) to-send)
        (not (some mutation-node? to-send))))))

(defn batch-sends
  [app remote-name send-queue]
  [:com.fulcrologic.fulcro.application/app :com.fulcrologic.fulcro.application/remote-name ::txn/send-queue => (s/keys :opt [::txn/send-node] :req [::txn/send-queue])]
  (let [prime-leader   (first send-queue)
        {prime-candidates true
         to-defer         false} (group-by (fn [n] (batchable? [prime-leader] n)) send-queue)
        {:keys [batch remainder]} (loop [result {:batch     []
                                                 :remainder to-defer}
                                         queue  prime-candidates]
                                    (let [leader (first queue)
                                          [to-send remainder] (split-with #(= (::txn/id leader) (::txn/id %)) queue)]
                                      (let [combined-node (create-combined-node app remote-name to-send)
                                            new-result    (update result :batch conj combined-node)]
                                        (if (seq remainder)
                                          (recur new-result remainder)
                                          new-result))))
        batch-node-id  (tempid/uuid)
        batch-node-idx 0
        remove!        *remove-send*
        batch-node     {::txn/id             batch-node-id
                        ::txn/idx            batch-node-idx
                        ::txn/raw-body       {:queries (mapv
                                                         (fn [{::txn/keys [ast]}] (futil/ast->query ast))
                                                         batch)}
                        ::txn/options        (or (::txn/options prime-leader) {})
                        ::txn/batch          batch
                        ::txn/update-handler (fn [combined-result]
                                               (loop [{::txn/keys [update-handler]} (first batch)
                                                      more-batch  (next batch)
                                                      result      (first combined-result)
                                                      more-result (next combined-result)]
                                                 (when update-handler
                                                   (update-handler result))
                                                 (when (and (seq more-batch) (seq more-result))
                                                   (recur (first more-batch) (next more-batch)
                                                     (first more-result) (next more-result)))))
                        ::txn/result-handler (fn [{:keys [body] :as batch-result}]
                                               (remove! app remote-name batch-node-id batch-node-idx)
                                               (loop [{::txn/keys [result-handler]} (first batch)
                                                      more-batch  (next batch)
                                                      result      (first body)
                                                      more-result (next body)]
                                                 (result-handler (assoc batch-result :body result))
                                                 (when (and (seq more-batch) (seq more-result))
                                                   (recur (first more-batch) (next more-batch)
                                                     (first more-result) (next more-result)))))
                        ::txn/active?        true}]
    (when (> (count batch) 1) (log/debug "Batched:" (count batch)))
    {::txn/send-node  batch-node
     ::txn/send-queue (into [batch-node] remainder)}))

(defn combine-sends
  "Takes a send queue and returns a map containing a new combined send node that can act as a single network request,
  along with the updated send queue."
  [app remote-name send-queue]
  [:com.fulcrologic.fulcro.application/app :com.fulcrologic.fulcro.application/remote-name ::txn/send-queue => (s/keys :opt [::txn/send-node] :req [::txn/send-queue])]
  (if (seq send-queue)
    (let [{:keys [supports-raw-body?]} (get (app->remotes app) remote-name)
          [_active-nodes send-queue] (split-with ::txn/active? send-queue)
          send-queue        (sort-queue-writes-before-reads (vec send-queue))
          id-to-send        (-> send-queue first ::txn/id)
          [to-send to-defer] (split-with #(= id-to-send (::txn/id %)) send-queue)
          leading-mutation? (mutation-node? (first to-send))
          batched-reads?    (get-in app [:com.fulcrologic.fulcro.application/config :batching-enabled remote-name] false)
          batch?            (and batched-reads? supports-raw-body?)]
      (if (or leading-mutation? (not batch?))
        (if (seq to-send)
          (let [combined-node (create-combined-node app remote-name to-send)]
            {::txn/send-node  combined-node
             ::txn/send-queue (into [combined-node] to-defer)})
          {::txn/send-queue send-queue})
        (batch-sends app remote-name send-queue)))
    {::txn/send-queue []}))

(defn net-send!
  "Process the send against the user-defined remote. Catches exceptions and calls error handler with status code 500
  if the remote itself throws exceptions."
  [app send-node remote-name]
  [:com.fulcrologic.fulcro.application/app ::txn/send-node :com.fulcrologic.fulcro.application/remote-name => any?]
  (enc/if-let [remote    (get (app->remotes app) remote-name)
               transmit! (get remote :transmit!)]
    (try
      (inspect/ido
        (if-let [batch (::txn/batch send-node)]
          (doseq [element-node batch]
            (inspect/ilet [tx (futil/ast->query (::txn/ast element-node))]
              (inspect/send-started! app remote-name (::txn/id element-node) tx)))
          (inspect/ilet [tx (futil/ast->query (::txn/ast send-node))]
            (inspect/send-started! app remote-name (::txn/id send-node) tx))))
      (transmit! remote send-node)
      (catch #?(:cljs :default :clj Exception) e
        (log/error e "Send threw an exception for tx:" (futil/ast->query (::txn/ast send-node)) "See https://book.fulcrologic.com/#err-txp-send-exc")
        (try
          (inspect/ido
            (if-let [batch (::txn/batch send-node)]
              (doseq [element-node batch]
                (inspect/send-failed! app (::txn/id element-node) "Transmit Exception"))
              (inspect/send-failed! app (::txn/id send-node) "Transmit Exception")))
          ((::txn/result-handler send-node) {:status-code      500
                                             :client-exception e})
          (catch #?(:cljs :default :clj Exception) e
            (log/fatal e "Error handler failed to handle exception!")))))
    (do
      (log/error "Transmit was not defined on remote" remote-name "See https://book.fulcrologic.com/#err-txp-remote-lacks-transmit")
      ((::txn/result-handler send-node) {:status-code 500
                                         :message     "Transmit missing on remote."}))))

(defn process-send-queues!
  "Process the send queues against the remotes. Updates the send queues on the app and returns the updated send queues."
  [{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app}]
  [:com.fulcrologic.fulcro.application/app => ::txn/send-queues]
  (let [send-queues     (-> runtime-atom deref ::txn/send-queues)
        remote-names    (app->remote-names app)
        operations      (atom [])
        new-send-queues (reduce
                          (fn [new-send-queues remote]
                            (let [send-queue (get send-queues remote [])
                                  [p serial] (extract-parallel send-queue)
                                  front      (first serial)]
                              ;; parallel items are removed from the queues, since they don't block anything
                              (doseq [item p]
                                (swap! operations conj #(net-send! app item remote)))
                              ;; sequential items are kept in queue to prevent out-of-order operation
                              (if (::txn/active? front)
                                (assoc new-send-queues remote serial)
                                (let [{::txn/keys [send-queue send-node]} (combine-sends app remote serial)]
                                  (when send-node
                                    (swap! operations conj #(net-send! app send-node remote)))
                                  (assoc new-send-queues remote send-queue)))))
                          {}
                          remote-names)]
    (swap! runtime-atom assoc ::txn/send-queues new-send-queues)
    ;; Actual net sends are done after we set the queues, in case the remote behave synchronously and immediately gives
    ;; results (like errors). Otherwise, the queue updates of those handlers would be overwritten by our swap on the
    ;; prior line
    (doseq [op @operations]
      (op))
    new-send-queues))

(defn tx-node
  ([tx]
   [::txn/tx => ::tx-node]
   (tx-node tx {}))
  ([tx options]
   [::txn/tx ::txn/options => ::tx-node]
   (let [ast       (eql/query->ast tx)
         ast-nodes (:children ast)
         elements  (into []
                     (comp
                       (filter (fn txfilt* [n] (= :call (:type n))))
                       (map-indexed
                         (fn ->txnode* [idx ast-node]
                           {::txn/idx               idx
                            ::txn/original-ast-node ast-node
                            ::txn/started?          #{}
                            ::txn/complete?         #{}
                            ::txn/results           {}
                            ::txn/dispatch          {}})))
                     ast-nodes)]
     {::txn/id       (tempid/uuid)
      ::txn/created  (futil/now)
      ::txn/options  options
      ::txn/tx       tx
      ::txn/elements elements})))

(defn build-env
  ([app {::txn/keys [options] :as tx-node} addl]
   [:com.fulcrologic.fulcro.application/app ::txn/tx-node map? => map?]
   (let [{:keys [ref component]} options]
     (cond-> (merge addl {:state (-> app :com.fulcrologic.fulcro.application/state-atom)
                          :app   app})
       options (assoc ::txn/options options)
       ref (assoc :ref ref)
       component (assoc :component component))))
  ([app {:keys [::txn/options] :as tx-node}]
   [:com.fulcrologic.fulcro.application/app ::txn/tx-node => map?]
   (build-env app tx-node {})))

(defn dispatch-elements
  "Run through the elements on the given tx-node and do the side-effect-free dispatch. This generates the dispatch map
  of things to do on that node."
  [tx-node env dispatch-fn]
  [::txn/tx-node map? any? => ::tx-node]
  (let [do-dispatch  (fn run* [env]
                       (try
                         (dispatch-fn env)
                         (catch #?(:clj Exception :cljs :default) e
                           (log/error e "Dispatch for mutation" (some-> env :ast futil/ast->query) "failed with an exception. No dispatch generated. See https://book.fulcrologic.com/#err-txp-mut-dispatch-exc")
                           {})))
        dispatch     (fn dispatch* [{:keys [::txn/original-ast-node] :as ele}]
                       (let [{:keys [type]} original-ast-node
                             env (assoc env :ast original-ast-node)]
                         (cond-> ele
                           (= :call type) (assoc ::txn/dispatch (do-dispatch env)))))
        dispatch-all (fn [eles] (mapv dispatch eles))]
    (update tx-node ::txn/elements dispatch-all)))

(defn application-rendered!
  "Should be called after the application renders to ensure that transactions blocked until the next render become
   unblocked. Schedules an activation."
  [{:keys [:com.fulcrologic.fulcro.application/runtime-atom] :as app} options]
  (when (some #(boolean (-> % ::txn/options :after-render?)) (-> runtime-atom deref ::txn/submission-queue))
    (swap! runtime-atom update ::txn/submission-queue
      (fn [queue] (mapv (fn [node] (update node ::txn/options dissoc :after-render?)) queue)))
    (schedule-activation! app 0)))

(defn activate-submissions!
  "Activate all of the transactions that have been submitted since the last activation. After the items are activated
  a single processing step will run for the active queue.

  Activation can be blocked by the tx-node options for things like waiting for the next render frame."
  [{:keys [:com.fulcrologic.fulcro.application/runtime-atom] :as app}]
  [:com.fulcrologic.fulcro.application/app => any?]
  (let [{blocked true ready false} (group-by (comp boolean :after-render? ::txn/options) (::txn/submission-queue @runtime-atom))
        dispatched-nodes (mapv #(dispatch-elements % (build-env app %) m/mutate) ready)]
    (swap! runtime-atom (fn [a]
                          (-> a
                            (update ::txn/active-queue #(reduce conj % dispatched-nodes))
                            (assoc ::txn/submission-queue (vec blocked)))))
    (process-queue! app)))

(defn schedule-activation!
  "Schedule activation of submitted transactions.  The default implementation copies all submitted transactions onto
   the active queue and immediately does an active queue processing step.  If `tm` is not supplied (in ms) it defaults to 10ms."
  ([app tm]
   [:com.fulcrologic.fulcro.application/app int? => any?]
   (schedule! app ::txn/activation-scheduled? activate-submissions! tm))
  ([app]
   [:com.fulcrologic.fulcro.application/app => any?]
   (schedule-activation! app 0)))

(defn schedule-queue-processing!
  "Schedule a processing of the active queue, which will advance the active transactions by a step.
   If `tm` is not supplied (in ms) it defaults to 10ms."
  ([app tm]
   [:com.fulcrologic.fulcro.application/app int? => any?]
   (schedule! app ::txn/queue-processing-scheduled? process-queue! tm))
  ([app]
   [:com.fulcrologic.fulcro.application/app => any?]
   (schedule-queue-processing! app 0)))

(defn schedule-sends!
  "Schedule actual network activity. If `tm` is not supplied (in ms) it defaults to 0ms."
  ([app tm]
   [:com.fulcrologic.fulcro.application/app int? => any?]
   (schedule! app ::txn/sends-scheduled? process-send-queues! tm))
  ([app]
   [:com.fulcrologic.fulcro.application/app => any?]
   (schedule-sends! app 0)))

(defn advance-actions!
  "Runs any incomplete and non-blocked optimistic operations on a node."
  [app {::txn/keys [id elements] :as node}]
  [:com.fulcrologic.fulcro.application/app ::txn/tx-node => ::tx-node]
  (let [remotes      (app->remote-names app)
        reduction    (reduce
                       (fn [{:keys [done? new-elements] :as acc} element]
                         (if done?
                           (update acc :new-elements conj element)
                           (let [{::txn/keys [complete? dispatch original-ast-node idx]} element
                                 {:keys [action]} dispatch
                                 remote-set      (set/intersection remotes (set (keys dispatch)))
                                 exec?           (and action (not (or done? (complete? :action))))
                                 fully-complete? (and (or exec? (complete? :action)) (empty? (set/difference remote-set complete?)))
                                 state-id-before (inspect/current-history-id app)
                                 state           (:com.fulcrologic.fulcro.application/state-atom app)
                                 state-before    @state
                                 updated-element (if exec? (-> element
                                                             (assoc ::txn/state-before-action state-before)
                                                             (update ::txn/complete? conj :action)) element)
                                 done?           (not fully-complete?)
                                 new-acc         {:done?        done?
                                                  :new-elements (conj new-elements updated-element)}
                                 env             (build-env app node {:ast original-ast-node})]
                             (when exec?
                               (try
                                 (when action
                                   (action env))
                                 (catch #?(:cljs :default :clj Exception) e
                                   (let [mutation-symbol (:dispatch-key original-ast-node)]
                                     (log/error e "The `action` section of mutation" mutation-symbol "threw an exception. See https://book.fulcrologic.com/#err-txp-mut-action-exc"))))
                               (ilet [tx (eql/ast->expr original-ast-node true)]
                                 (inspect/optimistic-action-finished! app env {:tx-id           (str id "-" idx)
                                                                               :state-id-before state-id-before
                                                                               :db-before       state-before
                                                                               :db-after        @state
                                                                               :tx              tx})))
                             new-acc)))
                       {:done? false :new-elements []}
                       elements)
        new-elements (:new-elements reduction)]
    (assoc node ::txn/elements new-elements)))

(defn run-actions!
  [app {::txn/keys [id elements] :as node}]
  [:com.fulcrologic.fulcro.application/app ::txn/tx-node => ::tx-node]
  (let [new-elements (reduce
                       (fn [new-elements element]
                         (let [{::txn/keys [idx complete? dispatch original-ast-node]} element
                               {:keys [action]} dispatch
                               exec?           (and action (not (complete? :action)))
                               state-id-before (inspect/current-history-id app)
                               state           (:com.fulcrologic.fulcro.application/state-atom app)
                               state-before    @state
                               updated-node    (if exec? (-> element
                                                           (assoc ::txn/state-before-action state-before)
                                                           (update ::txn/complete? conj :action)) element)
                               new-acc         (conj new-elements updated-node)
                               env             (build-env app node {:ast original-ast-node})]
                           (when exec?
                             (try
                               (action env)
                               (catch #?(:cljs :default :clj Exception) e
                                 (log/error e "The `action` section threw an exception for mutation: " (:dispatch-key original-ast-node) "See https://book.fulcrologic.com/#err-txp-mut-action-exc2")))
                             (ilet [tx (eql/ast->expr original-ast-node true)]
                               (inspect/optimistic-action-finished! app env {:tx-id           (str id "-" idx)
                                                                             :state-id-before state-id-before
                                                                             :db-before       state-before
                                                                             :db-after        @state
                                                                             :tx              tx})))
                           new-acc))
                       []
                       elements)]
    (assoc node ::txn/elements new-elements)))

(defn fully-complete?
  [app {:keys [::txn/elements] :as tx-node}]
  [:com.fulcrologic.fulcro.application/app ::txn/tx-node => boolean?]
  (let [element-complete? (fn [{:keys [::txn/dispatch ::txn/complete?]}]
                            (let [remotes     (app->remote-names app)
                                  active-keys (set/union #{:action} remotes)
                                  desired-set (set/intersection active-keys (set (keys dispatch)))]
                              (empty? (set/difference desired-set complete?))))]
    (every? element-complete? elements)))

(defn record-result!
  "Record a network result on the given txn/element.
   If result-key is given it is used, otherwise defaults to ::txn/results. Also removes the network send from the send
   queue so that remaining items can proceed, and schedules send processing."
  ([{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app} txn-id ele-idx remote result result-key]
   [:com.fulcrologic.fulcro.application/app ::txn/id int? keyword? any? keyword? => any?]
   (let [active-queue (::txn/active-queue @runtime-atom)
         txn-idx      (reduce
                        (fn [idx {:keys [::txn/id]}]
                          (if (= id txn-id)
                            (reduced idx)
                            (inc idx)))
                        0
                        active-queue)
         not-found?   (or (>= txn-idx (count active-queue)) (not= txn-id (::txn/id (get active-queue txn-idx))))]
     (if not-found?
       (log/error "Network result for" remote "does not have a valid node on the active queue! See https://book.fulcrologic.com/#err-txp-res-lacks-valid-node")
       (swap! runtime-atom assoc-in [::txn/active-queue txn-idx ::txn/elements ele-idx result-key remote] result))))
  ([app txn-id ele-idx remote result]
   [:com.fulcrologic.fulcro.application/app ::txn/id int? keyword? any? => any?]
   (record-result! app txn-id ele-idx remote result ::txn/results)))

(defn compute-desired-ast-node
  "Add the ::txn/desired-ast-nodes and ::txn/transmitted-ast-nodes for `remote` to the tx-element based on the dispatch for the `remote` of the original mutation."
  [app remote tx-node tx-element]
  [:com.fulcrologic.fulcro.application/app :com.fulcrologic.fulcro.application/remote-name ::txn/tx-node ::txn/tx-element => ::tx-element]
  (let [{::txn/keys [dispatch original-ast-node state-before-action]} tx-element
        env             (build-env app tx-node {:ast                 original-ast-node
                                                :state-before-action state-before-action})
        remote-fn       (get dispatch remote)
        remote-desire   (when remote-fn (remote-fn env))
        desired-ast     (cond
                          (or (false? remote-desire) (nil? remote-desire)) nil
                          (true? remote-desire) original-ast-node
                          (and (map? remote-desire) (contains? remote-desire :ast)) (:ast remote-desire)
                          (and (map? remote-desire) (contains? remote-desire :type)) remote-desire
                          :else (do
                                  (log/error "Remote dispatch for" remote "returned an invalid value." remote-desire "See https://book.fulcrologic.com/#err-txp-remote-dispatch-invalid-res")
                                  remote-desire))
        ;; The EQL transform from fulcro app config ONLY affects the network layer (the AST we put on the send node).
        ;; The response gets dispatched on network return, but the original query
        ;; is needed at the top app layer so that :pre-merge can use the complete query
        ;; as opposed to the pruned one.
        query-transform (ah/app-algorithm app :global-eql-transform)
        ast             (if (and desired-ast query-transform)
                          (query-transform desired-ast)
                          desired-ast)]
    (cond-> tx-element
      desired-ast (assoc-in [::txn/desired-ast-nodes remote] desired-ast)
      ast (assoc-in [::txn/transmitted-ast-nodes remote] ast))))

(defn add-send!
  "Generate a new send node and add it to the appropriate send queue. Returns the new send node."
  [{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app} {::txn/keys [id options parallel?] :as tx-node} ele-idx remote]
  [:com.fulcrologic.fulcro.application/app ::txn/tx-node ::txn/idx :com.fulcrologic.fulcro.application/remote-name
   => (s/nilable ::txn/send-node)]
  (let [update-handler (fn progress-handler* [result]
                         (record-result! app id ele-idx remote result ::txn/progress)
                         (schedule-queue-processing! app 0))
        ast            (get-in tx-node [::txn/elements ele-idx ::txn/transmitted-ast-nodes remote])
        remove!        *remove-send*
        handler        (fn result-handler* [result]
                         (when (:parallel? options)
                           (inspect/ilet [{:keys [status-code body]} result]
                            (if (= 200 status-code)
                              (inspect/send-finished! app remote id body)
                              (inspect/send-failed! app id (str status-code)))))
                         (record-result! app id ele-idx remote result)
                         (remove! app remote id ele-idx)
                         (schedule-sends! app 1)
                         (schedule-queue-processing! app 0))
        send-node      {::txn/id             id
                        ::txn/idx            ele-idx
                        ::txn/ast            ast
                        ::txn/options        options
                        ::txn/active?        false
                        ::txn/result-handler handler
                        ::txn/update-handler update-handler}]
    (if ast
      (do
        (swap! runtime-atom update-in [::txn/send-queues remote] (fnil conj []) send-node)
        send-node)
      (do
        (handler {:status-code 200 :body {}})
        nil))))

(defn queue-element-sends!
  "Queue all (unqueued) remote actions for the given element.  Returns the (possibly updated) node."
  [app tx-node {:keys [::txn/idx ::txn/dispatch ::txn/started?]}]
  [:com.fulcrologic.fulcro.application/app ::txn/tx-node ::txn/tx-element => ::tx-node]
  (let [remotes     (set/intersection (set (keys dispatch)) (app->remote-names app))
        to-dispatch (set/difference remotes started?)]
    (reduce
      (fn [node remote]
        (if (contains? (get-in node [::txn/elements idx ::txn/started?] #{}) remote)
          node
          (let [updated-node (-> node
                               (update-in [::txn/elements idx] (fn [tx-element] (compute-desired-ast-node app remote node tx-element)))
                               (update-in [::txn/elements idx ::txn/started?] conj remote))]
            (add-send! app updated-node idx remote)
            updated-node)))
      tx-node
      to-dispatch)))

(defn idle-node?
  "Returns true if the given node has no active network operations."
  [{:keys [::txn/elements] :as tx-node}]
  [::txn/tx-node => boolean?]
  (every?
    (fn idle?* [{:keys [::txn/started? ::txn/complete?]}]
      (let [in-progress (set/difference started? complete?)]
        (empty? in-progress)))
    elements))

(defn element-with-work
  "Returns a txnode element iff it has remaining (remote) work that has not been queued. Returns nil if there
   is no such element.

  remote-names is the set of legal remote names."
  [remote-names {:keys [::txn/dispatch ::txn/started?] :as element}]
  [:com.fulcrologic.fulcro.application/remote-names ::txn/tx-element => (s/nilable ::tx-element)]
  (let [todo      (set/intersection remote-names (set (keys dispatch)))
        remaining (set/difference todo started?)]
    (when (seq remaining)
      element)))

(defn queue-next-send!
  "Assumes tx-node is to be processed pessimistically. Queues the next send if the node is currently idle
  on the network and there are any sends left to do. Adds to the send queue, and returns the updated
  tx-node."
  [app {:keys [::txn/elements] :as tx-node}]
  [:com.fulcrologic.fulcro.application/app ::txn/tx-node => ::tx-node]
  (if (idle-node? tx-node)
    (let [remotes   (app->remote-names app)
          with-work (partial element-with-work remotes)
          element   (some with-work elements)]
      (if element
        (queue-element-sends! app tx-node element)
        tx-node))
    tx-node))

(defn queue-sends!
  "Finds any item(s) on the given node that are ready to be placed on the network queues and adds them. Non-optimistic
  multi-element nodes will only queue one remote operation at a time."
  [app {:keys [::txn/options ::txn/elements] :as tx-node}]
  [:com.fulcrologic.fulcro.application/app ::txn/tx-node => ::tx-node]
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
  [app tx-node {::txn/keys [results dispatch desired-ast-nodes transmitted-ast-nodes original-ast-node] :as tx-element} remote]
  [:com.fulcrologic.fulcro.application/app ::txn/tx-node ::txn/tx-element keyword? => ::tx-element]
  (schedule-queue-processing! app 0)
  (let [result  (get results remote)
        handler (get dispatch :result-action)]
    (when handler
      (let [env (build-env app tx-node {:dispatch        dispatch
                                        :transacted-ast  original-ast-node
                                        :mutation-ast    (get desired-ast-nodes remote)
                                        :transmitted-ast (get transmitted-ast-nodes remote)
                                        :result          result})]
        (try
          (handler env)
          (catch #?(:cljs :default :clj Exception) e
            (log/error e "The result-action mutation handler for mutation" (:dispatch-key original-ast-node) "threw an exception. See https://book.fulcrologic.com/#err-txp-mut-res-action-exc"))))))
  (update tx-element ::txn/complete? conj remote))

(defn distribute-element-results!
  "Distribute results and mark the remotes for those elements as complete."
  [app tx-node {:keys [::txn/results ::txn/complete?] :as tx-element}]
  [:com.fulcrologic.fulcro.application/app ::txn/tx-node ::txn/tx-element => ::tx-element]
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
  [app {:keys [::txn/elements] :as tx-node}]
  [:com.fulcrologic.fulcro.application/app ::txn/tx-node => ::tx-node]
  (assoc tx-node
    ::txn/elements (mapv
                     (fn [element] (distribute-element-results! app tx-node element))
                     elements)))

(defn update-progress!
  "Report all progress items to any registered progress dispatch and clear them from the tx-node.
  Returns the updated tx-node."
  [app {:keys [::txn/elements] :as tx-node}]
  [:com.fulcrologic.fulcro.application/app ::txn/tx-node => ::tx-node]
  (let [get-env (fn get-env* [remote progress] (build-env app tx-node {:remote remote :progress progress}))]
    (reduce
      (fn [node {::txn/keys [idx progress dispatch original-ast-node] :as element}]
        (doseq [[remote value] progress]
          (let [env    (get-env remote value)
                action (get dispatch :progress-action)]
            (when action
              (try
                (action env)
                (catch #?(:cljs :default :clj Exception) e
                  (log/error e "Progress action threw an exception in mutation" (:dispatch-key original-ast-node) "See https://book.fulcrologic.com/#err-txp-progress-action-exc"))))))
        (update-in node [::txn/elements idx] dissoc ::txn/progress))
      tx-node
      elements)))

(defn process-tx-node!
  [app {:keys [::txn/options] :as tx-node}]
  [:com.fulcrologic.fulcro.application/app ::txn/tx-node => (s/nilable ::tx-node)]
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

(defn requested-refreshes [app queue]
  [:com.fulcrologic.fulcro.application/app (s/coll-of ::txn/tx-node) => set?]
  "Returns a set of refreshes that have been requested by active mutations in the queue"
  (reduce
    (fn [outer-acc tx-node]
      (let [env (build-env app tx-node)]
        (reduce
          (fn [acc element]
            (let [{::txn/keys [dispatch]} element
                  refresh (:refresh dispatch)]
              (if refresh
                (into acc (set (refresh env)))
                acc)))
          outer-acc
          (::txn/elements tx-node))))
    #{}
    queue))

(defn remotes-active-on-node
  "Given a tx node and the set of legal remotes: returns a set of remotes that are active on that node."
  [{::txn/keys [elements] :as tx-node} remotes]
  [::txn/tx-node :com.fulcrologic.fulcro.application/remote-names
   => :com.fulcrologic.fulcro.application/remote-names]
  (let [active-on-element (fn [{::txn/keys [dispatch complete?]}]
                            (let [remotes (set remotes)]
                              (-> remotes
                                (set/intersection (set (keys dispatch)))
                                (set/difference complete?))))]
    (reduce
      (fn [acc ele]
        (set/union acc (active-on-element ele)))
      #{}
      elements)))

(defn active-remotes
  "Calculate which remotes still have network activity to do on the given active queue."
  [queue remotes]
  [::txn/active-queue :com.fulcrologic.fulcro.application/remote-names
   => :com.fulcrologic.fulcro.application/active-remotes]
  (reduce
    (fn [ra n]
      (set/union ra (remotes-active-on-node n remotes)))
    #{}
    queue))

(defn process-queue!
  "Run through the active queue and do a processing step."
  [{:com.fulcrologic.fulcro.application/keys [state-atom runtime-atom] :as app}]
  [:com.fulcrologic.fulcro.application/app => any?]
  (let [new-queue        (reduce
                           (fn *pstep [new-queue n]
                             (if-let [new-node (process-tx-node! app n)]
                               (conj new-queue new-node)
                               new-queue))
                           []
                           (::txn/active-queue @runtime-atom))
        accumulate       (fn [r items] (into (set r) items))
        remotes          (app->remote-names app)
        schedule-render! (ah/app-algorithm app :schedule-render!)
        explicit-refresh (requested-refreshes app new-queue)
        remotes-active?  (active-remotes new-queue remotes)]
    (swap! state-atom assoc :com.fulcrologic.fulcro.application/active-remotes remotes-active?)
    (swap! runtime-atom assoc ::txn/active-queue new-queue)
    (when (seq explicit-refresh)
      (swap! runtime-atom update :com.fulcrologic.fulcro.application/to-refresh accumulate explicit-refresh))
    (schedule-render! app)
    nil))

(defn transact-sync!
  "Run the optimistic action(s) of a transaction synchronously. It is primarily used to deal with controlled inputs, since they
   have issues working asynchronously, so ideally the mutation in question will *not* have remote action (though they
   are allowed to).

   NOTE: any *remote* behaviors of `tx` will *still be async*.

   This function:

   * Runs the optimistic side of the mutation(s)
   * IF (and only if) one or more of the mutations has more sections than just an `action` then it submits the mutation to the normal transaction queue,
     but with the optimistic part already done.
   * This functions *does not* queue a render refresh (though if the normal transaction queue is updated, it will queue tx remote processing, which will trigger a UI refresh).

   If you pass it an on-screen instance that has a query and ident, then this function tunnel updated UI props synchronously to that
   component so it can refresh immediately and avoid DOM input issues.

   Returns the new component props or the final state map if no component was used in the transaction.
   "
  [app tx {:keys [component ref] :as options}]
  (let [mutation-nodes      (:children (eql/query->ast tx))
        ast-node->operation (zipmap mutation-nodes (map (fn [ast-node] (m/mutate {:ast ast-node})) mutation-nodes))
        {optimistic true
         mixed      false} (group-by #(= #{:action :result-action} (-> (ast-node->operation %) keys set)) mutation-nodes)
        optimistic-tx-node  (when (seq optimistic)
                              (let [node (tx-node (eql/ast->query {:type :root :children optimistic}) options)]
                                (dispatch-elements node (build-env app node) m/mutate)))
        mixed-tx-node       (when (seq mixed)
                              (let [node (tx-node (eql/ast->query {:type :root :children mixed}) options)]
                                (dispatch-elements node (build-env app node) m/mutate)))
        resulting-node-id   (atom nil)]
    (when optimistic-tx-node (run-actions! app optimistic-tx-node))
    (when mixed-tx-node
      (let [node         (run-actions! app mixed-tx-node)
            runtime-atom (:com.fulcrologic.fulcro.application/runtime-atom app)]
        (reset! resulting-node-id (::txn/id node))
        (swap! runtime-atom update ::txn/active-queue conj node)
        (schedule-queue-processing! app 20)))
    (cond
      (and component (rc/component? component) (rc/has-ident? component))
      (when-let [refresh-component! (ah/app-algorithm app :refresh-component!)]
        (refresh-component! component))
      ref (let [r! (ah/app-algorithm app :render!)] (when r! (r! app)))
      :else (when #?(:cljs js/goog.DEBUG :clj true)
              (log/warn "Synchronous transaction was submitted on the app or a component without an ident. No UI refresh will happen. See https://book.fulcrologic.com/#warn-tx-missing-ident")))
    @resulting-node-id))

(defn default-tx!
  "Default (Fulcro-2 compatible) transaction submission. The options map can contain any additional options
  that might be used by the transaction processing (or UI refresh).

  Some that may be supported (depending on application settings):

  - `:optimistic?` - boolean. Should the transaction be processed optimistically?
  - `:ref` - ident. The component ident to include in the transaction env.
  - `:component` - React element. The instance of the component that should appear in the transaction env.
  - `:refresh` - Vector containing idents (of components) and keywords (of props). Things that have changed and should be re-rendered
    on screen. Only necessary when the underlying rendering algorithm won't auto-detect, such as when UI is derived from the
    state of other components or outside of the directly queried props. Interpretation depends on the renderer selected:
    The ident-optimized render treats these as \"extras\".
  - `:only-refresh` - Vector of idents/keywords.  If the underlying rendering configured algorithm supports it: The
    components using these are the *only* things that will be refreshed in the UI.
    This can be used to avoid the overhead of looking for stale data when you know exactly what
    you want to refresh on screen as an extra optimization. Idents are *not* checked against queries.

  WARNING: `:only-refresh` can cause missed refreshes because rendering is debounced. If you are using this for
           rapid-fire updates like drag-and-drop it is recommended that on the trailing edge (e.g. drop) of your sequence you
           force a normal refresh via `app/render!`.

  If the `options` include `:ref` (which comp/transact! sets), then it will be auto-included on the `:refresh` list.

  NOTE: Fulcro 2 'follow-on reads' are supported and are added to the `:refresh` entries. Your choice of rendering
  algorithm will influence their necessity.

  Returns the transaction ID of the submitted transaction.
  "
  ([app tx]
   [:com.fulcrologic.fulcro.application/app ::txn/tx => ::txn/id]
   (default-tx! app tx {}))
  ([{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app} tx {:keys [synchronous?] :as options}]
   [:com.fulcrologic.fulcro.application/app ::txn/tx ::txn/options => ::txn/id]
   (if synchronous?
     (transact-sync! app tx options)
     (do
       (schedule-activation! app)
       (let [{:keys [refresh only-refresh ref] :as options} (merge {:optimistic? true} options)
             follow-on-reads (into #{} (filter #(or (keyword? %) (eql/ident? %)) tx))
             node            (tx-node tx options)
             accumulate      (fn [r items] (into (set r) items))
             refresh         (cond-> (set refresh)
                               (seq follow-on-reads) (into follow-on-reads)
                               ref (conj ref))]
         (swap! runtime-atom (fn [s] (cond-> (update s ::txn/submission-queue (fn [v n] (conj (vec v) n)) node)
                                       ;; refresh sets are cumulative because rendering is debounced
                                       (seq refresh) (update :com.fulcrologic.fulcro.application/to-refresh accumulate refresh)
                                       (seq only-refresh) (update :com.fulcrologic.fulcro.application/only-refresh accumulate only-refresh))))
         (::txn/id node))))))

(defn- abort-elements!
  "Abort any elements in the given send-queue that have the given abort id.

  Aborting will cause the network to abort (which will report a result), or if the item is not yet active a
  virtual result will still be sent for that node.

  Returns a new send-queue that no longer contains the aborted nodes."
  [{:keys [abort!] :as remote} send-queue abort-id]
  (if abort!
    (reduce
      (fn [result {::txn/keys [active? result-handler] :as send-node}]
        (let [aid (node-abort-id send-node)]
          (cond
            (not= aid abort-id) (do
                                  (conj result send-node))
            active? (do
                      (abort! remote abort-id)
                      result)
            :otherwise (do
                         (result-handler {:status-text "Cancelled" ::txn/aborted? true})
                         result))))
      []
      send-queue)
    (do
      (log/error "Cannot abort network requests. The remote has no abort support! See https://book.fulcrologic.com/#err-txp-cant-abort")
      send-queue)))

(defn abort!
  "Implementation of abort when using this tx processing"
  [app abort-id]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} (rc/any->app app)
        runtime-state   @runtime-atom
        {:com.fulcrologic.fulcro.application/keys [remotes]
         ::txn/keys                               [send-queues]} runtime-state
        remote-names    (keys send-queues)
        new-send-queues (reduce
                          (fn [result remote-name]
                            (assoc result remote-name (abort-elements!
                                                        (get remotes remote-name)
                                                        (get send-queues remote-name) abort-id)))
                          {}
                          remote-names)]
    (swap! runtime-atom assoc ::txn/send-queues new-send-queues)))

(defn abort-remote!
  "Cause everything in the active network queue for remote to be cancelled. Any result that (finally) appears for aborted
  items will be ignored. This will cause a hard error to be \"received\" as the result for everything
  that is in the send queue of the given remote.

  This function is mainly meant for use in development mode when dealing with a buggy remote implementation."
  [app-ish remote]
  (let [app            (rc/any->app app-ish)
        {:com.fulcrologic.fulcro.application/keys [state-atom runtime-atom]} (rc/any->app app)
        {abort-network! :abort!
         :as            the-remote} (get @runtime-atom [:com.fulcrologic.fulcro.application/remotes remote])
        old-send-queue (get-in @runtime-atom [::txn/send-queues remote])]
    (swap! runtime-atom assoc-in [::txn/send-queues remote] [])
    (swap! state-atom update :com.fulcrologic.fulcro.application/active-remotes (fnil disj #{}) remote)
    (doseq [{::txn/keys [active? result-handler] :as send-node} old-send-queue
            aid (node-abort-id send-node)]
      (try
        (when active?
          (if abort-network!
            (abort-network! the-remote aid)
            (log/warn "Remote does not support abort. Clearing the queue, but a spurious result may still appear. See https://book.fulcrologic.com/#warn-tx-remote-abort-not-supported")))
        (result-handler {:status-code   500
                         :body          {}
                         :status-text   "Globally Aborted"
                         ::txn/aborted? true})
        (catch #?(:clj Exception :cljs :default) e
          (log/error e "Failed to abort send node. See https://book.fulcrologic.com/#err-txp-abort-failed"))))))

(defn with-batched-reads
  "Modify the given fulcro app so that it does reads in batches when possible. Requires server support (which is
  provided by Fulcro's handle-api-request), and a remote that can handle raw requests (see the latest http-remote).

  Defaults to enabling batching on all remotes, but if any of your client-side remote code or servers do not support
  batching, pass the `remotes` option, which is a set of keywords that names the remotes on which batching should
  be enabled."
  ([app] (with-batched-reads app nil))
  ([app remotes]
   (let [remotes          (or remotes
                            (some-> app
                              :com.fulcrologic.fulcro.application/runtime-atom
                              deref
                              :com.fulcrologic.fulcro.application/remotes
                              keys))
         batching-enabled (if (seq remotes) (zipmap remotes (repeat true)) {})]
     (-> app
       (assoc-in [:com.fulcrologic.fulcro.application/algorithms :com.fulcrologic.fulcro.algorithm/tx!] default-tx!)
       (assoc-in [:com.fulcrologic.fulcro.application/algorithms :com.fulcrologic.fulcro.algorithm/abort!] abort!)
       (assoc-in [:com.fulcrologic.fulcro.application/config :batching-enabled] batching-enabled)))))
