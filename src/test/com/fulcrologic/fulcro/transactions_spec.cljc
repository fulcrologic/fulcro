(ns com.fulcrologic.fulcro.transactions-spec
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.specs :as s+]
    [fulcro-spec.core :refer [specification provided! when-mocking! assertions behavior when-mocking component]]
    [clojure.spec.alpha :as s]
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.fulcro.algorithms.misc :refer [uuid]]
    [ghostwheel.core :refer [>defn =>]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.algorithms.scheduling :as sched]
    [com.fulcrologic.fulcro.application :as app :refer [fulcro-app]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [edn-query-language.core :as eql]
    [clojure.test :refer [is are deftest]]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.application-helpers :as ah]))

(defn fake-render [& args])

(defn mock-app
  ([params] (-> (fulcro-app params)
              (ah/with-optimized-render fake-render)))
  ([] (mock-app {})))

(>defn ->send
  ([id options]
   [::txn/id map? => ::txn/send-node]
   {::txn/id             id ::txn/idx 0 ::txn/ast (eql/query->ast1 `[(f {})])
    ::txn/result-handler (fn []) ::txn/error-handler (fn []) ::txn/update-handler (fn [])
    ::txn/active?        false
    ::txn/options        options})
  ([idn idx tx options]
   [int? ::txn/idx vector? map? => ::txn/send-node]
   {::txn/id             (uuid idn) ::txn/idx idx ::txn/ast (eql/query->ast tx)
    ::txn/result-handler (fn []) ::txn/error-handler (fn []) ::txn/update-handler (fn [])
    ::txn/active?        false
    ::txn/options        options}))

(specification "tx-node"
  (let [n (txn/tx-node '[(f) (g)] {:x 1})
        [f-ele g-ele :as elements] (::txn/elements n)]
    (assertions
      "Creates a valid node"
      (s/valid? ::txn/tx-node n) => true
      "Includes the given options"
      (::txn/options n) => {:x 1}
      "Parsed the tx into elements"
      (count elements) => 2
      (-> f-ele ::txn/original-ast-node :dispatch-key) => 'f)))

(specification "tx!"
  (let [mock-app (mock-app)]
    (when-mocking!
      (txn/schedule-activation! app) => (assertions
                                          "schedules activation"
                                          app => mock-app)

      (let [actual (app/default-tx! mock-app '[(f) (g)])
            queue  (-> mock-app ::app/runtime-atom deref ::txn/submission-queue)
            node   (first queue)]
        (assertions
          "Puts a new node on the submission queue"
          (s/valid? ::txn/tx-node node) => true
          "Returns the UUID of the new tx node"
          (uuid? actual) => true
          (::txn/id node) => actual)))))

(specification "dispatch-elements"
  (behavior "Places the result of the dispatch function on the dispatch key"
    (let [n            (txn/tx-node '[(f) (g)])
          updated-node (txn/dispatch-elements n
                         {:env true}
                         (fn [e] {:ran (-> e :ast :dispatch-key)}))
          [f-ele g-ele] (::txn/elements updated-node)]
      (assertions
        "Returns the updated node"
        (s/valid? ::txn/tx-node updated-node) => true
        "dispatch map contains entries for each mutation"
        (::txn/dispatch f-ele) => {:ran 'f}
        (::txn/dispatch g-ele) => {:ran 'g})))
  (behavior "Converts boolean remotes to node AST"
    (let [n            (txn/tx-node `[(f {:x 1})])
          updated-node (txn/dispatch-elements n
                         {:env true :ast {}}
                         (fn [env] {:remote true}))
          [f-ele] (::txn/elements updated-node)]
      (assertions
        (s/valid? ::txn/tx-node updated-node) => true
        "dispatch map contains entries for each mutation"
        (contains? (::txn/dispatch f-ele) :remote) => true)))
  (behavior "tolerates dispatches that throw exceptions"
    (when-mocking

      (log/-log! _ _ _ _ _ _ _ args _ _)
      =2x=> (assertions
              "Logs an error about the failed mutation"
              (str/includes? (second @args) "Dispatch of mutation") => true)

      (let [n            (txn/tx-node '[(f) (g)])
            updated-node (txn/dispatch-elements n
                           {:env true}
                           (fn [e] (throw (ex-info "INTENTIONAL THROW FROM TEST" {}))))
            [f-ele g-ele] (::txn/elements updated-node)]
        (assertions
          "Returns the updated node"
          (s/valid? ::txn/tx-node updated-node) => true
          "dispatch map contains entries for each mutation"
          (::txn/dispatch f-ele) => {}
          (::txn/dispatch g-ele) => {})))))

(specification "schedule!"
  (let [app           (mock-app)
        action-called (atom 0)
        action        (fn [a]
                        (assertions
                          "the action is called with the app"
                          a => app)
                        (swap! action-called inc))
        deferred-fn   (atom nil)
        k             ::txn/activation-scheduled?]
    (when-mocking!
      (sched/defer f tm) => (do
                              (assertions
                                "only calls defer once per timeout"
                                (deref deferred-fn) => nil
                                "schedules for the provided time"
                                tm => 5)
                              (reset! deferred-fn f))

      (sched/schedule! app k action 5)
      (sched/schedule! app k action 6)
      (sched/schedule! app k action 7)

      (behavior "when triggered"
        (@deferred-fn)

        (assertions
          "calls the action once"
          (-> action-called deref) => 1
          "resets the flag back to false"
          (-> app ::app/runtime-atom deref k) => false)))))

