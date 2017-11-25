(ns fulcro.client.impl.application-spec
  (:require
    [fulcro.client.core :as fc]
    [fulcro.client.primitives :as prim :refer [defui]]
    [fulcro-spec.core :refer-macros [specification behavior assertions provided component when-mocking]]
    [fulcro.client.dom :as dom]
    [fulcro.i18n :as i18n]
    [fulcro.client.impl.application :as app]
    [cljs.core.async :as async]
    [fulcro.client.impl.data-fetch :as f]
    [fulcro.client.network :as net]
    [fulcro.client.mutations :as m]
    [fulcro.history :as hist]))

(defui ^:once Thing
  static prim/Ident
  (ident [this props] [:thing/by-id (:id props)])
  static prim/IQuery
  (query [this] [:id :name])
  Object
  (render [this] (dom/div nil "")))

(defui ^:once Root
  static prim/IQuery
  (query [this] [:ui/react-key :ui/locale {:things (prim/get-query Thing)}])
  Object
  (render [this]
    (dom/div nil "")))

(defn reconciler-with-config [config]
  (-> (app/generate-reconciler {} {} (prim/parser {:read identity}) config)
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

(specification "Server-side rendering of locale"
  (let [state             {:ui/locale :es}
        unmounted-app     (fc/new-fulcro-client
                            :initial-state state
                            :network-error-callback (fn [state _] (get-in @state [:thing/by-id 1])))
        app               (fc/mount unmounted-app Root "application-mount-point")
        mounted-app-state (prim/app-state (:reconciler app))]

    (assertions
      "is honored by the client"
      @i18n/*current-locale* => :es
      (get @mounted-app-state :ui/locale) => :es)))

(specification "Fulcro Application (integration tests)"
  (let [startup-called    (atom false)
        thing-1           {:id 1 :name "A"}
        state             {:things [thing-1 {:id 2 :name "B"}]}
        callback          (fn [app] (reset! startup-called (:initial-state app)))
        unmounted-app     (fc/new-fulcro-client
                            :initial-state state
                            :started-callback callback
                            :network-error-callback (fn [state _] (get-in @state [:thing/by-id 1])))
        app               (fc/mount unmounted-app Root "application-mount-point")
        mounted-app-state (prim/app-state (:reconciler app))
        reconciler        (:reconciler app)
        reconciler-config (:config reconciler)
        migrate           (:migrate reconciler-config)]

    (component "Initialization"
      (behavior "returns fulcro client app record with"
        (assertions
          "a request queue"
          (-> app :send-queues :remote type) => cljs.core.async.impl.channels/ManyToManyChannel
          "a response queue"
          (-> app :response-channels :remote type) => cljs.core.async.impl.channels/ManyToManyChannel
          ;"a reconciler"
          ;(type reconciler) => fulcro.client.primitives/Reconciler
          "a parser"
          (type (:parser app)) => js/Function
          "a marker that the app was initialized"
          (:mounted? app) => true
          "networking support"
          (type (-> app :networking :remote)) => fulcro.client.network/Network
          "calls the callback with the initialized app"
          @startup-called => state
          "normalizes and uses the initial state"
          (get-in @mounted-app-state [:thing/by-id 1]) => {:id 1 :name "A"}
          (get-in @mounted-app-state [:things 0]) => [:thing/by-id 1]
          "sets the default language to :en"
          (get @mounted-app-state :ui/locale) => :en
          "gives app-state to global error function"
          (@(get-in app [:networking :remote :global-error-callback])) => thing-1)))

    (component "tempid migration"
      (when-mocking
        (prim/rewrite-tempids-in-request-queue queue remaps) =1x=> (assertions
                                                                         "Remaps tempids in the requests queue(s)"
                                                                         remaps => :mock-tempids)
        (prim/resolve-tempids state remaps) =1x=> (assertions
                                                        "Remaps tempids in the app state"
                                                        state => :app-state
                                                        remaps => :mock-tempids)

        (migrate :app-state :query :mock-tempids :id-key)))

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
      (when-mocking
        (m/locale-present? l) => true

        (let [react-key (:ui/react-key @mounted-app-state)]
          (reset! i18n/*current-locale* "en")
          (prim/transact! reconciler '[(fulcro.client.mutations/change-locale {:lang "es-MX"})])
          (assertions
            "Changes the i18n locale for translation lookups"
            (deref i18n/*current-locale*) => "es-MX"
            "Places the new locale in the app state"
            (:ui/locale @mounted-app-state) => "es-MX"
            "Updates the react key to ensure render can redraw everything"
            (not= react-key (:ui/react-key @mounted-app-state)) => true))))))

(specification "Fulcro Application (multiple remotes)"
  (let [state             {}
        unmounted-app     (fc/new-fulcro-client
                            :initial-state state
                            :networking {:a (net/mock-network)
                                         :b (net/mock-network)})
        app               (fc/mount unmounted-app Root "application-mount-point")
        mounted-app-state (prim/app-state (:reconciler app))
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
        (implements? net/FulcroNetwork (-> app :networking :a)) => true
        (implements? net/FulcroNetwork (-> app :networking :b)) => true))

    (component "tempid migration with multiple queues"
      (when-mocking
        (prim/rewrite-tempids-in-request-queue queue remaps) => (swap! queues-remapped conj queue)
        (prim/resolve-tempids state remaps) =1x=> (assertions
                                                        "remaps tempids in state"
                                                        state => :state
                                                        remaps => :mock-tempids)

        (migrate :state :query :mock-tempids :id-key)

        (assertions
          "Remaps ids in all queues"
          @queues-remapped => #{a-queue b-queue})))))

(def empty-history (hist/new-history 100))

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
          load-payload   {::prim/query query ::f/on-load on-update ::f/on-error on-error ::f/load-descriptors []}
          mutate-payload {::prim/query query ::f/on-load on-update ::f/on-error on-error}]
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
  net/FulcroNetwork
  (send [this edn done-callback error-callback])
  (start [this] this))

(defrecord MockNetwork-Parallel []
  net/NetworkBehavior
  (serialize-requests? [this] false)
  net/FulcroNetwork
  (send [this edn done-callback error-callback])
  (start [this] this))

(defrecord MockNetwork-ExplicitSequential []
  net/NetworkBehavior
  (serialize-requests? [this] true)
  net/FulcroNetwork
  (send [this edn done-callback error-callback])
  (start [this] this))

(specification "is-sequential? (detection of network queue behavior)"
  (assertions
    "defaults to sequential when not specified"
    (app/is-sequential? (MockNetwork-Legacy.)) => true)
  (assertions "can be overridden by implementing NetworkBehavior"
    (app/is-sequential? (MockNetwork-Parallel.)) => false
    (app/is-sequential? (MockNetwork-ExplicitSequential.)) => true))

(specification "split-mutations"
  (behavior "Takes a tx and splits it into a vector of one or more txes that have no duplicate mutation names"
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
      (app/split-mutations '[(f) (g) (f) (k) (g)]) => '[[(f) (g)] [(f) (k) (g)]]
      "Can split mutation joins"
      (app/split-mutations '[{(f) [:x]} (g) (f) (k) (g)]) => '[[{(f) [:x]} (g)] [(f) (k) (g)]]
      )))

(specification "enqueue-mutations"
  (behavior "enqueues a payload with query, load, and error callbacks"
    (let [send-queues {:remote :mock-queue}
          remote-txs  {:remote '[(f)]}]
      (when-mocking
        (app/fallback-handler app tx) => identity
        (prim/remove-loads-and-fallbacks tx) => tx
        (app/enqueue q p) => (let [{:keys [::prim/query]} p]
                               (assertions
                                 query => '[(f)]))

        (app/enqueue-mutations {:send-queues send-queues} remote-txs identity))))
  (behavior "splits mutation lists to prevent duplication mutations on a single network request"
    (let [send-queues {:remote :mock-queue}
          remote-txs  {:remote '[(f) (g) (f)]}]
      (when-mocking
        (app/fallback-handler app tx) => identity
        (prim/remove-loads-and-fallbacks tx) => tx
        (app/enqueue q p) =1x=> (let [{:keys [::prim/query]} p]
                                  (assertions
                                    query => '[(f) (g)]))
        (app/enqueue q p) =1x=> (let [{:keys [::prim/query]} p]
                                  (assertions
                                    query => '[(f)]))

        (app/enqueue-mutations {:send-queues send-queues} remote-txs identity)))))

(specification "Local read can"
  (let [state            (atom {:top-level    :top-level-value
                                :union-join   [:panel :a]
                                :union-join-2 [:dashboard :b]
                                :join         {:sub-key-1 [:item/by-id 1]
                                               :sub-key-2 :sub-value-2}
                                :item/by-id   {1 {:survey/title "Howdy!" :survey/description "More stuff"}}
                                :settings     {:tags nil}
                                :dashboard    {:b {:x 2 :y 1 :z [:dashboard :c]}
                                               :c {:x 3 :y 7 :z [[:dashboard :d]]}
                                               :d {:x 5 :y 10}}
                                :panel        {:a {:x 1 :n 4}}})
        custom-read      (fn [env k params] (when (= k :custom) {:value 42}))
        parser           (partial (prim/parser {:read (partial app/read-local (constantly false))}) {:state state})
        augmented-parser (partial (prim/parser {:read (partial app/read-local custom-read)}) {:state state})]

    (reset! i18n/*current-locale* "en-US")

    (assertions
      "read top-level properties"
      (parser [:top-level]) => {:top-level :top-level-value}

      "read nested queries"
      (parser [{:join [:sub-key-2]}]) => {:join {:sub-key-2 :sub-value-2}}

      "read union queries"
      (parser [{:union-join {:panel [:x :n] :dashboard [:x :y]}}]) => {:union-join {:x 1 :n 4}}
      (parser [{:union-join-2 {:panel [:x :n] :dashboard [:x :y]}}]) => {:union-join-2 {:x 2 :y 1}}
      (parser [{[:panel :a] {:panel [:x :n] :dashboard [:x :y]}}]) => {[:panel :a] {:x 1 :n 4}}

      "read queries with references"
      (parser [{:join [{:sub-key-1 [:survey/title :survey/description]}]}]) =>
      {:join {:sub-key-1 {:survey/title "Howdy!" :survey/description "More stuff"}}}

      "read with recursion"
      (parser [{:dashboard [{:b [:x :y {:z '...}]}]}]) => {:dashboard {:b {:x 2 :y 1 :z {:x 3 :y 7 :z [{:x 5 :y 10}]}}}}

      "read recursion nested in a union query"
      (parser [{:union-join-2 {:panel [:x :n] :dashboard [:x :y {:z '...}]}}]) => {:union-join-2 {:x 2 :y 1 :z {:x 3 :y 7 :z [{:x 5 :y 10}]}}}

      "still exhibits normal behavior when augmenting with a custom root-level reader function"
      (augmented-parser [:top-level]) => {:top-level :top-level-value}
      (augmented-parser [{:join [:sub-key-2]}]) => {:join {:sub-key-2 :sub-value-2}}
      (augmented-parser [{:union-join {:panel [:x :n] :dashboard [:x :y]}}]) => {:union-join {:x 1 :n 4}}
      (augmented-parser [{:union-join-2 {:panel [:x :n] :dashboard [:x :y]}}]) => {:union-join-2 {:x 2 :y 1}}
      (augmented-parser [{[:panel :a] {:panel [:x :n] :dashboard [:x :y]}}]) => {[:panel :a] {:x 1 :n 4}}
      (augmented-parser [{:join [{:sub-key-1 [:survey/title :survey/description]}]}]) => {:join {:sub-key-1 {:survey/title "Howdy!" :survey/description "More stuff"}}}
      (augmented-parser [{:dashboard [{:b [:x :y {:z '...}]}]}]) => {:dashboard {:b {:x 2 :y 1 :z {:x 3 :y 7 :z [{:x 5 :y 10}]}}}}
      (augmented-parser [{:union-join-2 {:panel [:x :n] :dashboard [:x :y {:z '...}]}}]) => {:union-join-2 {:x 2 :y 1 :z {:x 3 :y 7 :z [{:x 5 :y 10}]}}}

      "supports augmentation from a user-supplied read function"
      (augmented-parser [:top-level :custom]) => {:top-level :top-level-value :custom 42})

    (let [state  {:curr-view      [:main :view]
                  :main           {:view {:curr-item [[:sub-item/by-id 2]]}}
                  :sub-item/by-id {2 {:foo :baz :sub-items [[:sub-item/by-id 4]]}
                                   4 {:foo :bar}}}
          parser (partial (prim/parser {:read (partial app/read-local (constantly nil))}) {:state (atom state)})]

      (assertions
        "read recursion nested in a join underneath a union"
        (parser '[{:curr-view {:settings [*] :main [{:curr-item [:foo {:sub-items ...}]}]}}]) =>
        {:curr-view {:curr-item [{:foo :baz :sub-items [{:foo :bar}]}]}}))))
