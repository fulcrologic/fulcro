(ns untangled.client.impl.application-spec
  (:require
    [untangled.client.core :as uc]
    [om.next :as om :refer [defui]]
    [untangled-spec.core :refer-macros [specification behavior assertions provided component when-mocking]]
    [om.dom :as dom]
    [untangled.i18n :as i18n]
    [untangled.client.impl.application :as app]
    [cljs.core.async :as async]
    [untangled.client.impl.data-fetch :as f]
    [untangled.client.impl.om-plumbing :as plumbing]
    [untangled.client.network :as net]))

(defui ^:once Thing
  static om/Ident
  (ident [this props] [:thing/by-id (:id props)])
  static om/IQuery
  (query [this] [:id :name])
  Object
  (render [this] (dom/div nil "")))

(defui ^:once Root
  static om/IQuery
  (query [this] [:ui/react-key :ui/locale {:things (om/get-query Thing)}])
  Object
  (render [this]
    (dom/div nil "")))

(defn reconciler-with-config [config]
  (-> (app/generate-reconciler {} {} (om/parser {:read identity}) config)
    :config))

(specification "generate-reconciler"
  (behavior "open reconciler options"
    (assertions
      ":shared"
      (-> (reconciler-with-config {:shared {:res :value}}) :shared)
      => {:res :value}

      ":root-unmount"
      (-> (reconciler-with-config {:root-unmount identity}) :root-unmount)
      => identity))

  (behavior "locked reconciler options"
    (assertions
      ":state"
      (-> (reconciler-with-config {:state {}}) :state)
      =fn=> #(not= % {})

      ":send"
      (-> (reconciler-with-config {:send identity}) :send)
      =fn=> #(not= % identity)

      ":normalize"
      (-> (reconciler-with-config {:normalize false}) :normalize)
      => true

      ":remotes"
      (-> (reconciler-with-config {:remotes []}) :remotes)
      =fn=> #(not= % [])

      ":merge-ident"
      (-> (reconciler-with-config {:merge-ident identity}) :merge-ident)
      =fn=> #(not= % identity)

      ":merge-tree"
      (-> (reconciler-with-config {:merge-tree identity}) :merge-tree)
      =fn=> #(not= % identity)

      ":parser"
      (-> (reconciler-with-config {:parser identity}) :parser)
      =fn=> #(not= % identity))))

(specification "Untangled Application (integration tests)"
  (let [startup-called    (atom false)
        thing-1           {:id 1 :name "A"}
        state             {:things [thing-1 {:id 2 :name "B"}]}
        callback          (fn [app] (reset! startup-called (:initial-state app)))
        unmounted-app     (uc/new-untangled-client
                            :initial-state state
                            :started-callback callback
                            :network-error-callback (fn [state _] (get-in @state [:thing/by-id 1])))
        app               (uc/mount unmounted-app Root "application-mount-point")
        mounted-app-state (om/app-state (:reconciler app))
        reconciler        (:reconciler app)
        reconciler-config (:config reconciler)
        migrate           (:migrate reconciler-config)]

    (component "Initialization"
      (behavior "returns untangled client app record with"
        (assertions
          "a request queue"
          (-> app :send-queues :remote type) => cljs.core.async.impl.channels/ManyToManyChannel
          "a response queue"
          (-> app :response-channels :remote type) => cljs.core.async.impl.channels/ManyToManyChannel
          "a reconciler"
          (type reconciler) => om.next/Reconciler
          "a parser"
          (type (:parser app)) => js/Function
          "a marker that the app was initialized"
          (:mounted? app) => true
          "networking support"
          (type (-> app :networking :remote)) => untangled.client.network/Network
          "calls the callback with the initialized app"
          @startup-called => state
          "normalizes and uses the initial state"
          (get-in @mounted-app-state [:thing/by-id 1]) => {:id 1 :name "A"}
          (get-in @mounted-app-state [:things 0]) => [:thing/by-id 1]
          "sets the language to en-US"
          (get @mounted-app-state :ui/locale) => "en-US"
          "gives app-state to global error function"
          (@(get-in app [:networking :remote :global-error-callback])) => thing-1)))

    (component "tempid migration"
      (when-mocking
        (plumbing/rewrite-tempids-in-request-queue queue remaps) =1x=> (assertions
                                                                         "Remaps tempids in the requests queue(s)"
                                                                         remaps => :tempids)
        (plumbing/resolve-tempids state remaps) =1x=> (assertions
                                                        "Remaps tempids in the app state"
                                                        state => :app-state
                                                        remaps => :tempids)

        (migrate :app-state :query :tempids :id-key)))

    (component "Remote transactions"
      (when-mocking
        (app/detect-errant-remotes app) =1x=> (assertions
                                                "Detects invalid remote names"
                                                app => :the-app)
        (app/enqueue-mutations app tx-map cb) =1x=> (assertions
                                                      "Enqueues the mutations first"
                                                      app => :the-app
                                                      tx-map => :transactions
                                                      cb => :merge-callback)
        (app/enqueue-reads app) =1x=> (assertions
                                        "Enqueues the reads"
                                        app => :the-app)

        (app/server-send :the-app :transactions :merge-callback)))

    (component "Changing app :ui/locale"
      (let [react-key (:ui/react-key @mounted-app-state)]
        (reset! i18n/*current-locale* "en")
        (om/transact! reconciler '[(untangled.client.mutations/change-locale {:lang "es-MX"})])
        (assertions
          "Changes the i18n locale for translation lookups"
          (deref i18n/*current-locale*) => "es-MX"
          "Places the new locale in the app state"
          (:ui/locale @mounted-app-state) => "es-MX"
          "Updates the react key to ensure render can redraw everything"
          (not= react-key (:ui/react-key @mounted-app-state)) => true)))))

(specification "Untangled Application (multiple remotes)"
  (let [state             {}
        unmounted-app     (uc/new-untangled-client
                            :initial-state state
                            :networking {:a (net/mock-network)
                                         :b (net/mock-network)})
        app               (uc/mount unmounted-app Root "application-mount-point")
        mounted-app-state (om/app-state (:reconciler app))
        reconciler        (:reconciler app)
        reconciler-config (:config reconciler)
        migrate           (:migrate reconciler-config)
        a-queue           (-> app :send-queues :a)
        b-queue           (-> app :send-queues :b)
        queues-remapped   (atom #{})]

    (component "Initialization"
      (assertions
        "makes a request queue for each remote"
        (-> a-queue type) => cljs.core.async.impl.channels/ManyToManyChannel
        (-> b-queue type) => cljs.core.async.impl.channels/ManyToManyChannel
        "makes a response queue for each remote"
        (-> app :response-channels :a type) => cljs.core.async.impl.channels/ManyToManyChannel
        (-> app :response-channels :b type) => cljs.core.async.impl.channels/ManyToManyChannel
        "Includes each networking implementation"
        (implements? net/UntangledNetwork (-> app :networking :a)) => true
        (implements? net/UntangledNetwork (-> app :networking :b)) => true))

    (component "tempid migration with multiple queues"
      (when-mocking
        (plumbing/rewrite-tempids-in-request-queue queue remaps) => (swap! queues-remapped conj queue)
        (plumbing/resolve-tempids state remaps) =1x=> (assertions
                                                        "remaps tempids in state"
                                                        state => :state
                                                        remaps => :tempids)

        (migrate :state :query :tempids :id-key)

        (assertions
          "Remaps ids in all queues"
          @queues-remapped => #{a-queue b-queue})))))

(specification "Network payload processing (sequential networking)"
  (component "send-payload"
    (let [error          (atom 0)
          update         (atom 0)
          done           (atom 0)
          query          :the-tx
          on-error       (fn [] (swap! error inc))
          send-complete  (fn [] (swap! done inc))
          on-update      (fn [] (swap! update inc))
          reset-test     (fn [] (reset! error 0) (reset! update 0) (reset! done 0))
          load-payload   {:query query :on-load on-update :on-error on-error :load-descriptors []}
          mutate-payload {:query query :on-load on-update :on-error on-error}]
      (behavior "On queries (with load-descriptor payloads)"
        (provided "When real send completes without updates or errors"
          (app/real-send net tx send-done send-error send-update) => (do
                                                                       (assertions
                                                                         "Sends the transaction to the network handler"
                                                                         net => :network
                                                                         tx => :the-tx)
                                                                       (send-done))

          (app/send-payload :network load-payload send-complete)

          (assertions
            "Triggers update and send-complete once"
            @update => 1
            @done => 1
            @error => 0))

        (reset-test)

        (provided "When real send completes with an error"
          (app/real-send net tx send-done send-error send-update) => (do
                                                                       (assertions
                                                                         "Sends the transaction to the network handler"
                                                                         net => :network
                                                                         tx => :the-tx)
                                                                       (send-error))

          (app/send-payload :network load-payload send-complete)

          (assertions
            "Triggers error and send-complete once"
            @update => 0
            @done => 1
            @error => 1))

        (reset-test)

        (provided "When real send triggers multiple updates"
          (app/real-send net tx send-done send-error send-update) => (do
                                                                       (assertions
                                                                         "Sends the transaction to the network handler"
                                                                         net => :network
                                                                         tx => :the-tx)
                                                                       (send-update)
                                                                       (send-update)
                                                                       (send-update)
                                                                       (send-done))

          (app/send-payload :network load-payload send-complete)

          (assertions
            "Only one update is actually done."
            @update => 1
            @done => 1
            @error => 0)))

      (reset-test)
      (behavior "On mutations (no load-descriptor payloads)"
        (provided "When real send completes without updates or errors"
          (app/real-send net tx send-done send-error send-update) => (do
                                                                       (assertions
                                                                         "Sends the transaction to the network handler"
                                                                         net => :network
                                                                         tx => :the-tx)
                                                                       (send-done))

          (app/send-payload :network mutate-payload send-complete)

          (assertions
            "Triggers update and send-complete once"
            @update => 1
            @done => 1
            @error => 0))

        (reset-test)

        (provided "When real send completes with an error"
          (app/real-send net tx send-done send-error send-update) => (do
                                                                       (assertions
                                                                         "Sends the transaction to the network handler"
                                                                         net => :network
                                                                         tx => :the-tx)
                                                                       (send-error))

          (app/send-payload :network mutate-payload send-complete)

          (assertions
            "Triggers error and send-complete once"
            @update => 0
            @done => 1
            @error => 1))

        (reset-test)

        (provided "When real send triggers multiple updates"
          (app/real-send net tx send-done send-error send-update) => (do
                                                                       (assertions
                                                                         "Sends the transaction to the network handler"
                                                                         net => :network
                                                                         tx => :the-tx)
                                                                       (send-update)
                                                                       (send-update)
                                                                       (send-update)
                                                                       (send-done))

          (app/send-payload :network mutate-payload send-complete)

          (assertions
            "Updates are triggered for each update and once at completion"
            @update => 4
            @done => 1
            @error => 0))))))

(defrecord MockNetwork-Legacy []
  net/UntangledNetwork
  (send [this edn done-callback error-callback])
  (start [this complete-app] this))

(defrecord MockNetwork-Parallel []
  net/NetworkBehavior
  (serialize-requests? [this] false)
  net/UntangledNetwork
  (send [this edn done-callback error-callback])
  (start [this complete-app] this))

(defrecord MockNetwork-ExplicitSequential []
  net/NetworkBehavior
  (serialize-requests? [this] true)
  net/UntangledNetwork
  (send [this edn done-callback error-callback])
  (start [this complete-app] this))

(specification "is-sequential? (detection of network queue behavior)"
  (assertions
    "defaults to sequential when not specified"
    (app/is-sequential? (MockNetwork-Legacy.)) => true)
  (assertions "can be overridden by implementing NetworkBehavior"
    (app/is-sequential? (MockNetwork-Parallel.)) => false
    (app/is-sequential? (MockNetwork-ExplicitSequential.)) => true))

(specification "Sweep one"
  (assertions
    "removes not-found values from maps"
    (app/sweep-one {:a 1 :b ::plumbing/not-found}) => {:a 1}
    "is not recursive"
    (app/sweep-one {:a 1 :b {:c ::plumbing/not-found}}) => {:a 1 :b {:c ::plumbing/not-found}}
    "maps over vectors not recursive"
    (app/sweep-one [{:a 1 :b ::plumbing/not-found}]) => [{:a 1}]
    "retains metadata"
    (-> (app/sweep-one (with-meta {:a 1 :b ::plumbing/not-found} {:meta :data}))
      meta) => {:meta :data}
    (-> (app/sweep-one [(with-meta {:a 1 :b ::plumbing/not-found} {:meta :data})])
      first meta) => {:meta :data}
    (-> (app/sweep-one (with-meta [{:a 1 :b ::plumbing/not-found}] {:meta :data}))
      meta) => {:meta :data}))

(specification "Sweep merge"
  (assertions
    "recursively merges maps"
    (app/sweep-merge {:a 1 :c {:b 2}} {:a 2 :c 5}) => {:a 2 :c 5}
    (app/sweep-merge {:a 1 :c {:b 2}} {:a 2 :c {:x 1}}) => {:a 2 :c {:b 2 :x 1}}
    "stops recursive merging if the source element is marked as a leaf"
    (app/sweep-merge {:a 1 :c {:d {:x 2} :e 4}} {:a 2 :c (plumbing/as-leaf {:d {:x 1}})}) => {:a 2 :c {:d {:x 1}}}
    "sweeps values that are marked as not found"
    (app/sweep-merge {:a 1 :c {:b 2}} {:a 2 :c {:b ::plumbing/not-found}}) => {:a 2 :c {}}
    (app/sweep-merge {:a 1 :c 2} {:a 2 :c {:b ::plumbing/not-found}}) => {:a 2 :c {}}
    (app/sweep-merge {:a 1 :c 2} {:a 2 :c [{:x 1 :b ::plumbing/not-found}]}) => {:a 2 :c [{:x 1}]}
    (app/sweep-merge {:a 1 :c {:data-fetch :loading}} {:a 2 :c [{:x 1 :b ::plumbing/not-found}]}) => {:a 2 :c [{:x 1}]}
    (app/sweep-merge {:a 1 :c nil} {:a 2 :c [{:x 1 :b ::plumbing/not-found}]}) => {:a 2 :c [{:x 1}]}
    (app/sweep-merge {:a 1 :b {:c {:ui/fetch-state {:post-mutation 's}}}} {:a 2 :b {:c [{:x 1 :b ::plumbing/not-found}]}}) => {:a 2 :b {:c [{:x 1}]}}
    "sweeps not-found values from normalized table merges"
    (app/sweep-merge {:subpanel  [:dashboard :panel]
                      :dashboard {:panel {:view-mode :detail :surveys {:ui/fetch-state {:post-mutation 's}}}}
                      }
      {:subpanel  [:dashboard :panel]
       :dashboard {:panel {:view-mode :detail :surveys [[:s 1] [:s 2]]}}
       :s         {
                   1 {:db/id 1, :survey/launch-date :untangled.client.impl.om-plumbing/not-found}
                   2 {:db/id 2, :survey/launch-date "2012-12-22"}
                   }}) => {:subpanel  [:dashboard :panel]
                           :dashboard {:panel {:view-mode :detail :surveys [[:s 1] [:s 2]]}}
                           :s         {
                                       1 {:db/id 1}
                                       2 {:db/id 2 :survey/launch-date "2012-12-22"}
                                       }}
    "overwrites target (non-map) value if incoming value is a map"
    (app/sweep-merge {:a 1 :c 2} {:a 2 :c {:b 1}}) => {:a 2 :c {:b 1}}))

(specification "Merge handler"
  (let [swept-state                       {:state 1}
        data-response                     {:v 1}
        mutation-response                 {'f {:x 1 :tempids {1 2}} 'g {:y 2}}
        mutation-response-without-tempids (update mutation-response 'f dissoc :tempids)
        response                          (merge data-response mutation-response)
        rh                                (fn [state k v]
                                            (assertions
                                              "return handler is passed the swept state as a map"
                                              state => swept-state
                                              "tempids are stripped from return value before calling handler"
                                              (:tempids v) => nil)
                                            (vary-meta state assoc k v))]
    (when-mocking
      (app/sweep-merge t s) => (do
                                 (assertions
                                   "Passes source, cleaned of symbols, to sweep-merge"
                                   s => {:v 1})
                                 swept-state)

      (let [actual (app/merge-handler rh {} response)]
        (assertions
          "Returns the swept state reduced over the return handlers"
          ;; Function under test:
          actual => swept-state
          (meta actual) => mutation-response-without-tempids)))))

(specification "split-mutations"
  (behavior "Takes an Om tx and splits it into a vector of one or more txes that have no duplicate mutation names"
    (assertions
      "Refuses to split transactions that contain non-mutation entries (with console error)."
      (app/split-mutations '[:a (f) :b (f)]) => ['[:a (f) :b (f)]]
      "Give back an empty vector if there are no mutations"
      (app/split-mutations '[]) => '[]
      "Leaves non-duplicate txes alone"
      (app/split-mutations '[(f) (g) (h)]) => '[[(f) (g) (h)]]
      "Splits at duplicate mutation"
      (app/split-mutations '[(f) (g) (f) (k)]) => '[[(f) (g)] [(f) (k)]]
      "Resets 'seen mutations' at each split, so prior mutations do not cause extra splitting"
      (app/split-mutations '[(f) (g) (f) (k) (g)]) => '[[(f) (g)] [(f) (k) (g)]])))

(specification "enqueue-mutations"
  (behavior "enqueues a payload with query, load, and error callbacks"
    (let [send-queues {:remote :mock-queue}
          remote-txs  {:remote '[(f)]}]
      (when-mocking
        (app/fallback-handler app tx) => identity
        (plumbing/remove-loads-and-fallbacks tx) => tx
        (app/enqueue q p) => (let [{:keys [query]} p]
                               (assertions
                                 ""
                                 query => '[(f)]))

        (app/enqueue-mutations {:send-queues send-queues} remote-txs identity))))
  (behavior "splits mutation lists to prevent duplication mutations on a single network request"
    (let [send-queues {:remote :mock-queue}
          remote-txs  {:remote '[(f) (g) (f)]}]
      (when-mocking
        (app/fallback-handler app tx) => identity
        (plumbing/remove-loads-and-fallbacks tx) => tx
        (app/enqueue q p) =1x=> (let [{:keys [query]} p]
                                  (assertions
                                    ""
                                    query => '[(f) (g)]))
        (app/enqueue q p) =1x=> (let [{:keys [query]} p]
                                  (assertions
                                    ""
                                    query => '[(f)]))

        (app/enqueue-mutations {:send-queues send-queues} remote-txs identity)))))
