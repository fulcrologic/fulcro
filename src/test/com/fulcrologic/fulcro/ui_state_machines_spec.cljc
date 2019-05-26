(ns com.fulcrologic.fulcro.ui-state-machines-spec
  (:require
    [clojure.spec.alpha :as s]
    [fulcro-spec.core :refer [specification provided provided! when-mocking when-mocking! behavior assertions component]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.algorithms.data-targeting :as dft]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [taoensso.timbre :as log]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]))

(declare => =1x=>)

(def mock-app (app/fulcro-app))

(defn mutation-env [state-atom tx extra-env]
  (-> mock-app
    (assoc ::app/state-atom state-atom)
    (txn/build-env (txn/tx-node tx) extra-env)
    (assoc :ast (eql/query->ast1 tx))))

#_(defn mock-component
  "Returns a shallow-rendered react Component (rendered instance) that prim/component? will return true for."
  [fulcro-class props]
  #?(:clj  {}
     :cljs (.instance (shallow (binding [comp/*app* mock-app]
                                 ((comp/factory fulcro-class) props))))))

(defsc AClass [_ _] {:query ['*] :ident (fn [] [:A 1])})
(defn A-handler [env] env)
(uism/defstatemachine test-machine
  {::uism/actor-names #{:dialog}
   ::uism/aliases     {:visible?    [:dialog :ui/active?]
                       :title       [:dialog :title]
                       :widgets     [:dialog :linked-widgets]
                       :uberwidgets [:dialog :other-widgets]
                       :username    [:dialog :name]}
   ::uism/plugins     {:f? (fn [{:keys [visible? username]}]
                             (when visible? username))}
   ::uism/states      {:initial {::uism/handler (fn [env]
                                                  (with-meta env {:handler-ran true}))}

                       :B       {::uism/events {:bang! {::uism/handler         (fn [env] (uism/store env :handler-ran? true))
                                                        ::uism/target-state    :A
                                                        ::uism/event-predicate (fn [env] (uism/retrieve env :enabled? false))}}}
                       :C       {::uism/events {:bang! {::uism/handler      (fn [env] (uism/store env :handler-ran? true))
                                                        ::uism/target-state :A}}}
                       :D       {::uism/events {:bang! {::uism/handler      (fn [env] (uism/activate env :A))
                                                        ::uism/target-state :B}}}
                       :A       {::uism/handler A-handler}}})

(def base-fulcro-state
  {:TABLE        {1 {:ui/active?     false :name "Joe"
                     :other-widgets  []
                     :linked-widgets [[:widget/by-id 1]]}
                  2 {:ui/active?     true :name "Jane"
                     :other-widgets  []
                     :linked-widgets [[:widget/by-id 2]]}}
   :widget/by-id {1 {:id 1}
                  2 {:id 2}
                  3 {:id 3}}
   ::uism/asm-id {:fake (uism/new-asm
                          {::uism/state-machine-id      `test-machine
                           ::uism/asm-id                :fake
                           ::uism/actor->component-name {:dialog (uism/any->actor-component-registry-key AClass)}
                           ::uism/actor->ident          {:dialog [:TABLE 1]}})}})
(defn test-env [event-id event-data]
  (uism/state-machine-env base-fulcro-state [:TABLE 1] :fake event-id event-data))

(specification "State Machine Registry"
  (assertions
    "Registers the FQ symbol of the state machine"
    (map? (uism/get-state-machine `test-machine)) => true
    "Stores the definition at that symbol"
    (::uism/actor-names (uism/get-state-machine `test-machine)) => #{:dialog}
    "Allows lookup of a value via an active env and a machine key"
    (uism/lookup-state-machine-field (test-env :event nil) ::uism/actor-names) => #{:dialog}))

(specification "state-machine-env"
  (assertions
    "produces a spec-compliant result"
    (s/valid? ::uism/env (uism/state-machine-env {} [:a 1] :x :evt {})) => true))

(let [env (test-env nil nil)]
  (specification "asm-value"
    (assertions "Finds things in the active state machine"
      (uism/asm-value env ::uism/asm-id) => :fake))

  (specification "activate"
    (assertions
      "Sets the given active state in the env"
      (-> env
        (uism/activate :A)
        (uism/asm-value ::uism/active-state)) => :A)
    (assertions
      "Ignores a requst to move to an invalid state (logs an error)"
      (-> env
        (uism/activate :crap)
        (uism/asm-value ::uism/active-state)) => :initial))

  (specification "store/retrieve"
    (assertions
      "Allows for the local storage or asm-local values"
      (-> env (uism/store :x true) (uism/retrieve :x)) => true
      (-> env (uism/store :x 0) (uism/retrieve :x)) => 0
      (-> env (uism/store :x {}) (uism/retrieve :x)) => {}
      (-> env (uism/store :v 1) (uism/retrieve :v)) => 1))

  (specification "resolve-alias"
    (assertions
      "Returns the Fulcro state path for a given data alias"
      (uism/resolve-alias env :username) => [:TABLE 1 :name])

    (assertions
      "Returns nil if it is an invalid alias"
      (uism/resolve-alias env :crap) => nil))

  (specification "actor-path"
    (assertions
      "Returns the Fulcro ident of an actor"
      (uism/actor-path env :dialog) => [:TABLE 1]
      "Returns the Fulcro path to data in an actor if a field is included"
      (uism/actor-path env :dialog :boo) => [:TABLE 1 :boo]))

  (specification "actor-class"
    (assertions
      "Returns the Fulcro class of an actor"
      (uism/actor-class env :dialog) => AClass))

  (specification "set-actor-value"
    (assertions
      "Sets a raw (non-aliased) attribute in Fulcro state on an actor"
      (-> env
        (uism/set-actor-value :dialog :boo 42)
        ::uism/state-map
        :TABLE
        (get 1)
        :boo) => 42

      "Which can be read by actor-value"
      (-> env
        (uism/set-actor-value :dialog :boo 42)
        (uism/actor-value :dialog :boo)) => 42))

  (specification "alias-value"
    (assertions
      "Returns nil if the alias isn't valid"
      (uism/alias-value env :name) => nil
      "Gets the value of the fulro state that the alias refers to"
      (uism/alias-value env :username) => "Joe"))

  (specification "set-aliased-value"
    (assertions
      "Can set a single value"
      (-> env
        (uism/set-aliased-value :title "Hello")
        (uism/alias-value :title)) => "Hello"
      "Can set two values"
      (-> env
        (uism/set-aliased-value :title "Hello" :username "Joe")
        (uism/alias-value :title)) => "Hello"
      (-> env
        (uism/set-aliased-value :title "Hello" :username "Joe")
        (uism/alias-value :username)) => "Joe"
      "Can set more than 2 values"
      (-> env
        (uism/set-aliased-value :title "Hello" :username "Joe" :visible? :booga)
        (uism/alias-value :visible?)) => :booga)

    (assertions
      "Returns unmodified env if the alias isn't valid"
      (uism/set-aliased-value env :name "Sam") => env
      "Sets the value in the fulro state that the alias refers to"
      (-> env
        (uism/set-aliased-value :username "Sam")
        ::uism/state-map
        :TABLE
        (get 1)
        :name) => "Sam"))

  (specification "reset-actor-ident"
    (let [env (uism/reset-actor-ident env :dialog [:TABLE 2])]
      (assertions
        "Can update actor/ident indexes"
        (uism/asm-value env ::uism/actor->ident) => {:dialog [:TABLE 2]}
        (uism/asm-value env ::uism/ident->actor) => {[:TABLE 2] :dialog}
        "Class index is correct"
        (uism/actor-class env :dialog) => AClass
        (uism/asm-value env ::uism/actor->component-name) => {:dialog ::AClass}))

    (assertions
      "Returns unmodified env if the alias isn't valid"
      (uism/set-aliased-value env :name "Sam") => env
      "Sets the value in the fulro state that the alias refers to"
      (-> env
        (uism/assoc-aliased :username "Sam")
        ::uism/state-map
        :TABLE
        (get 1)
        :name) => "Sam"))

  (specification "assoc-aliased"
    (assertions
      "Can set a single value"
      (-> env
        (uism/assoc-aliased :title "Hello")
        (uism/alias-value :title)) => "Hello"
      "Can set two values"
      (-> env
        (uism/assoc-aliased :title "Hello" :username "Joe")
        (uism/alias-value :title)) => "Hello"
      (-> env
        (uism/assoc-aliased :title "Hello" :username "Joe")
        (uism/alias-value :username)) => "Joe"
      "Can set more than 2 values"
      (-> env
        (uism/assoc-aliased :title "Hello" :username "Joe" :visible? :booga)
        (uism/alias-value :visible?)) => :booga)

    (assertions
      "Returns unmodified env if the alias isn't valid"
      (uism/set-aliased-value env :name "Sam") => env
      "Sets the value in the fulro state that the alias refers to"
      (-> env
        (uism/assoc-aliased :username "Sam")
        ::uism/state-map
        :TABLE
        (get 1)
        :name) => "Sam"))

  (specification "update-aliased"
    (assertions
      "Can update a value"
      (-> env
        (uism/assoc-aliased :title "Hello")
        (uism/update-aliased :title #(str % "-suffix"))
        (uism/alias-value :title)) => "Hello-suffix"
      "Can update a value with arguments"
      (-> env
        (uism/assoc-aliased :title "Hello")
        (uism/update-aliased :title #(str %1 %2 %3) 1 2)
        (uism/alias-value :title)) => "Hello12"))

  (specification "dissoc-aliased"
    (let [env (uism/assoc-aliased env :title "Hello" :username "Joe")]
      (let [entries (-> env
                      (uism/dissoc-aliased :title)
                      (get-in [::uism/state-map :TABLE 1])
                      (-> keys set))]
        (assertions
          "Can dissoc a single alias"
          (contains? entries :title) => false))
      (let [entries (-> env
                      (uism/dissoc-aliased :title :username)
                      (get-in [::uism/state-map :TABLE 1])
                      (-> keys set))]
        (assertions
          "Can dissoc multiple aliases"
          (contains? entries :title) => false
          (contains? entries :name) => false))))

  (specification "integrate-ident"
    (assertions
      "Accepts a list of operations"
      (let [nenv (uism/integrate-ident env [:widget/by-id 2] :prepend :widgets
                   :append :uberwidgets)]
        (select-keys (get-in nenv [::uism/state-map :TABLE 1]) #{:other-widgets :linked-widgets}))
      => {:linked-widgets [[:widget/by-id 2] [:widget/by-id 1]]
          :other-widgets  [[:widget/by-id 2]]}
      "Can prepend an ident"
      (-> env
        (uism/integrate-ident [:widget/by-id 2] :prepend :widgets)
        (get-in [::uism/state-map :TABLE 1 :linked-widgets])
        ) => [[:widget/by-id 2] [:widget/by-id 1]]
      "Can append an ident"
      (-> env
        (uism/integrate-ident [:widget/by-id 3] :append :widgets)
        (get-in [::uism/state-map :TABLE 1 :linked-widgets])
        ) => [[:widget/by-id 1] [:widget/by-id 3]]
      "Integrating an already present ident has no effect"
      (-> env
        (uism/integrate-ident [:widget/by-id 1] :prepend :widgets)
        (get-in [::uism/state-map :TABLE 1 :linked-widgets])
        ) => [[:widget/by-id 1]]
      (-> env
        (uism/integrate-ident [:widget/by-id 1] :append :widgets)
        (get-in [::uism/state-map :TABLE 1 :linked-widgets])
        ) => [[:widget/by-id 1]]))

  (specification "remove-ident"
    (assertions
      "Can remove an ident"
      (-> env
        (uism/remove-ident [:widget/by-id 1] :widgets)
        (get-in [::uism/state-map :TABLE 1 :linked-widgets])
        ) => []
      "Removing a non-present ident has no effect"
      (-> env
        (uism/remove-ident [:widget/by-id 2] :widgets)
        (get-in [::uism/state-map :TABLE 1 :linked-widgets])
        ) => [[:widget/by-id 1]]))

  (specification "aliased-data"
    (assertions
      "Builds a map of all of the current values of aliased data"
      (-> env
        (uism/aliased-data)) => {:visible?    false
                                 :title       nil
                                 :widgets     [[:widget/by-id 1]]
                                 :uberwidgets []
                                 :username    "Joe"}))

  (specification "run"
    (assertions
      "Runs a plugin against the env"
      (-> env
        (uism/set-aliased-value :visible? true)
        (uism/run :f?)) => "Joe"

      "Can be passed extra data that can overwrite aliased values"
      (-> env
        (uism/set-aliased-value :visible? true)
        (uism/run :f? {:visible? false})) => nil))

  (specification "active-state-handler"
    (provided! "The state machine definition is missing or the active state has no handler"
      (uism/lookup-state-machine e) => (do
                                         (assertions
                                           e => env)
                                         test-machine)
      (uism/asm-value e k) => (do
                                (assertions
                                  e => env
                                  k => ::uism/active-state)
                                :boo)

      (assertions
        "returns core identity"
        (uism/active-state-handler env) => identity))
    (let [env     (-> (test-env :bang! nil) (uism/activate :B) (uism/store :enabled? true))
          handler (fn geh* [e] e)]
      (provided! "The state machine definition has event definitions"
        (uism/lookup-state-machine e) => (do
                                           (assertions
                                             e => env)
                                           test-machine)
        (uism/asm-value e k) => (do
                                  (assertions
                                    e => env
                                    k => ::uism/active-state)
                                  :boo)

        (uism/generic-event-handler e) => handler

        (assertions
          "returns a generic-event-handler"
          (uism/active-state-handler env) => handler)))
    (provided! "The state machine definition exists and there is an active state."
      (uism/lookup-state-machine e) => (do
                                         (assertions
                                           e => env)
                                         test-machine)
      (uism/asm-value e k) => (do
                                (assertions
                                  e => env
                                  k => ::uism/active-state)
                                :A)

      (assertions
        "returns the correct handler"
        (uism/active-state-handler env) => A-handler)))

  (specification "generic-event-handler"
    (let [disabled-env              (-> (test-env :bang! nil) (uism/activate :B) (uism/store :enabled? false))
          enabled-env               (-> disabled-env (uism/store :enabled? true))
          non-event-env             (-> (test-env :boo nil) (uism/activate :A))
          missing-handler           (uism/generic-event-handler non-event-env)
          disabled-handler          (uism/generic-event-handler disabled-env)
          enabled-handler           (uism/generic-event-handler enabled-env)
          no-predicate-env          (-> enabled-env (uism/activate :C))
          no-predicate-handler      (uism/generic-event-handler no-predicate-env)
          overriding-handler-env    (-> disabled-env (uism/activate :D))
          overriding-handler        (uism/generic-event-handler overriding-handler-env)

          actual-disabled-result    (disabled-handler disabled-env)
          actual-enabled-result     (enabled-handler enabled-env)
          no-predicate-result       (no-predicate-handler no-predicate-env)
          overriding-handler-result (overriding-handler overriding-handler-env)]

      (behavior "when the event definition is missing"
        (assertions
          "Returns nil"
          missing-handler => nil))

      (behavior "When there is no predicate"
        (assertions
          "Runs the handler"
          (-> no-predicate-result (uism/retrieve :handler-ran? false)) => true
          "Follows the transition, if defined"
          (-> no-predicate-result (uism/asm-value ::uism/active-state)) => :A))
      (behavior "when the predicate is false"
        (assertions
          "Does not runs the handler"
          (-> actual-disabled-result (uism/retrieve :handler-ran? false)) => false
          "Stays in the same state"
          (-> actual-disabled-result (uism/asm-value ::uism/active-state)) => :B))
      (behavior "when the predicate is true"
        (assertions
          "Runs the handler"
          (-> actual-enabled-result (uism/retrieve :handler-ran? false)) => true
          "Follows the transition, if defined"
          (-> actual-enabled-result (uism/asm-value ::uism/active-state)) => :A))
      (behavior "when the handler activates a target state different from the *declared* target state"
        (assertions
          "The handler wins"
          (-> overriding-handler-result (uism/asm-value ::uism/active-state)) => :A))))

  (specification "apply-event-value"
    (assertions
      "returns an unmodified env for other events"
      (uism/apply-event-value env {::uism/event-id :random-event}) => env
      "applies a change to fulcro state based on the ::value-changed event"
      (-> env
        (uism/apply-event-value {::uism/event-id   ::uism/value-changed
                                 ::uism/event-data {::uism/alias :visible?
                                                    :value       :new-value}})
        (uism/alias-value :visible?)) => :new-value))

  (specification "queue-mutations!"
    (let [mutation-1-descriptor {::uism/mutation-context :dialog
                                 ::uism/ok-event         :pow!
                                 ::uism/mutation         `a}
          mutation-2-descriptor {::uism/mutation-context :dialog
                                 ::uism/ok-event         :bam!
                                 ::uism/mutation         `b}
          menv1                 (assoc env ::uism/queued-mutations [mutation-1-descriptor])
          menv                  (assoc env ::uism/queued-mutations [mutation-1-descriptor
                                                                    mutation-2-descriptor])]
      (behavior "Walks the list of queued mutations in env"
        (when-mocking!
          (comp/transact! comp tx)
          =1x=> (assertions
                  "Calls transact with the (1st) mutation delegate and the mutation descriptor "
                  tx => `[(uism/mutation-delegate ~mutation-1-descriptor)])
          (comp/transact! comp tx)
          =1x=> (assertions
                  "Calls transact with the (2nd) mutation delegate and the mutation descriptor "
                  tx => `[(uism/mutation-delegate ~mutation-2-descriptor)])

          (uism/queue-mutations! mock-app menv)))))

  (specification "convert-load-options"
    (let [load-options (uism/convert-load-options env {::comp/component-class AClass ::uism/post-event :blah})]
      (assertions
        "always sets a fallback function (that will send a default load-error event)"
        (:fallback load-options) => `uism/handle-load-error
        "removes any of the UISM-specific options from the map"
        (contains? load-options ::comp/component-class) => false
        (contains? load-options ::uism/post-event) => false
        "defaults load markers to an explicit false"
        (false? (:marker load-options)) => true))
    (let [load-options (uism/convert-load-options env {::uism/post-event :blah})]
      (assertions
        "Sets the post event handler and post event if there is a post-event"
        (-> load-options :post-mutation-params ::uism/event-id) => :blah
        (:post-mutation load-options) => `uism/trigger-state-machine-event))
    (let [load-options (uism/convert-load-options env {::uism/fallback-event        :foo
                                                       ::uism/fallback-event-params {:y 1}})]
      (assertions
        "Sets fallback event and params if present"
        (-> load-options :post-mutation-params ::uism/error-event) => :foo
        (-> load-options :post-mutation-params ::uism/error-data) => {:y 1})))

  (specification "handle-load-error*"
    (behavior "When there is an error event in the original load request (post mutation params)"
      (when-mocking
        (comp/transact! r tx) => (let [{:keys [params]} (-> tx eql/query->ast1)
                                       {::uism/keys [event-id event-data asm-id]} params]
                                   (assertions
                                     "it triggers that event with the error data"
                                     event-id => :foo
                                     event-data => {:y 1}
                                     asm-id => :fake))
        (uism/handle-load-error* (app/fulcro-app {}) {:post-mutation-params {::uism/asm-id      :fake
                                                                             ::uism/error-event :foo
                                                                             ::uism/error-data  {:y 1}}})))
    (behavior "When the error event is not present in the original load request (post mutation params)"
      (when-mocking
        (comp/transact! r tx) => (let [{:keys [params]} (-> tx eql/query->ast1)
                                       {::uism/keys [event-id event-data asm-id]} params]
                                   (assertions
                                     "it triggers ::uism/load-error"
                                     event-id => ::uism/load-error
                                     asm-id => :fake))
        (uism/handle-load-error* (app/fulcro-app {}) {:post-mutation-params {::uism/asm-id :fake}}))))

  (specification "queue-actor-load!"
    (let [app          (app/fulcro-app {})
          env          (assoc env ::uism/queued-loads [])
          load-called? (atom false)]
      (when-mocking!
        (uism/actor->ident e actor) =1x=> [:actor 1]
        (df/load r ident class params) =1x=> (do
                                               (reset! load-called? true)
                                               (assertions
                                                 "Sends a real load to fulcro containing: the proper actor ident"
                                                 ident => [:actor 1]
                                                 "the correct component class"
                                                 class => AClass
                                                 "The params with specified load options"
                                                 params => {:marker false})
                                               true)
        (uism/queue-actor-load! app env :dialog AClass {:marker false}))))

  #?(:cljs
     (let [fulcro-state (atom {})
           mutation-env (mutation-env fulcro-state [] {:ref [:TABLE 1]})
           event        {::uism/event-id :boggle ::uism/asm-id :fake}]
       (specification "trigger-state-machine-event!"
         (let [handler    (fn [env] (assoc env :handler-ran true))
               final-env? (fn [env]
                            (assertions
                              "Receives the final env"
                              (:looked-up-env env) => true
                              (:applied-event-values env) => true
                              (:handler-ran env) => true))]
           (when-mocking!
             (uism/state-machine-env s r a e d) => (assoc env :looked-up-env true)
             (uism/clear-timeouts-on-event! env event) => (do
                                                            (assertions
                                                              "Clears any auto-cleared timeouts"
                                                              true => true)
                                                            (assoc env :cleared-timeouts event))
             (uism/active-state-handler e) => handler
             (uism/apply-event-value env evt) => (assoc env :applied-event-values true)

             ;; not calling the mocks on these will cause fail. our assertion is just
             ;; for pos output
             (uism/queue-mutations! r e) => (do (final-env? e)
                                                (assertions
                                                  "Tries to queue mutations using the final env"
                                                  true => true)
                                                nil)

             (uism/queue-loads! r env) => (do (final-env? env)
                                              (assertions
                                                "Tries to queue loads using the final env"
                                                true => true)
                                              nil)

             (uism/update-fulcro-state! env satom) => (do (final-env? env)
                                                          (assertions
                                                            "Tries to update fulcro state"
                                                            satom => fulcro-state)
                                                          nil)

             (uism/ui-refresh-list env) => [[:x :y]]

             (uism/trigger-queued-events! menv triggers list) =1x=> (do
                                                                      (assertions
                                                                        "processes events that handlers queued."
                                                                        (count triggers) => 0)
                                                                      list)

             ;; ACTION UNDER TEST
             (let [actual (uism/trigger-state-machine-event! mutation-env event)]
               (assertions
                 "returns the list of things to refresh in the UI"
                 actual => [[:x :y]])))))))

  (specification "trigger-queued-events!"
    (let [mutation-env (mutation-env (atom {}) [] {:ref [:TABLE 1]})
          event-1      {::uism/asm-id :a ::uism/event-id :event-1 ::uism/event-data {:a 1}}
          event-2      {::uism/asm-id :b ::uism/event-id :event-2 ::uism/event-data {:a 2}}
          triggers     [event-1 event-2]]
      (when-mocking!
        (uism/trigger-state-machine-event! menv event) =1x=> (do
                                                               (assertions
                                                                 "Triggers the queued event"
                                                                 event => event-1
                                                                 "with the mutation env"
                                                                 (= menv mutation-env) => true)
                                                               [[:b 2]])
        (uism/trigger-state-machine-event! menv event) =1x=> (do
                                                               (assertions
                                                                 "Triggers the queued event"
                                                                 event => event-2
                                                                 "with the mutation env"
                                                                 (= mutation-env menv) => true)
                                                               [[:c 3]])

        (let [actual (uism/trigger-queued-events! mutation-env triggers [[:A 1]])]
          (assertions
            "Accumulates and returns the actors to refresh"
            actual => [[:A 1] [:b 2] [:c 3]])))))

  (specification "trigger-state-machine-event mutation"
    (let [trigger {::uism/asm-id :a ::uism/event-id :x}
          menv    (mutation-env (atom {}) [(uism/trigger-state-machine-event trigger)] {})
          {:keys [action]} (m/mutate menv)]
      (when-mocking!
        (uism/trigger-state-machine-event! mutation-env p) => (do
                                                                (assertions
                                                                  "runs the state machine event"
                                                                  (= mutation-env menv) => true
                                                                  p => trigger)
                                                                [[:table 1]])
        (app/schedule-render! app) => (assertions
                                        "Queues the actors for UI refresh"
                                        (nil? app) => false)

        (action menv))))

  (specification "set-string!"
    (provided! "The user supplies a string"
      (uism/trigger! this smid event params) => (do
                                                  (assertions
                                                    "Triggers a value-changed event"
                                                    event => ::uism/value-changed
                                                    "on a named state machine"
                                                    smid => :fake
                                                    "whose parameters specify the new value"
                                                    (::uism/alias params) => :username
                                                    (:value params) => "hello")
                                                  nil)

      (uism/set-string! {} :fake :username "hello"))
    #?(:cljs
       (provided! "The user supplies a js DOM onChange event"
         (uism/trigger! this smid event params) => (do
                                                     (assertions
                                                       "Triggers a value-changed event"
                                                       event => ::uism/value-changed
                                                       "on a named state machine"
                                                       smid => :fake
                                                       "with the extracted string value"
                                                       (::uism/alias params) => :username
                                                       (:value params) => "hi")
                                                     nil)

         (uism/set-string! {} :fake :username #js {:target #js {:value "hi"}}))))

  #_(specification "derive-actor-components"
    (let [actual (uism/derive-actor-components {:a [:x 1]
                                                :b AClass
                                                :c (mock-component AClass {})
                                                :d (uism/with-actor-class [:A 1] AClass)})]
      (assertions
        "allows a bare ident (no mapping)"
        (:a actual) => nil
        "accepts a singleton classes"
        (:b actual) => ::AClass
        ;; Need enzyme configured consistently for this test
        "accepts a react instance"
        (:c actual) => ::AClass
        "finds class on metadata"
        (:d actual) => ::AClass)))
  #_(specification "derive-actor-idents"
    (let [actual (uism/derive-actor-idents {:a [:x 1]
                                            :b AClass
                                            :c (mock-component AClass {})
                                            :d (uism/with-actor-class [:A 1] AClass)})]
      (assertions
        "allows a bare ident"
        (:a actual) => [:x 1]
        "finds the ident on singleton classes"
        (:b actual) => [:A 1]
        "remembers the singleton class as metadata"
        (:b actual) => [:A 1]
        ;; Need enzyme configured consistently for this test
        "remembers the class of a react instance"
        (:c actual) => [:A 1]
        "records explicit idents that use with-actor-class"
        (:d actual) => [:A 1])))
  (specification "set-timeout"
    (let [new-env    (uism/set-timeout env :timer/my-timer :event/bam! {} 100)
          descriptor (some-> new-env ::uism/queued-timeouts first)]
      (assertions
        "Adds a timeout descriptor to the queued timeouts"
        (s/valid? ::uism/timeout-descriptor descriptor) => true
        "whose default auto-cancel is constantly false"
        (some-> descriptor ::uism/cancel-on meta :cancel-on (apply [:x])) => false)))

  (let [prior-timer           {::uism/timeout   100
                               ::uism/timer-id  :timer/id
                               ::uism/js-timer  (with-meta {} {:timer :mock-js-timer})
                               ::uism/event-id  :bam!
                               ::uism/cancel-on (with-meta {} {:cancel-on (constantly true)})}
        env-with-active-timer (assoc-in env (uism/asm-path env [::uism/active-timers :timer/id]) prior-timer)]
    (specification "schedule-timeouts!"
      (provided! "There isn't already a timer under that ID"
        (uism/get-js-timer e t) =1x=> nil
        (uism/set-js-timeout! f t) =1x=> (assertions
                                           "sets the low-level timer with the correct time"
                                           t => 100)

        (let [prepped-env (uism/set-timeout env :timer/id :bam! {} 100)
              new-env     (uism/schedule-timeouts! (app/fulcro-app {}) prepped-env)]

          (assertions
            "Adds a timeout descriptor to active timers"
            (s/valid? ::uism/timeout-descriptor
              (get-in new-env (uism/asm-path new-env [::uism/active-timers :timer/id])))
            => true)))

      (provided! "There IS a timer under that ID"
        (uism/get-js-timer e t) =1x=> :low-level-js-timer
        (uism/clear-js-timeout! t) =1x=> (assertions
                                           "Clears the old timer"
                                           t => :low-level-js-timer)
        (uism/set-js-timeout! f t) =1x=> (assertions
                                           "sets the low-level timer with the correct time"
                                           t => 300)
        (let [prepped-env (uism/set-timeout env-with-active-timer :timer/id :bam! {} 300)
              new-env     (uism/schedule-timeouts! (app/fulcro-app {}) prepped-env)]
          (assertions
            "Adds a timeout descriptor to active timers"
            (s/valid? ::uism/timeout-descriptor
              (get-in new-env (uism/asm-path new-env [::uism/active-timers :timer/id])))
            => true))))

    (specification "clear-timeout!"
      (provided! "The timer exists"
        (uism/clear-js-timeout! t) => (assertions
                                        "clears the timer"
                                        t => :mock-js-timer)

        (let [new-env (uism/clear-timeout! env-with-active-timer :timer/id)]
          (assertions
            "Removes the timer from the active timers table"
            (get-in new-env (uism/asm-path new-env [::uism/active-timers :timer/id])) => nil))))

    (specification "clear-timeouts-on-event!"
      (provided! "clear-timeout! works correctly"
        (uism/clear-timeout! e t) => (assoc e :cleared? true)

        (let [new-env (uism/clear-timeouts-on-event! env-with-active-timer :bam!)]
          (assertions
            "Clears and removes the timer"
            (:cleared? new-env) => true))))))

;; not usable from clj
(specification "begin!"
  (let [fulcro-state (atom {})]
    (component "(the begin mutation)"
      (let [creation-args {::uism/state-machine-id `test-machine
                           ::uism/asm-id           :fake
                           ::uism/actor->ident     {:dialog [:table 1]}}
            mutation-env  (mutation-env fulcro-state [(uism/begin creation-args)] {:ref [:TABLE 1]})
            {:keys [action]} (m/mutate mutation-env)
            real-new-asm  uism/new-asm]
        (when-mocking!
          (uism/new-asm p) =1x=> (do
                                   (assertions
                                     "creates a new asm with the provided params"
                                     p => creation-args)
                                   (real-new-asm p))
          (uism/trigger-state-machine-event! e p) =1x=> (do
                                                          (assertions
                                                            "triggers the ::started event"
                                                            (::uism/event-id p) => ::uism/started
                                                            (::uism/asm-id p) => :fake)
                                                          [[:A 1]])
          (app/schedule-render! app) => (assertions
                                          "Updates the UI"
                                          (nil? app) => false)

          (action mutation-env)

          (assertions
            "and stores it in fulcro state"
            (contains? (::uism/asm-id @fulcro-state) :fake) => true))))
    #?(:cljs
       (component "(the wrapper function begin!)"
         (when-mocking
           (comp/transact! t tx) => (assertions
                                      "runs fulcro transact on the begin mutation"
                                      (ffirst tx) => `uism/begin)


           (uism/begin! mock-app test-machine :fake {:dialog [:table 1]}))))))

(uism/defstatemachine ctm {::uism/aliases     {:x [:dialog :foo]}
                           ::uism/actor-names #{:dialog}
                           ::uism/states      {:initial {::uism/handler (fn [env] env)}}})
(specification "compute-target"
  (let [asm      (uism/new-asm {::uism/state-machine-id `ctm ::uism/asm-id :fake
                                ::uism/actor->ident     {:dialog [:dialog 1]}})
        test-env (uism/state-machine-env {::uism/asm-id {:fake asm}} nil :fake :do {})]
    (behavior "accepts (and returns) any kind of raw fulcro target"
      (assertions
        "(normal target)"
        (uism/compute-target test-env {::m/target [:a 1]}) => [:a 1]
        "(special target)"
        (uism/compute-target test-env {::m/target (df/append-to [:a 1])}) => [:a 1]
        (dft/special-target? (uism/compute-target test-env {::m/target (df/append-to [:a 1])})) => true))
    (behavior "Resolves actors"
      (assertions
        (uism/compute-target test-env {::uism/target-actor :dialog}) => [:dialog 1]
        "can combine plain targets with actor targets"
        (uism/compute-target test-env {::m/target [:a 1] ::uism/target-actor :dialog}) => [[:a 1] [:dialog 1]]
        (dft/multiple-targets? (uism/compute-target test-env {::m/target          [:a 1]
                                                              ::uism/target-actor :dialog}))
        => true

        "can combine actor targets with a multiple-target"
        (uism/compute-target test-env {::m/target          (df/multiple-targets [:a 1] [:b 2])
                                       ::uism/target-actor :dialog})
        => [[:a 1] [:b 2] [:dialog 1]]))
    (behavior "Resolves aliases"
      (assertions
        (uism/compute-target test-env {::uism/target-alias :x}) => [:dialog 1 :foo]
        "can combine plain targets with alias targets"
        (uism/compute-target test-env {::m/target [:a 1] ::uism/target-alias :x}) => [[:a 1] [:dialog 1 :foo]]
        (dft/multiple-targets? (uism/compute-target test-env {::m/target          [:a 1]
                                                              ::uism/target-alias :x}))
        => true

        "can combine alias targets with a multiple-target"
        (uism/compute-target test-env {::m/target          (df/multiple-targets [:a 1] [:b 2])
                                       ::uism/target-alias :x})
        => [[:a 1] [:b 2] [:dialog 1 :foo]]))))

