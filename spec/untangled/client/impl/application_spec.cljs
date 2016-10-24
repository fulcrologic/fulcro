(ns untangled.client.impl.application-spec
  (:require
    [untangled.client.core :as uc]
    [om.next :as om :refer-macros [defui]]
    [untangled-spec.core :refer-macros [specification behavior assertions provided component when-mocking]]
    untangled.client.impl.built-in-mutations
    [om.dom :as dom]
    [untangled.i18n.core :as i18n]
    [untangled.client.impl.application :as app]
    [cljs.core.async :as async]
    [untangled.client.impl.data-fetch :as f]
    [untangled.client.impl.om-plumbing :as plumbing]))

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

(specification "Untangled Application (integration tests)"
  (let [startup-called (atom false)
        thing-1 {:id 1 :name "A"}
        state {:things [thing-1 {:id 2 :name "B"}]}
        callback (fn [app] (reset! startup-called (:initial-state app)))
        unmounted-app (uc/new-untangled-client
                        :initial-state state
                        :started-callback callback
                        :network-error-callback (fn [state _] (get-in @state [:thing/by-id 1])))
        app (uc/mount unmounted-app Root "application-mount-point")
        mounted-app-state (om/app-state (:reconciler app))
        reconciler (:reconciler app)]

    (component "Initialization"
      (behavior "returns untangled client app record with"
        (assertions
          "a request queue"
          (type (:queue app)) => cljs.core.async.impl.channels/ManyToManyChannel
          "a response queue"
          (type (:response-channel app)) => cljs.core.async.impl.channels/ManyToManyChannel
          "a reconciler"
          (type reconciler) => om.next/Reconciler
          "a parser"
          (type (:parser app)) => js/Function
          "a marker that the app was initialized"
          (:mounted? app) => true
          "networking support"
          (type (:networking app)) => untangled.client.impl.network/Network
          "calls the callback with the initialized app"
          @startup-called => state
          "normalizes and uses the initial state"
          (get-in @mounted-app-state [:thing/by-id 1]) => {:id 1 :name "A"}
          (get-in @mounted-app-state [:things 0]) => [:thing/by-id 1]
          "sets the language to en-US"
          (get @mounted-app-state :ui/locale) => "en-US"
          "gives app-state to global error function"
          (@(get-in app [:networking :global-error-callback])) => thing-1)))

    (component "Remote transaction"
      (behavior "are split into reads, mutations, and tx fallbacks"
        (let [fallback-handler app/fallback-handler
              full-tx '[(a/f) (untangled/load {}) (tx/fallback {:action app/fix-error})]
              mark-loading! (let [to-be-marked (atom [{:query '[:some-real-query]}])]
                              (fn []
                                (when-let [marked (first @to-be-marked)]
                                  (swap! to-be-marked pop)
                                  marked)))]
          (when-mocking
            (om/app-state _) => (atom nil)
            (f/mark-loading r) => (mark-loading!)
            (app/fallback-handler app tx) => (let [rv (fallback-handler app tx)
                                                   app-state (atom {})]
                                               (when-mocking
                                                 (om/app-state _) => app-state
                                                 (om/transact! _ tx) =1x=> (assertions
                                                                             "calls passed-in fallback mutation"
                                                                             tx => '[(tx/fallback {:action  app/fix-error
                                                                                                   :execute true
                                                                                                   :error   {:some :error}})])
                                                 (behavior "fallback handler"
                                                   (rv {:some :error})
                                                   (assertions
                                                     "sees the tx that includes the fallback"
                                                     tx => full-tx
                                                     "sets the global error marker"
                                                     (:untangled/server-error @app-state) => {:some :error}))))

            (app/enqueue q p) =1x=> (do
                                      (assertions
                                        "mutation is sent, is first, and does not include load/fallbacks"
                                        (:query p) => '[(a/f)])
                                      true)
            (app/enqueue q p) =1x=> (do
                                      (assertions
                                        "reads are sent, and are last"
                                        (:query p) => '[:some-real-query])
                                      true)

            (app/server-send {} {:remote full-tx} (fn []))))))

    (component "Changing app :ui/locale"
      (let [react-key (:ui/react-key @mounted-app-state)]
        (reset! i18n/*current-locale* "en")
        (om/transact! reconciler '[(ui/change-locale {:lang "es-MX"})])
        (assertions
          "Changes the i18n locale for translation lookups"
          (deref i18n/*current-locale*) => "es-MX"
          "Places the new locale in the app state"
          (:ui/locale @mounted-app-state) => "es-MX"
          "Updates the react key to ensure render can redraw everything"
          (not= react-key (:ui/react-key @mounted-app-state)) => true)))))

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
  (let [triggers (atom {})
        state (atom {:sa true})
        rh (fn [env k v]
             (assertions "return handler is passed state atom"
               (-> env :state deref :sa) => true)
             (swap! triggers assoc k v))]
    (when-mocking
      (app/sweep-merge t s) => (do
                                 (assertions
                                   "Passes source, cleaned of symbols, to sweep-merge"
                                   s => {})
                                 :return-of-sweep)


      (assertions
        "Returns the result of an actual sweep-merge"
        ;; Function under test:
        (app/merge-handler state rh {} {'f :value 'g :other}) => :return-of-sweep

        "triggers return-handler on symbols"
        (get @triggers 'f) => :value
        (get @triggers 'g) => :other))))