(defmutation f [p]
  (action [env]
    (identity 32))
  (ok-action [env] nil)
  (error-action [env] nil)
  (remote [env] true))
(defmutation g [p]
  (remote [env] false))

(specification "activate-submissions!"
  (let [app (mock-app)]
    (when-mocking!
      (txn/schedule-activation! app) => nil
      (txn/process-queue! app) => (assertions
                                    "Processes the active queue."
                                    true => true)

      (comp/transact! app `[(f {})])
      (comp/transact! app `[(g {})])

      (txn/activate-submissions! app)

      (let [sub-q (-> app ::app/runtime-atom deref ::txn/submission-queue)
            act-q (-> app ::app/runtime-atom deref ::txn/active-queue)
            node  (first act-q)
            node2 (second act-q)]
        (assertions
          "Clears the submission queue"
          sub-q => []
          "Places the nodes on the active queue"
          (count act-q) => 2
          "Dispatches the node"
          (fn? (-> node ::txn/elements first ::txn/dispatch :remote)) => true
          (fn? (-> node ::txn/elements first ::txn/dispatch :action)) => true
          "Successive calls append to the active queue"
          (fn? (-> node2 ::txn/elements first ::txn/dispatch :remote)) => true)))))

(defonce aa-track (atom #{}))

(defmutation aa1 [p]
  (action [env]
    (reset! aa-track [:aa1])))

(defmutation aa2 [p]
  (action [env]
    (swap! aa-track conj :aa2))
  (remote [env] true))

(defmutation aa3 [p]
  (action [env]
    (swap! aa-track conj :aa3))
  (remote [env] true))

(specification "advance-actions!"
  (let [app               (mock-app)
        node              (txn/tx-node `[(aa1 {}) (aa2 {}) (aa3 {})])
        node              (txn/dispatch-elements node {} (fn [e] (m/mutate e)))
        step1             (txn/advance-actions! app node)
        step1-effects     @aa-track
        step1a            (txn/advance-actions! app step1)
        step1a-effects    @aa-track
        step1-remote-done (update-in step1 [::txn/elements 1 ::txn/complete?] conj :remote)
        step2             (txn/advance-actions! app step1-remote-done)
        step2-effects     @aa-track]
    (assertions
      "The first element is marked complete"
      (-> step1 ::txn/elements first ::txn/complete?) => #{:action}
      "After one step it will run all optimism up till the first remote operation"
      step1-effects => [:aa1 :aa2]
      "Re-running advance only skips processed aspects, and stops on node that is still active"
      step1a-effects => [:aa1 :aa2]
      "Completed remote unblocks and allows processing to continue"
      step2-effects => [:aa1 :aa2 :aa3]
      (-> step2 ::txn/elements first ::txn/complete?) => #{:action}
      (-> step2 ::txn/elements second ::txn/complete?) => #{:action :remote}
      "but that processing stops at the very next full-stack mutation"
      (-> step2 ::txn/elements (nth 2) ::txn/complete?) => #{:action})))

(specification "run-actions!"
  (let [app     (mock-app)
        node    (txn/tx-node `[(aa1 {}) (aa2 {}) (aa3 {})])
        node    (txn/dispatch-elements node {} (fn [e] (m/mutate e)))
        actual  (txn/run-actions! app node)
        effects @aa-track]
    (assertions
      "Runs all optimistic operations"
      effects => [:aa1 :aa2 :aa3]
      "Records them as complete in the elements"
      (-> actual ::txn/elements first ::txn/complete?) => #{:action}
      (-> actual ::txn/elements second ::txn/complete?) => #{:action}
      (-> actual ::txn/elements (nth 2) ::txn/complete?) => #{:action})))

(specification "fully-complete?"
  (let [app   (mock-app)
        node  (txn/tx-node `[(aa1 {}) (aa2 {}) (aa3 {})])
        dnode (txn/dispatch-elements node {} (fn [e] (m/mutate e)))
        rnode (txn/run-actions! app dnode)
        fnode (-> rnode
                (update-in [::txn/elements 1 ::txn/complete?] conj :remote)
                (update-in [::txn/elements 2 ::txn/complete?] conj :remote))]
    (assertions
      "A node without dispatches is fully complete"
      (txn/fully-complete? app node) => true
      "A dispatched node is not fully complete"
      (txn/fully-complete? app dnode) => false
      "A node with pending remotes is not fully complete"
      (txn/fully-complete? app rnode) => false
      "A node with all remotes and actions run is fully complete"
      (txn/fully-complete? app fnode) => true)))

(specification "extract-parallel"
  (let [sends [(->send (uuid 1) {::txn/parallel? true}) (->send (uuid 2) {:parallel? true}) (->send (uuid 3) {})]
        [p s] (txn/extract-parallel sends)
        pids  (into #{} (map ::txn/id) p)
        sids  (into #{} (map ::txn/id) s)]
    (assertions
      "Extracts the correct parallel items"
      pids => #{(uuid 1) (uuid 2)}
      "Extracts the correct sequential items"
      sids => #{(uuid 3)})))

(specification "process-send-queues!"
  (behavior "When there are parallel and/or sequential requests ready to go"
    (let [sends         [(->send (uuid 1) {::txn/parallel? true}) (->send (uuid 2) {}) (->send (uuid 3) {})
                         (-> (->send (uuid 4) {}) (assoc ::txn/ast (eql/query->ast [:x :y])))]
          send-queues   {:remote sends}
          app           (mock-app {:remotes {:remote {:transmit! (fn [send])}}})
          mock-combined (-> (->send (uuid 4) {})
                          (assoc ::txn/active? true))]
      (swap! (::app/runtime-atom app) assoc ::txn/send-queues send-queues)

      (when-mocking!
        (txn/extract-parallel s) => (do
                                      (assertions
                                        "Uses extract-parallel to split on the parallel sends"
                                        s => sends)
                                      [[(first sends)] (vec (rest sends))])
        (txn/combine-sends app remote serial) => (do
                                                   (assertions
                                                     "Uses the combined send alg to attempt combining together serial sends"
                                                     remote => :remote
                                                     serial => (rest sends))
                                                   {::txn/send-queue [mock-combined]
                                                    ::txn/send-node  mock-combined})
        (txn/net-send! app s r) =1x=> (do
                                        (assertions
                                          "Immediately sends parallel items"
                                          s => (first sends)))
        (txn/net-send! app s r) =1x=> (do
                                        (assertions
                                          "Sends only the first item in the sequential list"
                                          s => mock-combined))

        (let [{:keys [remote] :as actual} (txn/process-send-queues! app)]
          (assertions
            "Returns the updated send queues with nodes combined"
            actual => {:remote [mock-combined]}
            "where each queue is still a vector"
            (vector? remote) => true)))))

  (behavior "when the queue(s) already have active item(s)"
    (let [sends       [(assoc (->send (uuid 2) {}) ::txn/active? true) (->send (uuid 3) {})]
          send-queues {:remote sends}
          remotes     {:remote (fn [send] (throw (ex-info "SHOULD NOT BE CALLED" {})))}
          {:keys [::app/runtime-atom] :as app} (mock-app)]
      (swap! runtime-atom assoc ::app/remotes remotes)
      (swap! runtime-atom assoc ::txn/send-queues send-queues)

      (when-mocking!
        (txn/extract-parallel s) => [[] (vec sends)]

        (let [actual (txn/process-send-queues! app)]
          (assertions
            "Returns the unchanged send queues"
            actual => send-queues))))))

(specification "build-env"
  (let [app        (mock-app)
        tx-options {:parallel? true}
        tx-node    (txn/tx-node `[(f {})] tx-options)
        addl       {:ref [:x 1] :component :c :crap 22 :app nil}
        actual     (txn/build-env app tx-node addl)]
    (assertions
      "Includes the app (which cannot be overwritten by addl)"
      (:app actual) => app
      "Includes the component ref, if available"
      (:ref actual) => [:x 1]
      "Includes the component, if available"
      (:component actual) => :c
      "Places tx-node options at the ::txn/options key"
      (::txn/options actual) => tx-options
      "Merges the additional options into the env"
      (:crap actual) => 22)))

(specification "schedule-activation!"
  (let [app (mock-app)]
    (when-mocking!
      (sched/schedule! app k action tm) =1x=> (assertions
                                                "schedules based on the correct key"
                                                k => ::txn/activation-scheduled?
                                                "with the time passed by the user"
                                                tm => 5)

      (txn/schedule-activation! app 5))

    (when-mocking!
      (sched/schedule! app k action tm) =1x=> (assertions
                                                "defaults to 10ms"
                                                k => ::txn/activation-scheduled?
                                                tm => 10)

      (txn/schedule-activation! app))))

(specification "schedule-queue-processing!"
  (let [app (mock-app)]
    (when-mocking!
      (sched/schedule! app k action tm) =1x=> (assertions
                                                "schedules based on the correct key"
                                                k => ::txn/queue-processing-scheduled?
                                                "with the time passed by the user"
                                                tm => 5)

      (txn/schedule-queue-processing! app 5))

    (when-mocking!
      (sched/schedule! app k action tm) =1x=> (assertions
                                                "defaults to 10ms"
                                                k => ::txn/queue-processing-scheduled?
                                                tm => 10)

      (txn/schedule-queue-processing! app))))

(specification "schedule-sends!"
  (let [app (mock-app)]
    (when-mocking!
      (sched/schedule! app k action tm) =1x=> (assertions
                                                "schedules based on the correct key"
                                                k => ::txn/sends-scheduled?
                                                "with the time passed by the user"
                                                tm => 5)

      (txn/schedule-sends! app 5))

    (when-mocking!
      (sched/schedule! app k action tm) =1x=> (assertions
                                                "defaults to 0ms"
                                                k => ::txn/sends-scheduled?
                                                tm => 0)

      (txn/schedule-sends! app))))

(specification "remove-send!"
  (let [sends          [(->send (uuid 1) {}) (->send (uuid 2) {}) (->send (uuid 3) {})]
        send-queues    {:remote sends
                        :rest   []}
        {:keys [::app/runtime-atom] :as app} (mock-app)
        expected-sends [(first sends) (last sends)]]
    (swap! runtime-atom assoc ::txn/send-queues send-queues)

    (txn/remove-send! app :remote (uuid 2) 0)

    (assertions
      "Removes the node from the correct remote queue"
      (-> runtime-atom deref ::txn/send-queues :remote) => expected-sends
      "Leaves the other remote queues unchanged"
      (-> runtime-atom deref ::txn/send-queues :rest) => [])))

(specification "record-result!"
  (let [{:keys [::app/runtime-atom] :as app} (mock-app)
        tx-node     (-> (txn/tx-node `[(f {})])
                      (assoc-in [::txn/elements 0 ::txn/started?] #{:remote})
                      (assoc ::txn/id (uuid 1))
                      (txn/dispatch-elements {} (fn [e] (m/mutate e))))
        tx-node2    (-> (txn/tx-node `[(g {})])
                      (assoc ::txn/id (uuid 2))
                      (txn/dispatch-elements {} (fn [e] (m/mutate e))))
        mock-result {:x 1}]
    (swap! runtime-atom update ::txn/active-queue conj tx-node2 tx-node)

    (txn/record-result! app (uuid 1) 0 :remote mock-result)

    (assertions
      "Places the result in the correct remote result on the correct tx-node in the active queue of the runtime atom"
      (-> runtime-atom deref ::txn/active-queue second ::txn/elements first ::txn/results :remote) => mock-result
      "Leaves other nodes unmodified"
      (-> runtime-atom deref ::txn/active-queue first) => tx-node2))

  (let [{:keys [::app/runtime-atom] :as app} (mock-app)
        tx-node     (-> (txn/tx-node `[(f {})])
                      (assoc-in [::txn/elements 0 ::txn/started?] #{:remote})
                      (assoc ::txn/id (uuid 1))
                      (txn/dispatch-elements {} (fn [e] (m/mutate e))))
        mock-result {:x 1}]
    (swap! runtime-atom update ::txn/active-queue conj tx-node)

    (when-mocking
      (log/-log! _ _ _ _ _ _ _ args _ _)
      => (assertions
           "logs an error if the node is not found"
           (first @args) => "Network result for")

      (txn/record-result! app (uuid 2) 0 :remote mock-result))))

(specification "add-send!"
  (let [{:keys [::app/runtime-atom] :as app} (mock-app)
        tx-node (-> (txn/tx-node `[(f {})])
                  (assoc-in [::txn/elements 0 ::txn/started?] #{:remote})
                  (assoc ::txn/id (uuid 1))
                  (txn/dispatch-elements {} (fn [e] (m/mutate e))))
        {:keys [::txn/result-handler ::txn/update-handler]
         :as   resultant-node} (txn/add-send! app tx-node 0 :remote)]
    (component "Handlers"
      (behavior "update handler"
        (when-mocking
          (txn/record-result! app id ele-idx remote result k) => (assertions
                                                                   "Records the result under the progess key"
                                                                   k => ::txn/progress)
          (txn/schedule-queue-processing! app tm) => (assertions
                                                       "Schedules queue processing immediately."
                                                       tm => 0)

          (update-handler {})))
      (behavior "result handler"
        (when-mocking
          (txn/record-result! app id ele-idx remote result) => (assertions
                                                                 "Records the result"
                                                                 ele-idx => 0)
          (txn/remove-send! app remote id ele-idx) => (assertions
                                                        "Removes the send from the queue"
                                                        ele-idx => 0)
          (txn/schedule-queue-processing! app tm) => (assertions
                                                       "schedules queue processing immediately"
                                                       tm => 0)
          (txn/schedule-sends! app tm) => (assertions
                                            "Schedules a scan for news sends"
                                            (int? tm) => true)

          (result-handler {}))))))

(specification "queue-element-sends!"
  (let [{:keys [::app/runtime-atom] :as app} (mock-app)
        tx-node (-> (txn/tx-node `[(f {})])
                  (assoc ::txn/id (uuid 1))
                  (txn/dispatch-elements {} (fn [e] (m/mutate e))))
        element (-> tx-node ::txn/elements first)
        called  (atom 0)]

    (when-mocking
      (txn/add-send! app n idx remote) => (do
                                            (swap! called inc)
                                            (assertions
                                              "Adds the send"
                                              remote => :remote
                                              idx => 0
                                              (= (::txn/id n) (::txn/id tx-node)) => true))

      (let [new-tx-node (txn/queue-element-sends! app tx-node element)]

        (assertions
          "Marks the element as started"
          (-> new-tx-node ::txn/elements first ::txn/started?) => #{:remote})

        ;; run against new node (which is started)
        (txn/queue-element-sends! app new-tx-node element)
        (assertions
          "skips nodes that are already started"
          (= 1 @called) => true)))))

(specification "idle-node?"
  (let [idle-node               (-> (txn/tx-node `[(f {}) (g {})])
                                  (txn/dispatch-elements {} (fn [e] (m/mutate e))))
        partially-complete-node (-> idle-node
                                  (update-in [::txn/elements 0 ::txn/started?] conj :remote)
                                  (update-in [::txn/elements 0 ::txn/complete?] conj :remote))
        node-with-active-ele    (-> partially-complete-node
                                  (update-in [::txn/elements 1 ::txn/started?] conj :remote))]
    (assertions
      "returns true for nodes that have no elements that are started"
      (txn/idle-node? idle-node) => true
      "returns true for nodes that have completed network activity"
      (txn/idle-node? partially-complete-node) => true
      "returns false for nodes that have started, but not completed network activity"
      (txn/idle-node? node-with-active-ele) => false)))

(specification "element-with-work"
  (let [idle-node        (-> (txn/tx-node `[(f {})])
                           (txn/dispatch-elements {} (fn [e] (m/mutate e))))
        element          (get-in idle-node [::txn/elements 0])
        active-element   (update element ::txn/started? conj :remote)
        complete-element (update active-element ::txn/complete? conj :remote)]

    (assertions
      "Returns the element provided iff it has unqueud remote work"
      (txn/element-with-work #{:remote} element) => element
      (txn/element-with-work #{:remote} active-element) => nil
      (txn/element-with-work #{:remote} complete-element) => nil)))

(specification "queue-next-send!"
  (let [app                    (mock-app)
        idle-node              (-> (txn/tx-node `[(f {}) (g {})])
                                 (txn/dispatch-elements {} (fn [e] (m/mutate e))))
        f-ele                  (get-in idle-node [::txn/elements 0])
        g-ele                  (get-in idle-node [::txn/elements 1])
        node-after-first-queue (update-in idle-node [::txn/elements 0 ::txn/started?] conj :remote)
        node-f-complete        (update-in node-after-first-queue [::txn/elements 0 ::txn/complete?] conj :remote)]

    (when-mocking
      (txn/queue-element-sends! app n e) =1x=> (do
                                                 (assertions
                                                   "queues only the first pending element "
                                                   (= e f-ele) => true)
                                                 n)

      (txn/queue-next-send! app idle-node)
      ;; this second one should not cause the mock to be called again
      (let [actual (txn/queue-next-send! app node-after-first-queue)]
        (assertions
          "when the node is not idle, it returns the unmodified node"
          (= actual node-after-first-queue) => true)))

    (when-mocking
      (txn/queue-element-sends! app n e) =1x=> (do
                                                 (assertions
                                                   "Queues the next when the prior is complete."
                                                   (= e g-ele) => true)
                                                 n)

      (txn/queue-next-send! app node-f-complete))))

(specification "queue-sends!"
  (let [app          (mock-app)
        pess-tx-node (txn/tx-node `[(f {}) (g {})] {:optimistic? false})
        opt-tx-node  (txn/tx-node `[(f {}) (g {})] {:optimistic? true})]
    (component "Pessimistic mode:"
      (when-mocking
        (txn/schedule-sends! a tm) =1x=> (assertions
                                           "Schedules an immediate processing of the send queue"
                                           tm => 0)
        (txn/queue-next-send! app n) =1x=> (do
                                             (assertions
                                               "Queues just the next element's remotes"
                                               (= n pess-tx-node) => true)
                                             (assoc n :updated 1))

        (let [actual (txn/queue-sends! app pess-tx-node)]
          (assertions
            "Returns the updated node"
            (:updated actual) => 1))))
    (component "Optimistic mode:"
      (let [f-ele (get-in opt-tx-node [::txn/elements 0])
            g-ele (get-in opt-tx-node [::txn/elements 1])]
        (when-mocking
          (txn/schedule-sends! a tm) =1x=> (assertions
                                             "Schedules an immediate processing of the send queue"
                                             tm => 0)
          (txn/queue-element-sends! app n e) =1x=> (do
                                                     (assertions
                                                       "Queues the elements with remote operation"
                                                       (= e f-ele) => true)
                                                     (update n :updated (fnil inc 0)))
          (txn/queue-element-sends! app n e) =1x=> (do
                                                     (assertions
                                                       "Queues the elements with remote operation"
                                                       (= e g-ele) => true)
                                                     (update n :updated inc))

          (let [actual (txn/queue-sends! app opt-tx-node)]
            (assertions
              "Returns the updated node"
              (:updated actual) => 2)))))))

(specification "dispatch-result!"
  (when-mocking
    (txn/schedule-queue-processing! a t) => nil
    (txn/build-env app n) => {:mock-env true}

    (let [app         (mock-app)
          mock-result {:status-code 200 :value 20}
          actual-env  (atom nil)
          mock-action (fn [env] (reset! actual-env env))
          tx-node     (-> (txn/tx-node `[(f {})])
                        (txn/dispatch-elements {} (fn [e] (m/mutate e)))
                        (assoc-in [::txn/elements 0 ::txn/results :remote] mock-result)
                        (update-in [::txn/elements 0 ::txn/dispatch] assoc :result-action mock-action))
          ele         (get-in tx-node [::txn/elements 0])
          updated-ele (txn/dispatch-result! app tx-node ele :remote)]

      (assertions
        "Uses `build-env` to build the env"
        (-> actual-env deref :mock-env) => true
        "Calls the :result-action handler"
        (nil? (deref actual-env)) => false
        "Passes the result on the :result key of env"
        (= mock-result (-> actual-env deref :result)) => true
        "Marks the element as complete"
        (::txn/complete? updated-ele) => #{:remote})))

  (behavior "When the handler throws an exception"
    (when-mocking
      (txn/schedule-queue-processing! a t) => nil
      (log/-log! _ _ _ _ _ _ _ args _ _)
      =1x=> (assertions
              "Logs an error about the failed handler"
              (second @args) => "The result-action mutation handler for mutation")

      (let [app         (mock-app)
            mock-action (fn [env] (throw (ex-info "INTENTIONALLY THROWN" {})))
            tx-node     (-> (txn/tx-node `[(f {})])
                          (txn/dispatch-elements {} (fn [e] (m/mutate e)))
                          (assoc-in [::txn/elements 0 ::txn/results :remote] {})
                          (update-in [::txn/elements 0 ::txn/dispatch] assoc :result-action mock-action))
            ele         (get-in tx-node [::txn/elements 0])
            updated-ele (txn/dispatch-result! app tx-node ele :remote)]

        (assertions
          "Marks the element as complete"
          (::txn/complete? updated-ele) => #{:remote}))))

  (behavior "When the handler is missing (ignored result)"
    (let [app         (mock-app)
          tx-node     (-> (txn/tx-node `[(f {})])
                        (txn/dispatch-elements {} (fn [e] (m/mutate e)))
                        (assoc-in [::txn/elements 0 ::txn/results :remote] {}))
          ele         (get-in tx-node [::txn/elements 0])
          updated-ele (txn/dispatch-result! app tx-node ele :remote)]

      (assertions
        "Marks the element as complete"
        (::txn/complete? updated-ele) => #{:remote}))))

(defmutation multi-remote [p]
  (remote [env] true)
  (rest [env] true)
  (graphql [env] true))

(specification "distribute-element-results!"
  (when-mocking!
    (txn/dispatch-result! a n e r) =1x=> (do
                                           (assertions
                                             "Dispatches the result of each non-complete remote"
                                             r => :graphql)
                                           (update e ::txn/complete? conj r))
    (txn/dispatch-result! a n e r) =1x=> (do
                                           (assertions
                                             "Dispatches the result of each non-complete remote"
                                             r => :remote)
                                           (update e ::txn/complete? conj r))

    (let [app         (mock-app)
          _           (swap! (::app/runtime-atom app) assoc ::app/remotes {:remote  {:transmit! (fn [send])}
                                                                           :rest    {:transmit! (fn [send])}
                                                                           :graphql {:transmit! (fn [send])}})
          mock-result {:status-code 200 :value 20}
          tx-node     (-> (txn/tx-node `[(multi-remote {})])
                        (txn/dispatch-elements {} (fn [e] (m/mutate e)))
                        (assoc-in [::txn/elements 0 ::txn/started?] #{:remote :rest :graphql})
                        (assoc-in [::txn/elements 0 ::txn/complete?] #{:rest})
                        (assoc-in [::txn/elements 0 ::txn/results] (sorted-map ; so that the test is consistent
                                                                     :remote mock-result
                                                                     :rest mock-result
                                                                     :graphql mock-result)))
          ele         (get-in tx-node [::txn/elements 0])
          updated-ele (txn/distribute-element-results! app tx-node ele)]

      (assertions
        "Returns the update element"
        (::txn/complete? updated-ele) => #{:remote :rest :graphql}))))

(specification "distribute-results!"
  (when-mocking!
    (txn/distribute-element-results! a n e) =2x=> (do
                                                    (assertions
                                                      "Uses distribute-element-results! on each element of the node"
                                                      (contains? #{0 1} (::txn/idx e)) => true)
                                                    (assoc e :touched? true))

    (let [app          (mock-app)
          tx-node      (txn/tx-node `[(f {}) (g {})])
          updated-node (txn/distribute-results! app tx-node)]

      (assertions
        "Returns the updated node where each element has been processed"
        (vector? (get-in updated-node [::txn/elements])) => true
        (get-in updated-node [::txn/elements 0 :touched?]) => true
        (get-in updated-node [::txn/elements 0 :touched?]) => true))))

(specification "update-progress!"
  (when-mocking!
    (txn/build-env app n extra) => (merge extra {:mock-env true})

    (let [app           (mock-app)
          mock-progress {:done 20}
          actual-env    (atom nil)
          mock-action   (fn [env] (reset! actual-env env))
          tx-node       (-> (txn/tx-node `[(f {}) (g {})])
                          (txn/dispatch-elements {} (fn [e] (m/mutate e)))
                          (assoc-in [::txn/elements 0 ::txn/progress :remote] mock-progress)
                          (assoc-in [::txn/elements 1 ::txn/progress :remote] mock-progress)
                          (update-in [::txn/elements 0 ::txn/dispatch] assoc :progress-action mock-action))
          updated-node  (txn/update-progress! app tx-node)]

      (assertions
        "Uses `build-env` to build the env"
        (-> actual-env deref :mock-env) => true
        "Calls the :progress-action handler, when defined"
        (nil? (deref actual-env)) => false
        "Passes the progress on the :progress key of env"
        (= mock-progress (-> actual-env deref :progress)) => true
        "Removes the progress markers from the elements"
        (get-in updated-node [::txn/elements 0 ::txn/progress]) => nil
        (get-in updated-node [::txn/elements 1 ::txn/progress]) => nil))))

(specification "process-tx-node!"
  (behavior "returns nil when run on fully complete nodes"
    (when-mocking!
      (txn/fully-complete? app n) => true

      (let [app         (mock-app)
            opt-tx-node (txn/tx-node `[(f {}) (g {})] {:optimistic? true})]

        (assertions
          (txn/process-tx-node! app opt-tx-node) => nil))))

  (behavior "Optimistic mode"
    (let [app         (mock-app)
          opt-tx-node (txn/tx-node `[(f {}) (g {})] {:optimistic? true})]

      (when-mocking!
        (txn/fully-complete? app n) => false
        (txn/run-actions! app n) =1x=> (assoc n :ran-actions? true)
        (txn/queue-sends! a n) =1x=> (assoc n :queued-sends? true)
        (txn/update-progress! a n) =1x=> (assoc n :updated-progress? true)
        (txn/distribute-results! a n) =1x=> (assoc n :distributed-results? true)

        (let [actual (txn/process-tx-node! app opt-tx-node)]
          (assertions
            "Returns the updated node"
            (s/valid? ::txn/tx-node actual) => true
            (:ran-actions? actual) => true
            (:queued-sends? actual) => true
            (:updated-progress? actual) => true
            (:distributed-results? actual) => true)))))

  (behavior "Pessimistic mode"
    (let [app     (mock-app)
          tx-node (txn/tx-node `[(f {}) (g {})] {:optimistic? false})]

      (when-mocking!
        (txn/fully-complete? app n) => false
        (txn/advance-actions! app n) =1x=> (assoc n :advanced-actions? true)
        (txn/queue-sends! a n) =1x=> (assoc n :queued-sends? true)
        (txn/update-progress! a n) =1x=> (assoc n :updated-progress? true)
        (txn/distribute-results! a n) =1x=> (assoc n :distributed-results? true)

        (let [actual (txn/process-tx-node! app tx-node)]
          (assertions
            "Returns the updated node"
            (s/valid? ::txn/tx-node actual) => true
            (:advanced-actions? actual) => true
            (:queued-sends? actual) => true
            (:updated-progress? actual) => true
            (:distributed-results? actual) => true))))))

(specification "process-queue!"
  (behavior "Walks the active queue and runs the processing step on each node"
    (when-mocking
      (txn/process-tx-node! a n) =1x=> (do
                                         (assertions
                                           "Processes each node through process-tx-node!"
                                           (::txn/id n) => (uuid 1))
                                         n)
      (txn/process-tx-node! a n) =1x=> (do
                                         (assertions
                                           "Processes each node through process-tx-node!"
                                           (::txn/id n) => (uuid 2))
                                         nil)
      (app/schedule-render! a) => nil

      (let [{:keys [::app/runtime-atom] :as app} (-> (mock-app) (ah/with-optimized-render (fn
                                                                                            ([app force-root?])
                                                                                            ([app]))))
            active-queue [(assoc (txn/tx-node `[(f {})]) ::txn/id (uuid 1))
                          (assoc (txn/tx-node `[(g {})]) ::txn/id (uuid 2))]]
        (swap! runtime-atom assoc ::txn/active-queue active-queue)

        (txn/process-queue! app)

        (assertions
          "Updates the live active queue, removing any nodes that came back nil from processing"
          (get @runtime-atom ::txn/active-queue) => [(first active-queue)])))))

(specification "query-ast?"
  (assertions
    "Correctly detects queries on full AST"
    (txn/query-ast? (eql/query->ast [:x {:z [:a]} {:y {:a [:x] :b [:y]}}])) => true
    (txn/query-ast? (eql/query->ast [:x])) => true
    "Correctly detects queries on AST node"
    (txn/query-ast? (eql/query->ast1 `[:x])) => true
    "Correctly identifies mutation AST"
    (txn/query-ast? (eql/query->ast `[(f)])) => false
    "Correctly identifies mutation AST node"
    (txn/query-ast? (eql/query->ast1 `[(f)])) => false))

(specification "mutation-ast?"
  (assertions
    "Correctly detects mutations on full AST"
    (txn/mutation-ast? (eql/query->ast `[(f {})])) => true
    "Correctly detects mutation joins on full AST"
    (txn/mutation-ast? (eql/query->ast `[{(f {}) [:x]}])) => true
    "Correctly detects mutations on AST node"
    (txn/mutation-ast? (eql/query->ast1 `[(f)])) => true
    "Correctly detects mutation joins on AST node"
    (txn/mutation-ast? (eql/query->ast1 `[{(f) [:x]}])) => true))

(specification "sort-queue-writes-before-reads"
  (behavior "non-active nodes"
    (let [send-queue [(->send 1 0 [:x :y {:z [:j]}] {})
                      (->send 2 0 `[(f {})] {})
                      (->send 2 1 `[(g {})] {})
                      (->send 3 0 `[(f {})] {})]]
      (let [actual (txn/sort-queue-writes-before-reads send-queue)]
        (assertions
          "Preserves the length of the queue"
          (count actual) => (count send-queue)
          "Moves the read(s) to the end"
          (last actual) => (first send-queue)
          "Preserves the relative order of writes"
          (butlast actual) => (rest send-queue)))))

  (behavior "when there are active nodes"
    (let [send-queue [(assoc (->send 1 0 [:x :y {:z [:j]}] {}) ::txn/active? true)
                      (->send 2 0 `[(f {})] {})]]
      (let [actual (txn/sort-queue-writes-before-reads send-queue)]
        (assertions
          "Leaves active nodes on the front of the queue"
          (first actual) => (first send-queue))))))

(specification "combine-sends"
  (behavior "Combines related nodes in the send queue:"
    (let [app                 (mock-app)
          original-send-queue [(->send 2 0 `[(f {:x 1})] {})
                               (->send 2 1 `[(g {:y 2})] {})
                               (->send 3 0 `[(f {})] {})]
          send-queues         {:remote original-send-queue}]
      (swap! (::app/runtime-atom app) assoc ::txn/send-queues send-queues)

      (when-mocking!
        (txn/sort-queue-writes-before-reads q) =1x=> (do
                                                       (assertions
                                                         "sorts the queue"
                                                         (= q original-send-queue) => true)
                                                       q)

        (let [{:keys [::txn/send-node ::txn/send-queue]} (txn/combine-sends app :remote original-send-queue)]
          (behavior "Creates a new combined node that: "
            (assertions
              "has a combined AST for the first real send"
              (eql/ast->query (::txn/ast send-node)) => `[(f {:x 1}) (g {:y 2})]
              "is marked active"
              (::txn/active? send-node) => true))
          (behavior "creates an updated send queue that"
            (assertions
              "starts with the combined node"
              (= send-node (first send-queue)) => true
              "includes the unsent nodes"
              (= (last original-send-queue) (last send-queue)) => true))))))

  (component "The combined node"
    (let [app                 (mock-app)
          f-result            (atom nil)
          g-result            (atom nil)
          f-update            (atom nil)
          g-update            (atom nil)
          original-send-queue [(assoc (->send 2 0 `[(f {:x 1})] {})
                                 ::txn/update-handler (fn [result] (reset! f-update result))
                                 ::txn/result-handler (fn [result] (reset! f-result result)))
                               (assoc (->send 2 0 `[(g {:x 1})] {})
                                 ::txn/update-handler (fn [result] (reset! g-update result))
                                 ::txn/result-handler (fn [result] (reset! g-result result)))]
          send-queues         {:remote original-send-queue}]
      (swap! (::app/runtime-atom app) assoc ::txn/send-queues send-queues)

      (when-mocking!
        (txn/sort-queue-writes-before-reads q) =1x=> q

        (let [{:keys [::txn/send-node]} (txn/combine-sends app :remote original-send-queue)
              {:keys [::txn/result-handler ::txn/update-handler]} send-node
              progress-message {:progress 50 :body {:x 1}}
              network-result   {:status-code 200
                                :body        {`f {:x 1}
                                              `g {:y 2}}}]

          (update-handler progress-message)
          (behavior "has an update handler that: "
            (assertions
              "Sends the update to all original node update handlers"
              (-> f-update deref) => progress-message
              (-> g-update deref) => progress-message))

          (result-handler network-result)
          (behavior "has a result handler that: "
            (assertions
              "Distributes results to the original node handlers"
              (-> f-result deref nil?) => false
              (-> g-result deref nil?) => false
              "Narrows the result body to only contain the mutation key of interest"
              (-> f-result deref :body keys set) => #{`f}
              (-> g-result deref :body keys set) => #{`g}
              "Includes the rest of the combined result"
              (-> f-result deref :status-code) => 200)))))))

(defmutation b1 [params]
  (action [env]
    (log/info "Optimistic b1"))
  (result-action [env]
    (log/info "Result b1: " (:result env)))
  (remote [env] true))

(defmutation b2 [params]
  (action [{:keys [state] :as env}]
    (log/info "Optimistic b2")
    (swap! state assoc :X 1))
  (ok-action [{:keys [state] :as env}]
    (swap! state assoc :Y 2)
    (log/info "OK Action called" (-> env :result :body (get `bam!))))
  (result-action [{:keys [dispatch] :as env}]
    (log/info "Result action b2: " (:result env))
    ((:ok-action dispatch) env))
  (remote [env] (eql/query->ast `[(bam! ~params)])))
