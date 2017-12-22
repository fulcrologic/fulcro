(ns fulcro.client.data-fetch-spec
  (:require
    [fulcro.client.data-fetch :as df]
    [fulcro.client.impl.data-fetch :as dfi]
    [fulcro.client.util :as util]
    #?@(:cljs
        [[goog.log :as glog]])
    [fulcro.client.primitives :as prim :refer [defui defsc]]
    [clojure.test :refer [is are]]
    [fulcro-spec.core :refer
     [specification behavior assertions provided component when-mocking]]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.logging :as log]
    [fulcro.client.impl.protocols :as omp]
    [fulcro.client :as fc]
    [fulcro.client.impl.data-fetch :as f]
    [fulcro.history :as hist]
    [fulcro.client.impl.protocols :as p]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; SETUP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defui ^:once Person
  static prim/IQuery (query [_] [:db/id :username :name])
  static prim/Ident (ident [_ props] [:person/id (:db/id props)]))

(defui ^:once Comment
  static prim/IQuery (query [this] [:db/id :title {:author (prim/get-query Person)}])
  static prim/Ident (ident [this props] [:comments/id (:db/id props)]))

(defui ^:once Item
  static prim/IQuery (query [this] [:db/id :name {:comments (prim/get-query Comment)}])
  static prim/Ident (ident [this props] [:items/id (:db/id props)]))


(defui ^:once Panel
  static prim/IQuery (query [this] [:db/id {:items (prim/get-query Item)}])
  static prim/Ident (ident [this props] [:panel/id (:db/id props)]))

(defui ^:once PanelRoot
  static prim/IQuery (query [this] [{:panel (prim/get-query Panel)}]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;  TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(specification "Data states"
  (behavior "are properly initialized."
    (is (df/data-state? (dfi/make-data-state :ready)))
    (try (dfi/make-data-state :invalid-key)
         (catch #?(:cljs :default :clj Throwable) e
           (is (= #?(:cljs (.-message e) :clj (.getMessage e)) "INVALID DATA STATE TYPE: :invalid-key")))))

  (behavior "can by identified by type."
    (is (df/ready? (dfi/make-data-state :ready)))
    (is (df/failed? (dfi/make-data-state :failed)))
    (is (df/loading? (dfi/make-data-state :loading)))

    (is (not (df/ready? (dfi/make-data-state :failed))))
    (is (not (df/failed? "foo")))))

(specification "Processed ready states"
  (let [make-ready-marker  (fn [without-set]
                             (dfi/ready-state
                               {:ident   [:item/by-id 1]
                                :field   :comments
                                :without without-set
                                :query   (prim/focus-query (prim/get-query Item) [:comments])}))

        without-join       (make-ready-marker #{:author})
        without-prop       (make-ready-marker #{:username})
        without-multi-prop (make-ready-marker #{:db/id})]


    (assertions
      "remove the :without portion of the query on joins"
      (dfi/data-query without-join) => [{[:item/by-id 1] [{:comments [:db/id :title]}]}]

      "remove the :without portion of the query on props"
      (dfi/data-query without-prop) => [{[:item/by-id 1] [{:comments [:db/id :title {:author [:db/id :name]}]}]}]

      "remove the :without portion when keyword appears in multiple places in the query"
      (dfi/data-query without-multi-prop) => [{[:item/by-id 1] [{:comments [:title {:author [:username :name]}]}]}]))

  (behavior "can elide top-level keys from the query"
    (let [ready-state (dfi/ready-state {:query (prim/get-query Item) :without #{:name}})]
      (is (= [:db/id {:comments [:db/id :title {:author [:db/id :username]}]}] (::prim/query ready-state)))))

  (behavior "can include parameters when eliding top-level keys from the query"
    (let [ready-state (dfi/ready-state {:query (prim/get-query Item) :without #{:name} :params {:db/id {:x 1}}})]
      (is (= '[(:db/id {:x 1}) {:comments [:db/id :title {:author [:db/id :username]}]}] (::prim/query ready-state)))))

  (behavior "can elide keywords from a union query"
    (assertions
      (prim/ast->query
        (dfi/elide-ast-nodes
          (prim/query->ast [{:current-tab {:panel [:data] :console [:data] :dashboard [:data]}}])
          #{:console}))
      => [{:current-tab {:panel [:data] :dashboard [:data]}}])))

(defsc InitTestChild [this props]
  {:query         [:y]
   :ident         [:child/by-id :y]
   :initial-state {:y 2}})

(defsc InitTestComponent [this props]
  {:initial-state {:x 1 :z :param/z :child {}}
   :ident         [:parent/by-id :x]
   :query         [:x :z {:child (prim/get-query InitTestChild)}]})

#?(:cljs
   (specification "Load parameters" :focused
     (let [query-with-params       (:query (df/load-params* :prop Person {:params {:n 1}}))
           ident-query-with-params (:query (df/load-params* [:person/by-id 1] Person {:params {:n 1}}))]
       (assertions
         "Always include a vector for refresh"
         (df/load-params* :prop Person {}) =fn=> #(vector? (:refresh %))
         (df/load-params* [:person/by-id 1] Person {}) =fn=> #(vector? (:refresh %))
         "Accepts nil for subquery and params"
         (:query (df/load-params* [:person/by-id 1] nil {})) => [[:person/by-id 1]]
         "Constructs query with parameters when subquery is nil"
         (:query (df/load-params* [:person/by-id 1] nil {:params {:x 1}})) => '[([:person/by-id 1] {:x 1})]
         "Constructs a JOIN query (without params)"
         (:query (df/load-params* :prop Person {})) => [{:prop (prim/get-query Person)}]
         (:query (df/load-params* [:person/by-id 1] Person {})) => [{[:person/by-id 1] (prim/get-query Person)}]
         "Honors target for property-based join"
         (:target (df/load-params* :prop Person {:target [:a :b]})) => [:a :b]
         "Constructs a JOIN query (with params)"
         query-with-params =fn=> (fn [q] (= q `[({:prop ~(prim/get-query Person)} {:n 1})]))
         ident-query-with-params =fn=> (fn [q] (= q `[({[:person/by-id 1] ~(prim/get-query Person)} {:n 1})])))
       (provided "uses computed-refresh to augment the refresh list"
         (df/computed-refresh explicit k t) =1x=> :computed-refresh

         (let [params (df/load-params* :k Person {})]
           (assertions
             "includes the computed refresh list as refresh"
             (:refresh params) => :computed-refresh))))
     (let [state-marker-legacy (dfi/ready-state {:query [{:x [:a]}] :field :x :ident [:thing/by-id 1] :params {:x {:p 2}}})
           state-marker-new    (dfi/ready-state {:query [{:x [:a]}] :field :x :ident [:thing/by-id 1] :params {:p 2}})]
       (assertions
         "Honors legacy way of specifying parameters"
         (-> state-marker-legacy ::prim/query) => '[({:x [:a]} {:p 2})]
         "Honors simpler way of specifying parameters"
         (-> state-marker-new ::prim/query) => '[({:x [:a]} {:p 2})]))
     (behavior "When initialize is:"
       (let [params                  (df/load-params* :root/comp InitTestComponent {:initialize true})
             params-with-init-params (df/load-params* :root/comp InitTestComponent {:initialize {:z 42}})]
         (assertions
           "true: load params get initial state for component"
           (:initialize params) => {:root/comp {:x 1 :child {:y 2}}}
           "a map: load params get that map as the initial state of the component"
           (:initialize params-with-init-params) => {:root/comp {:z 42}})))))

(specification "Load auto-refresh"
  (component "computed-refresh"
    (assertions
      "Adds the load-key ident to refresh (as a non-duplicate)"
      (set (#'df/computed-refresh [:a] [:table 1] nil)) => #{:a [:table 1]}
      (set (#'df/computed-refresh [:a [:table 1]] [:table 1] nil)) => #{:a [:table 1]}
      "Adds the load-key keyword to refresh (non-duplicate, when there is no target)"
      (set (#'df/computed-refresh [:a] :b nil)) => #{:a :b}
      (set (#'df/computed-refresh [:a :b] :b nil)) => #{:a :b}
      "Allows a multi-target target"
      (set (#'df/computed-refresh [:x] [:c 3]
             (df/multiple-targets
               (df/append-to [:a 1 :f])
               (df/prepend-to [:b 2 :g])))) => #{[:a 1] [:b 2] [:c 3] :x}
      "Adds the target's first two elements as an ident to refresh (when target has 2+ elements)"
      (set (#'df/computed-refresh [:a] :b [:x 3 :boo])) => #{:a [:x 3]}
      (set (#'df/computed-refresh [:a] :b [:x 3])) => #{:a [:x 3]}
      "Adds the target's first element as a kw to refresh (when target has 1 element)"
      (set (#'df/computed-refresh [:a] :b [:x])) => #{:a :x})))

(specification "Load mutation expressions"
  (let [mutation-expr (df/load-mutation {:refresh [:a :b] :query [:my-query]})
        mutation      (first mutation-expr)]
    (assertions
      "are vectors"
      mutation-expr =fn=> vector?
      "include a Fulcro/load with mutation arguments"
      mutation =fn=> list?
      (first mutation) => 'fulcro/load
      "always do a follow-on read for :ui/loading-data"
      mutation-expr =fn=> #(some (fn [k] (= k :ui/loading-data)) %)
      "include user-driven follow-on-reads"
      mutation-expr =fn=> #(some (fn [k] (= k :a)) %)
      mutation-expr =fn=> #(some (fn [k] (= k :b)) %))))

(specification "The load function"
  (when-mocking
    (df/load-params* key query config) => :mutation-args
    (df/load-mutation args) => (do
                                 (assertions
                                   "creates mutation arguments"
                                   args => :mutation-args)
                                 :mutation)
    (prim/transact! c tx) => (do (assertions
                                   "uses the passed component/app in transact!"
                                   c => :component
                                   "uses the mutation created by load-mutation"
                                   tx => :mutation))
    (prim/component? c) => true

    (df/load :component :x Person {})))

#?(:cljs
   (specification "The load-action function"
     (let [state-atom (atom {})]
       (when-mocking
         (df/load-params* key query config) => {:refresh [] :query [:x]}

         (df/load-action {:state state-atom} :x Person {})

         (let [query (-> @state-atom :fulcro/ready-to-load first ::prim/query)]
           (assertions
             "adds a proper load marker to the state"
             query => [:x]))))))

(specification "Lazy loading"
  (component "load-field"
    (behavior "Accepts named parameters"
      (let [query (prim/get-query Item)]
        (provided "properly calls transact"
          (prim/get-ident c) =2x=> [:item/by-id 10]
          (prim/get-query c) =1x=> query
          (prim/transact! c tx) =1x=> (let [params          (-> tx first second)
                                            follow-on-reads (set (-> tx rest))]
                                        (assertions
                                          "includes :ui/loading-data in the follow-on reads"
                                          (contains? follow-on-reads :ui/loading-data) => true
                                          "includes ident of component in the follow-on reads"
                                          (contains? follow-on-reads [:item/by-id 10]) => true
                                          "does the transact on the component"
                                          c => 'component
                                          "includes the component's ident in the marker."
                                          (:ident params) => [:item/by-id 10]
                                          "focuses the query to the specified field."
                                          (:query params) => [{:comments [:db/id :title {:author [:db/id :username :name]}]}]
                                          "includes the parameters."
                                          (:params params) => {:sort :by-name}
                                          "includes the subquery exclusions."
                                          (:without params) => #{:excluded-attr}
                                          "includes the post-processing callback."
                                          (:post-mutation params) => 'foo
                                          "includes the error fallback"
                                          (:fallback params) => 'bar))

          (df/load-field 'component :comments
            :without #{:excluded-attr}
            :params {:sort :by-name}
            :post-mutation 'foo
            :fallback 'bar))))

    (behavior "Accepts parameters as a map"
      (let [query (prim/get-query Item)]
        (provided "properly calls transact"
          (prim/get-ident c) =2x=> [:item/by-id 10]
          (prim/get-query c) =1x=> query
          (prim/transact! c tx) =1x=> (let [params          (-> tx first second)
                                            follow-on-reads (set (-> tx rest))]
                                        (assertions
                                          "includes :ui/loading-data in the follow-on reads"
                                          (contains? follow-on-reads :ui/loading-data) => true
                                          "includes ident of component in the follow-on reads"
                                          (contains? follow-on-reads [:item/by-id 10]) => true
                                          "does the transact on the component"
                                          c => 'component
                                          "includes the component's ident in the marker."
                                          (:ident params) => [:item/by-id 10]
                                          "focuses the query to the specified field."
                                          (:query params) => [{:comments [:db/id :title {:author [:db/id :username :name]}]}]
                                          "includes the parameters."
                                          (:params params) => {:sort :by-name}
                                          "includes the subquery exclusions."
                                          (:without params) => #{:excluded-attr}
                                          "includes custom marker"
                                          (:marker params) => :my-marker
                                          "includes the post-processing callback."
                                          (:post-mutation params) => 'foo
                                          "includes the error fallback"
                                          (:fallback params) => 'bar))

          (df/load-field 'component :comments
            {:without       #{:excluded-attr}
             :params        {:sort :by-name}
             :marker        :my-marker
             :post-mutation 'foo
             :fallback      'bar})))))

  (component "load-field-action"
    (behavior "accepts named parameters"
      (let [app-state (atom {})]
        (df/load-field-action app-state Item [:item/by-id 3] :comments :without #{:author} :marker :x)

        (let [marker (first (get-in @app-state [:fulcro/ready-to-load]))]
          (assertions
            "includes the custom load marker name"
            (::dfi/marker marker) => :x
            "places a ready marker in the load queue"
            marker =fn=> (fn [marker] (df/ready? marker))
            "includes the focused query"
            (dfi/data-query marker) => [{[:item/by-id 3] [{:comments [:db/id :title]}]}]))))
    (behavior "accepts parameter map"
      (let [app-state (atom {})]
        (df/load-field-action app-state Item [:item/by-id 3] :comments {:marker :x :without #{:author}})

        (let [marker (first (get-in @app-state [:fulcro/ready-to-load]))]
          (assertions
            "includes the custom load marker name"
            (::dfi/marker marker) => :x
            "places a ready marker in the load queue"
            marker =fn=> (fn [marker] (df/ready? marker))
            "includes the focused query"
            (dfi/data-query marker) => [{[:item/by-id 3] [{:comments [:db/id :title]}]}]))))))

(specification "full-query"
  (let [item-ready-markers [(dfi/ready-state {:ident [:db/id 1] :field :author :query [{:author [:name]}]})
                            (dfi/ready-state {:ident [:db/id 2] :field :author :query [{:author [:name]}]})]
        top-level-markers  [(dfi/ready-state {:query [{:questions [:db/id :name]}]})
                            (dfi/ready-state {:query [{:answers [:db/id :name]}]})]]
    (behavior "composes items queries"
      (is (= [{[:db/id 1] [{:author [:name]}]} {[:db/id 2] [{:author [:name]}]}] (dfi/full-query item-ready-markers))))
    (behavior "composes top-level queries"
      (is (= [{:questions [:db/id :name]} {:answers [:db/id :name]}] (dfi/full-query top-level-markers))))))

(specification "data-query-key of a fetch marker's query"
  (assertions
    "is the first keyword of simple props"
    (dfi/data-query-key {::prim/query [:a :b :c]}) => :a
    "is the first keyword the first join"
    (dfi/data-query-key {::prim/query [{:a [:x :y]} :b {:c [:z]}]}) => :a
    "tolerates parameters"
    (dfi/data-query-key {::prim/query '[({:a [:x :y]} {:n 1}) :b {:c [:z]}]}) => :a
    (dfi/data-query-key {::prim/query '[(:a {:n 1}) :b {:c [:z]}]}) => :a))

(defn mark-loading-mutate [])
(defn mark-loading-fallback [])

(defmethod m/mutate 'mark-loading-test/callback [e k p] {:action #(mark-loading-mutate)})
(defmethod m/mutate 'mark-loading-test/fallback [_ _ _] {:action #(mark-loading-fallback)})

(specification "The inject-query-params function"
  (let [prop-ast                  (prim/query->ast [:a :b :c])
        prop-params               {:a {:x 1} :c {:y 2}}
        join-ast                  (prim/query->ast [:a {:things [:name]}])
        join-params               {:things {:start 1}}
        existing-params-ast       (prim/query->ast '[(:a {:x 1})])
        existing-params-overwrite {:a {:x 2}}]
    (assertions
      "can add parameters to a top-level query property"
      (prim/ast->query (dfi/inject-query-params prop-ast prop-params)) => '[(:a {:x 1}) :b (:c {:y 2})]
      "can add parameters to a top-level join property"
      (prim/ast->query (dfi/inject-query-params join-ast join-params)) => '[:a ({:things [:name]} {:start 1})]
      "merges new parameters over existing ones"
      (prim/ast->query (dfi/inject-query-params existing-params-ast existing-params-overwrite)) => '[(:a {:x 2})])))

(specification "set-global-loading"
  (let [loading-state     (atom {:ui/loading-data          true
                                 :fulcro/loads-in-progress #{1}})
        not-loading-state (atom {:ui/loading-data          true
                                 :fulcro/loads-in-progress #{}})]
    (when-mocking
      (prim/app-state r) => not-loading-state

      (#'dfi/set-global-loading! :reconciler)

      (assertions
        "clears the marker if nothing is in the loading set"
        (-> @not-loading-state :ui/loading-data) => false))

    (when-mocking
      (prim/app-state r) => loading-state

      (#'dfi/set-global-loading! :reconciler)

      (assertions
        "sets the marker if anything is in the loading set"
        (-> @loading-state :ui/loading-data) => true))))

(specification "Rendering lazily loaded data"
  (let [ready-props   {:ui/fetch-state (dfi/make-data-state :ready)}
        loading-props (update ready-props :ui/fetch-state dfi/set-loading!)
        failed-props  (update ready-props :ui/fetch-state dfi/set-failed!)
        props         {:foo :bar}]

    (letfn [(ready-override [_] :ready-override)
            (loading-override [_] :loading-override)
            (failed-override [_] :failed-override)
            (not-present [_] :not-present)
            (present [props] (if (nil? props) :baz (:foo props)))]

      (assertions
        "When props are ready to load, runs ready-render"
        (df/lazily-loaded present ready-props :ready-render ready-override) => :ready-override

        "When props are loading, runs loading-render"
        (df/lazily-loaded present loading-props :loading-render loading-override) => :loading-override

        "When loading the props failed, runs failed-render"
        (df/lazily-loaded present failed-props :failed-render failed-override) => :failed-override

        "When the props are nil and not-present-renderer provided, runs not-present-render"
        (df/lazily-loaded present nil :not-present-render not-present) => :not-present

        "When the props are nil without a not-present-renderer, runs data-render"
        (df/lazily-loaded present nil) => :baz

        "When props are loaded, runs data-render."
        (df/lazily-loaded present props) => :bar))))

(defmethod m/mutate 'qrp-loaded-callback [{:keys [state]} n p] (swap! state assoc :callback-done true :callback-params p))

(def empty-history (hist/new-history 100))

#?(:cljs
   (specification "Query response processing (loaded-callback with post mutation)"
     (let [item            (dfi/set-loading! (dfi/ready-state {:ident                [:item 2]
                                                               :query                [:id :b]
                                                               :refresh              [:x :y]
                                                               :post-mutation        'qrp-loaded-callback
                                                               :post-mutation-params {:x 1}}))
           state           (atom {:fulcro/loads-in-progress #{(dfi/data-uuid item)}
                                  :item                     {2 {:id :original-data}}})
           items           [item]
           queued          (atom [])
           rendered        (atom false)
           merged          (atom false)
           globally-marked (atom false)
           loaded-cb       (#'dfi/loaded-callback :reconciler)
           response        {:id 2}]
       (when-mocking
         (prim/app-state r) => state
         (prim/get-history r) => (atom empty-history)
         (prim/merge! r resp query) => (reset! merged true)
         (util/force-render r ks) => (reset! rendered ks)
         (dfi/tick! r) => nil
         (dfi/set-global-loading! r) => (reset! globally-marked true)

         (loaded-cb response items)

         (assertions
           "Merges response with app state"
           @merged => true
           "Runs post-mutations"
           (:callback-done @state) => true
           (:callback-params @state) => {:x 1}
           "Triggers a render for :ui/loading-data and any addl keys requested by mutations (in addition to any top-level keys of the query)"
           (set @rendered) => #{dfi/marker-table :ui/loading-data :ui/fetch-state :x :y :id [:item 2]}
           "Removes loading markers for results that didn't materialize"
           (get-in @state (dfi/data-path item) :fail) => nil
           "Updates the global loading marker"
           @globally-marked => true)))))

#?(:cljs
   (specification "Query Response with :initialize (load ... {:initialize true})" :focused
     (let [item      (dfi/set-loading! (dfi/ready-state (df/load-params* :root/comp InitTestComponent {:initialize true})))
           state     (atom {:fulcro/loads-in-progress #{(dfi/data-uuid item)}})
           items     [item]
           loaded-cb (#'dfi/loaded-callback :reconciler)
           response  {:root/comp {:z 55 :child {:y 77}}}]
       (when-mocking
         (prim/app-state r) => state
         (prim/get-history r) => (atom empty-history)
         (prim/merge! r resp query) => (do
                                         (assertions
                                           "Response is deep merged with initialized data before being merged with app state"
                                           resp => {:root/comp {:x 1 :z 55 :child {:y 77}}}))
         (util/force-render r ks) => nil
         (dfi/tick! r) => nil
         (dfi/set-global-loading! r) => nil

         (loaded-cb response items)))))

#?(:cljs
   (specification "Query response processing (loaded-callback with no post-mutations)"
     (let [item            (dfi/set-loading! (dfi/ready-state {:ident [:item 2] :query [:id :b] :refresh [:a]}))
           state           (atom {:fulcro/loads-in-progress #{(dfi/data-uuid item)}
                                  :item                     {2 {:id :original-data}}})
           items           [item]
           queued          (atom [])
           rendered        (atom false)
           merged          (atom false)
           globally-marked (atom false)
           loaded-cb       (#'dfi/loaded-callback :reconciler)
           response        {:id 2}]
       (when-mocking
         (prim/app-state r) => state
         (dfi/tick! r) => nil
         (prim/merge! r resp query) => (reset! merged true)
         (prim/get-history r) => (atom empty-history)
         (util/force-render r items) => (reset! queued (set items))
         (dfi/set-global-loading! r) => (reset! globally-marked true)

         (loaded-cb response items)

         (assertions
           "Merges response with app state"
           @merged => true
           "Queues the refresh items for refresh"
           @queued =fn=> #(contains? % :a)
           "Queues the global loading marker for refresh"
           @queued =fn=> #(contains? % :ui/loading-data)
           "Queues the marker table for refresh"
           @queued =fn=> #(contains? % :ui.fulcro.client.data-fetch.load-markers/by-id)
           "Queues :ui/fetch-state for refresh"
           @queued =fn=> #(contains? % :ui/fetch-state)
           "Removes loading markers for results that didn't materialize"
           (get-in @state (dfi/data-path item) :fail) => nil
           "Updates the global loading marker"
           @globally-marked => true)))))

(defmethod m/mutate 'qrp-error-fallback [{:keys [state]} n p] (swap! state assoc :fallback-done true))

#?(:cljs
   (specification "Query response processing (error-callback)"
     (let [item            (dfi/set-loading! (dfi/ready-state {:ident [:item 2] :query [:id :b] :fallback 'qrp-error-fallback :refresh [:x :y]}))
           state           (atom {:fulcro/loads-in-progress #{(dfi/data-uuid item)}
                                  :item                     {2 {:id :original-data}}})
           items           [item]
           globally-marked (atom false)
           queued          (atom [])
           rendered        (atom false)
           error-cb        (#'dfi/error-callback :reconciler)
           response        {:id 2}]
       (when-mocking
         (dfi/callback-env r req orig) => {:state state}
         (prim/get-history r) => (atom empty-history)
         (prim/app-state r) => state
         (dfi/record-network-error! r i e) => nil
         (dfi/tick! r) => nil
         (dfi/set-global-loading! r) => (reset! globally-marked true)
         (prim/force-root-render! r) => (assertions
                                          "Triggers render at root"
                                          r => :reconciler)

         (error-cb response items))

       (assertions
         "Runs fallbacks"
         (:fallback-done @state) => true
         "Rewrites load markers as error markers"
         (dfi/failed? (get-in @state (conj (dfi/data-path item) :ui/fetch-state) :fail)) => true
         "Updates the global loading marker"
         @globally-marked => true))))

(specification "fetch marker data-path"
  (assertions
    "is the field path for a load-field marker"
    (dfi/data-path {::prim/ident [:obj 1] ::dfi/field :f}) => [:obj 1 :f]
    "is an ident if the query key is an ident"
    (dfi/data-path {::prim/query [{[:a 1] [:prop]}]}) => [:a 1]
    "is the data-query-key by default"
    (dfi/data-path {::prim/query [:obj]}) => [:obj]
    "is the explicit target if supplied"
    (dfi/data-path {::dfi/target [:a :b]}) => [:a :b]))

#?(:cljs
   (specification "Load markers for field loading (legacy)"
     (let [state  {:t {1 {:id 1}
                       2 {:id 2}}}
           item-1 (dfi/ready-state {:query [:comments] :ident [:t 1] :field :comments :target [:top]})
           item-2 (dfi/ready-state {:query [:comments] :ident [:t 2] :field :comments :marker false})

           state  (#'dfi/place-load-markers state [item-1 item-2])]

       (assertions
         "ignore targeting"
         (get-in state [:top]) => nil
         "are placed in app state only when the fetch requests a marker"
         (get-in state [:t 1 :comments]) =fn=> #(contains? % :ui/fetch-state)
         (get-in state [:t 2]) => {:id 2}
         "are tracked by UUID in :fulcro/loads-in-progress"
         (get state :fulcro/loads-in-progress) =fn=> #(= 2 (count %))))))

#?(:cljs
   (specification "Load markers for field loading (new, by-name)"
     (let [state  {:t {1 {:id 1}
                       2 {:id 2}}}
           item-1 (dfi/ready-state {:query [:comments] :ident [:t 1] :field :comments :marker :my-marker :target [:top]})
           item-2 (dfi/ready-state {:query [:comments] :ident [:t 2] :field :comments :marker false})

           state  (#'dfi/place-load-markers state [item-1 item-2])]

       (assertions
         "ignore targeting"
         (get-in state [:top]) => nil
         "are placed in the marker table when the fetch requests a marker"
         (get-in state [f/marker-table :my-marker]) =fn=> #(contains? % ::dfi/type)
         "are tracked by UUID in :fulcro/loads-in-progress"
         (get state :fulcro/loads-in-progress) =fn=> #(= 2 (count %))))))

(specification "Load markers for regular (top-level) queries (legacy)"
  (let [state  {:users/by-id {4 {:name "Joe"}}
                :t           {1 {:id 1}}}
        item-1 (dfi/ready-state {:query [{:users [:name]}]})
        item-2 (dfi/ready-state {:query [:some-value]})
        item-3 (dfi/ready-state {:query [{:users [:name]}] :target [:t 1 :user]})
        item-4 (dfi/ready-state {:query [:some-value] :target [:t 1 :value]})

        state  (#'dfi/place-load-markers state [item-1 item-2 item-3 item-4])]

    (assertions
      "Default to being placed in app root at the first key of the query"
      (get state :users) =fn=> #(contains? % :ui/fetch-state)
      (get state :some-value) =fn=> #(contains? % :ui/fetch-state)
      "Will appear at alternate target locations"
      (get-in state [:t 1 :user]) =fn=> #(contains? % :ui/fetch-state)
      (get-in state [:t 1 :value]) =fn=> #(contains? % :ui/fetch-state))))

(specification "Load markers for regular (top-level) queries (new, by id)"
  (let [state  {:users/by-id {4 {:name "Joe"}}
                :t           {1 {:id 1}}}
        item-1 (dfi/ready-state {:marker :a :query [{:users [:name]}]})
        item-2 (dfi/ready-state {:marker :b :query [:some-value]})
        item-3 (dfi/ready-state {:marker :c :query [{:users [:name]}] :target [:t 1 :user]})
        item-4 (dfi/ready-state {:marker :d :query [:some-value] :target [:t 1 :value]})

        state  (#'dfi/place-load-markers state [item-1 item-2 item-3 item-4])]

    (assertions
      "Are placed in the markers table"
      (get state f/marker-table) =fn=> #(contains? % :a)
      (get state f/marker-table) =fn=> #(contains? % :b)
      (get state f/marker-table) =fn=> #(contains? % :c)
      (get state f/marker-table) =fn=> #(contains? % :d))))

(specification "Load markers when loading with an ident (new, by id)"
  (let [state  {:users/by-id {4 {:name "Joe"}}
                :t           {1 {:id 1}}}
        item-2 (dfi/ready-state {:marker :a :query [{[:users/by-id 3] [:name]}] :target [:t 1 :user]})
        item-3 (dfi/ready-state {:marker false :query [{[:users/by-id 4] [:name]}]})

        state  (#'dfi/place-load-markers state [item-2 item-3])]

    (assertions
      "Place a marker by ID when asked for"
      (-> state (get f/marker-table) keys set) => #{:a})))

(specification "Load markers when loading with an ident (legacy)"
  (let [state  {:users/by-id {4 {:name "Joe"}}
                :t           {1 {:id 1}}}
        item-2 (dfi/ready-state {:query [{[:users/by-id 3] [:name]}] :target [:t 1 :user]})
        item-3 (dfi/ready-state {:query [{[:users/by-id 4] [:name]}]})

        state  (#'dfi/place-load-markers state [item-2 item-3])]

    (assertions
      "Ignore explicit targeting"
      (get-in state [:t 1 :user :ui/fetch-state]) => nil
      "Only place a marker if there is already an object in the table"
      (get-in state [:users/by-id 3 :ui/fetch-state]) => nil
      (get-in state [:users/by-id 4 :ui/fetch-state]) =fn=> map?)))

(specification "relocating server results"
  (behavior "Does nothing if the item is based on a simple query with no targeting"
    (let [simple-state-atom (atom {:a [[:x 1]]})
          simple-items      #{(dfi/ready-state {:query [{:a [:x]}]})}]

      (dfi/relocate-targeted-results! simple-state-atom simple-items)

      (assertions
        @simple-state-atom => {:a [[:x 1]]})))
  (behavior "Does nothing if the item is a field query"
    (let [simple-state-atom (atom {:a [[:x 1]]})
          mistargeted-items #{(dfi/ready-state {:ident [:obj 1] :field :boo :query [:boo] :target [:obj 1 :boo]})}]

      (dfi/relocate-targeted-results! simple-state-atom mistargeted-items)

      (assertions
        @simple-state-atom => {:a [[:x 1]]})))
  (behavior "Moves simple query results to explicit target"
    (let [simple-state-atom    (atom {:a [[:x 1]]})
          maptarget-state-atom (atom {:a   [[:x 1]]
                                      :obj {1 {:boo {:n 1}}}})
          vectarget-state-atom (atom {:a   [[:x 1]]
                                      :obj {1 {:boo []}}})
          targeted-items       #{(dfi/ready-state {:query [{:a [:x]}] :target [:obj 1 :boo]})}]

      (dfi/relocate-targeted-results! simple-state-atom targeted-items)
      (dfi/relocate-targeted-results! maptarget-state-atom targeted-items)
      (dfi/relocate-targeted-results! vectarget-state-atom targeted-items)

      (assertions
        @simple-state-atom => {:obj {1 {:boo [[:x 1]]}}}
        @maptarget-state-atom => {:obj {1 {:boo [[:x 1]]}}}
        @vectarget-state-atom => {:obj {1 {:boo [[:x 1]]}}}))))

(specification "Splits items to load by join key / ident kind."
  (let [q-a-x  {::prim/query [{:a [:x]}]}
        q-a-y  {::prim/query [{:a [:y]}]}
        q-a-z  {::prim/query [{:a [:z]}]}
        q-a-w  {::prim/query [{:a [:w]}]}
        q-b-x  {::prim/query [{:b [:x]}]}
        q-c-x  {::prim/query [{[:c 999] [:x]}]}
        q-c-y  {::prim/query [{[:c 999] [:y]}]}
        q-c-z  {::prim/query [{[:c 998] [:z]}]}
        q-c-w  {::prim/query [{[:c 998] [:w]}]}
        q-ab-x {::prim/query [{:a [:x]} {:b [:x]}]}
        q-bc-x {::prim/query [{:b [:x]} {:c [:x]}]}
        q-cd-x {::prim/query [{:c [:x]} {:d [:x]}]}
        q-de-x {::prim/query [{:d [:x]} {:e [:x]}]}]
    (assertions
      "loads all items immediately when no join key conflicts, preserving order"
      (dfi/split-items-ready-to-load [q-a-x]) => [[q-a-x] []]
      (dfi/split-items-ready-to-load [q-a-x q-b-x]) => [[q-a-x q-b-x] []]
      (dfi/split-items-ready-to-load [q-a-x q-c-x]) => [[q-a-x q-c-x] []]
      (dfi/split-items-ready-to-load [q-a-x q-b-x q-c-x]) => [[q-a-x q-b-x q-c-x] []]

      "defers loading when join key conflict, preserving order where possible"
      (dfi/split-items-ready-to-load [q-a-x q-a-y q-a-z q-a-w]) => [[q-a-x] [q-a-y q-a-z q-a-w]]
      (dfi/split-items-ready-to-load [q-a-y q-a-z q-a-w]) => [[q-a-y] [q-a-z q-a-w]]
      (dfi/split-items-ready-to-load [q-a-z q-a-w]) => [[q-a-z] [q-a-w]]
      (dfi/split-items-ready-to-load [q-a-w]) => [[q-a-w] []]

      "defers loading when ident key conflict, preserving order where possible"
      (dfi/split-items-ready-to-load [q-c-x q-c-y q-c-z q-c-w]) => [[q-c-x] [q-c-y q-c-z q-c-w]]
      (dfi/split-items-ready-to-load [q-c-y q-c-z q-c-w]) => [[q-c-y] [q-c-z q-c-w]]
      (dfi/split-items-ready-to-load [q-c-z q-c-w]) => [[q-c-z] [q-c-w]]
      (dfi/split-items-ready-to-load [q-c-w]) => [[q-c-w] []]

      "defers loading when any key conflicts, preserving order where possible"
      (dfi/split-items-ready-to-load
        [q-a-x q-a-y q-a-z
         q-b-x
         q-c-x q-c-y q-c-z]) => [[q-a-x q-b-x q-c-x] [q-a-y q-a-z q-c-y q-c-z]]

      "defers loading when join keys partially conflict, preserving order where possible"
      (dfi/split-items-ready-to-load [q-ab-x q-bc-x q-cd-x q-de-x]) => [[q-ab-x q-cd-x] [q-bc-x q-de-x]]
      (dfi/split-items-ready-to-load [q-bc-x q-de-x]) => [[q-bc-x q-de-x] []])))

(specification "is-deferred-transaction?"
  (assertions
    "Returns false for invalid or nil queries"
    (dfi/is-deferred-transaction? nil) => false
    (dfi/is-deferred-transaction? []) => false
    (dfi/is-deferred-transaction? :boo) => false
    "Returns true if the query has a first element that is the namespaced keyword to indicate deferral"
    (dfi/is-deferred-transaction? [::dfi/deferred-transaction]) => true))


(defmutation f [params]
  (action [env] true)
  (remote [env] true))
(defmutation g [params]
  (action [env] true)
  (rest-remote [env] true))
(defmutation h [params]
  (action [env] true))
(defmutation i [params]
  (action [env] true)
  (remote [env] true)
  (rest-remote [env] true))

(defmethod m/mutate `j [{:keys [target]} _ params]
  (cond
    (= target :remote) {:remote true}
    (= target :rest-remote) {:rest-remote true}
    :otherwise {}))

(defmethod m/mutate `unhappy-mutation [env _ params]
  (throw (ex-info "Boo!" {})))

(specification "get-remotes"
  (when-mocking
    (log/error & m) => nil                                  ; suppress logging during test

    (assertions
      "Returns the correct remote for a given mutation"
      (df/get-remotes `f) => #{:remote}
      (df/get-remotes `g) => #{:rest-remote}
      "Returns all remotes that are active for the given mutation"
      (df/get-remotes `i) => #{:remote :rest-remote}
      "Returns all remotes that are active even if the mutation responds via target (as long as legal remotes list is passed in)"
      (df/get-remotes `j #{:remote :rest-remote}) => #{:remote :rest-remote}
      "Returns #{:remote} if the mutation throws an exception"
      (df/get-remotes `unhappy-mutation) => #{:remote}
      "Returns an empty set if the mutation is not remote"
      (df/get-remotes `h) => #{})))

(specification "fallback (the mutation)" :focused
  (behavior "On parse (run of `transact!`)"
    (let [fake-ast (prim/query->ast1 '[(f {:x 1})])]
      (assertions
        "Returns the ast"
        (-> (m/mutate {:ref [:a 1] :target :remote :ast fake-ast} 'tx/fallback {:action 'do-thing}) :remote :dispatch-key) => 'f
        "the AST parameters are augmented with the ref of the caller"
        (-> (m/mutate {:ref [:a 1] :target :remote :ast fake-ast} 'tx/fallback {:action 'do-thing}) :remote :params) => {:x 1 ::prim/ref [:a 1]}
        "Uses the remote based on target (leading to fallbacks appearing for *all* defined remotes, to be filtered at a later stage)"
        (-> (m/mutate {:ref [:a 1] :target :rest :ast fake-ast} 'tx/fallback {:action 'do-thing}) keys set) => #{:rest}
        "Can be invoked as `fulcro.client.data-fetch/fallback`"
        (-> (m/mutate {:ref [:a 1] :target :remote :ast fake-ast} `df/fallback {:action 'do-thing}) :remote :dispatch-key) => 'f)))
  (behavior "On network error (fallback trigger via :execute true)"
    (assertions
      "Returns an action to run"
      (-> (m/mutate {} `df/fallback {:execute true}) keys set) => #{:action})))
