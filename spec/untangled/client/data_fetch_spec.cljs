(ns untangled.client.data-fetch-spec
  (:require
    [untangled.client.data-fetch :as df]
    [untangled.client.impl.data-fetch :as dfi]
    [untangled.client.impl.util :as util]
    [goog.log :as glog]
    [om.next :as om :refer-macros [defui]]
    [cljs.test :refer-macros [is are]]
    [untangled-spec.core :refer-macros
     [specification behavior assertions provided component when-mocking]]
    [untangled.client.mutations :as m]
    [untangled.client.logging :as log]
    [om.next.protocols :as omp]
    [untangled.dom :as udom]
    [untangled.client.core :as uc]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; SETUP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defui ^:once Person
  static om/IQuery (query [_] [:db/id :username :name])
  static om/Ident (ident [_ props] [:person/id (:db/id props)]))

(defui ^:once Comment
  static om/IQuery (query [this] [:db/id :title {:author (om/get-query Person)}])
  static om/Ident (ident [this props] [:comments/id (:db/id props)]))

(defui ^:once Item
  static om/IQuery (query [this] [:db/id :name {:comments (om/get-query Comment)}])
  static om/Ident (ident [this props] [:items/id (:db/id props)]))

(defui ^:once Panel
  static om/IQuery (query [this] [:db/id {:items (om/get-query Item)}])
  static om/Ident (ident [this props] [:panel/id (:db/id props)]))

(defui ^:once PanelRoot
  static om/IQuery (query [this] [{:panel (om/get-query Panel)}]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;  TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(specification "Data states"
  (behavior "are properly initialized."
    (is (df/data-state? (dfi/make-data-state :ready)))
    (try (dfi/make-data-state :invalid-key)
         (catch :default e
           (is (= (.-message e) "INVALID DATA STATE TYPE: :invalid-key")))))

  (behavior "can by identified by type."
    (is (df/ready? (dfi/make-data-state :ready)))
    (is (df/failed? (dfi/make-data-state :failed)))
    (is (df/loading? (dfi/make-data-state :loading)))

    (is (not (df/ready? (dfi/make-data-state :failed))))
    (is (not (df/failed? "foo")))))

(specification "Processed ready states"
  (let [make-ready-marker (fn [without-set]
                            (dfi/ready-state
                              {:ident   [:item/by-id 1]
                               :field   :comments
                               :without without-set
                               :query   (om/focus-query (om/get-query Item) [:comments])}))

        without-join (make-ready-marker #{:author})
        without-prop (make-ready-marker #{:username})
        without-multi-prop (make-ready-marker #{:db/id})]


    (assertions
      "remove the :without portion of the query on joins"
      (dfi/data-query without-join) => [{[:item/by-id 1] [{:comments [:db/id :title]}]}]

      "remove the :without portion of the query on props"
      (dfi/data-query without-prop) => [{[:item/by-id 1] [{:comments [:db/id :title {:author [:db/id :name]}]}]}]

      "remove the :without portion when keyword appears in multiple places in the query"
      (dfi/data-query without-multi-prop) => [{[:item/by-id 1] [{:comments [:title {:author [:username :name]}]}]}]))

  (behavior "can elide top-level keys from the query"
    (let [ready-state (dfi/ready-state {:query (om/get-query Item) :without #{:name}})]
      (is (= [:db/id {:comments [:db/id :title {:author [:db/id :username]}]}] (::dfi/query ready-state)))))

  (behavior "can include parameters when eliding top-level keys from the query"
    (let [ready-state (dfi/ready-state {:query (om/get-query Item) :without #{:name} :params {:db/id {:x 1}}})]
      (is (= '[(:db/id {:x 1}) {:comments [:db/id :title {:author [:db/id :username]}]}] (::dfi/query ready-state)))))

  (behavior "can elide keywords from a union query"
    (assertions
      (om/ast->query
        (dfi/elide-ast-nodes
          (om/query->ast [{:current-tab {:panel [:data] :console [:data] :dashboard [:data]}}])
          #{:console}))
      => [{:current-tab {:panel [:data] :dashboard [:data]}}])))


(specification "Load parameters"
  (let [query-with-params (:query (df/load-params* :prop Person {:params {:n 1}}))
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
      (:query (df/load-params* :prop Person {})) => [{:prop (om/get-query Person)}]
      (:query (df/load-params* [:person/by-id 1] Person {})) => [{[:person/by-id 1] (om/get-query Person)}]
      "Honors target for property-based join"
      (:target (df/load-params* :prop Person {:target [:a :b]})) => [:a :b]
      "Constructs a JOIN query (with params)"
      query-with-params =fn=> (fn [q] (= q `[({:prop ~(om/get-query Person)} {:n 1})]))
      ident-query-with-params =fn=> (fn [q] (= q `[({[:person/by-id 1] ~(om/get-query Person)} {:n 1})])))))

(specification "Load mutation expressions"
  (let [mutation-expr (df/load-mutation {:refresh [:a :b] :query [:my-query]})
        mutation (first mutation-expr)]
    (assertions
      "are vectors"
      mutation-expr =fn=> vector?
      "include an untangled/load with mutation arguments"
      mutation =fn=> list?
      (first mutation) => 'untangled/load
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
    (om/transact! c tx) => (do (assertions
                                 "uses the passed component/app in transact!"
                                 c => :component
                                 "uses the mutation created by load-mutation"
                                 tx => :mutation))
    (om/component? c) => true

    (df/load :component :x Person {})))

(specification "The load-action function"
  (let [state-atom (atom {})]
    (when-mocking
      (df/load-params* key query config) => {:refresh [] :query [:x]}

      (df/load-action state-atom :x Person {})

      (let [query (-> @state-atom :untangled/ready-to-load first ::dfi/query)]
        (assertions
          "State atom ends up with a proper load marker"
          query => [:x])))))

(specification "Lazy loading"
  (component "Loading a field within a component"
    (let [query (om/get-query Item)]
      (provided "properly calls transact"
        (om/get-ident c) =2x=> [:item/by-id 10]
        (om/get-query c) =1x=> query
        (om/transact! c tx) =1x=> (let [params (-> tx first second)
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

  (component "Loading a field from within another mutation"
    (let [app-state (atom {})]
      (df/load-field-action app-state Item [:item/by-id 3] :comments :without #{:author})

      (let [marker (first (get-in @app-state [:untangled/ready-to-load]))]
        (assertions
          "places a ready marker in the app state"
          marker =fn=> (fn [marker] (df/ready? marker))
          "includes the focused query"
          (dfi/data-query marker) => [{[:item/by-id 3] [{:comments [:db/id :title]}]}]))))

  (behavior "Loading data for the app in general"
    (provided "when requesting data for a specific ident"
      (om/transact! c tx) => (let [params (-> tx first second)]
                               (behavior "includes without."
                                 (is (= :without (:without params))))
                               (behavior "includes params."
                                 (is (= :params (:params params))))
                               (behavior "includes post-processing callback."
                                 (let [cb (:post-mutation params)]
                                   (is (= 'foo cb))))
                               (behavior "includes error fallback."
                                 (let [fb (:fallback params)]
                                   (is (= 'bar fb))))
                               (behavior "includes the ident in the data state."
                                 (is (= [:item/id 99] (:ident params))))
                               (behavior "includes the query joined to the ident."
                                 (is (= (om/get-query Item) (:query params)))))

      (df/load-data 'reconciler (om/get-query Item)
                    :ident [:item/id 99]
                    :params :params
                    :without :without
                    :post-mutation 'foo
                    :fallback 'bar))

    (component "when requesting data for a collection"
      (when-mocking
        (om/transact! c tx) => (let [params (-> tx first second)
                                     follow-on-reads (set (rest tx))]
                                 (assertions
                                   "includes the follow-on reads"
                                   (contains? follow-on-reads :a) => true
                                   (contains? follow-on-reads :b) => true
                                   "directly uses the query."
                                   (:query params) => [{:items (om/get-query Item)}]))

        (df/load-data 'reconciler [{:items (om/get-query Item)}] :refresh [:a :b]))))

  (component "Loading a collection/singleton from within another mutation"
    (let [app-state (atom {})]

      (df/load-data-action app-state (om/get-query PanelRoot) :without #{:items})

      (let [marker (first (get-in @app-state [:untangled/ready-to-load]))]
        (assertions
          "places a ready marker in the app state"
          marker =fn=> (fn [marker] (df/ready? marker))
          "includes the focused query"
          (dfi/data-query marker) => [{:panel [:db/id]}])))))

(specification "full-query"
  (let [item-ready-markers [(dfi/ready-state {:ident [:db/id 1] :field :author :query [{:author [:name]}]})
                            (dfi/ready-state {:ident [:db/id 2] :field :author :query [{:author [:name]}]})]
        top-level-markers [(dfi/ready-state {:query [{:questions [:db/id :name]}]})
                           (dfi/ready-state {:query [{:answers [:db/id :name]}]})]]
    (behavior "composes items queries"
      (is (= [{[:db/id 1] [{:author [:name]}]} {[:db/id 2] [{:author [:name]}]}] (dfi/full-query item-ready-markers))))
    (behavior "composes top-level queries"
      (is (= [{:questions [:db/id :name]} {:answers [:db/id :name]}] (dfi/full-query top-level-markers))))))

(specification "data-query-key of a fetch marker's query"
  (assertions
    "is the first keyword of simple props"
    (dfi/data-query-key {::dfi/query [:a :b :c]}) => :a
    "is the first keyword the first join"
    (dfi/data-query-key {::dfi/query [{:a [:x :y]} :b {:c [:z]}]}) => :a
    "tolerates parameters"
    (dfi/data-query-key {::dfi/query '[({:a [:x :y]} {:n 1}) :b {:c [:z]}]}) => :a
    (dfi/data-query-key {::dfi/query '[(:a {:n 1}) :b {:c [:z]}]}) => :a))

(defn mark-loading-mutate [])
(defn mark-loading-fallback [])

(defmethod m/mutate 'mark-loading-test/callback [e k p] {:action #(mark-loading-mutate)})
(defmethod m/mutate 'mark-loading-test/fallback [_ _ _] {:action #(mark-loading-fallback)})

(specification "The inject-query-params function"
  (let [prop-ast (om/query->ast [:a :b :c])
        prop-params {:a {:x 1} :c {:y 2}}
        join-ast (om/query->ast [:a {:things [:name]}])
        join-params {:things {:start 1}}
        existing-params-ast (om/query->ast '[(:a {:x 1})])
        existing-params-overwrite {:a {:x 2}}]
    (assertions
      "can add parameters to a top-level query property"
      (om/ast->query (dfi/inject-query-params prop-ast prop-params)) => '[(:a {:x 1}) :b (:c {:y 2})]
      "can add parameters to a top-level join property"
      (om/ast->query (dfi/inject-query-params join-ast join-params)) => '[:a ({:things [:name]} {:start 1})]
      "merges new parameters over existing ones"
      (om/ast->query (dfi/inject-query-params existing-params-ast existing-params-overwrite)) => '[(:a {:x 2})])
    (behavior "Warns about parameters that cannot be joined to the query"
      (let [ast (om/query->ast [:a :b])
            params {:c {:x 1}}]
        (when-mocking
          (glog/error obj msg) => (is (= "Error: You attempted to add parameters for #{:c} to top-level key(s) of [:a :b]" msg))

          (dfi/inject-query-params ast params)
          )))))

(specification "set-global-loading"
  (let [loading-state (atom {:ui/loading-data             true
                             :untangled/loads-in-progress #{1}})
        not-loading-state (atom {:ui/loading-data             true
                                 :untangled/loads-in-progress #{}})]
    (when-mocking
      (om/app-state r) => not-loading-state

      (dfi/set-global-loading :reconciler)

      (assertions
        "clears the marker if nothing is in the loading set"
        (-> @not-loading-state :ui/loading-data) => false))

    (when-mocking
      (om/app-state r) => loading-state

      (dfi/set-global-loading :reconciler)

      (assertions
        "sets the marker if anything is in the loading set"
        (-> @loading-state :ui/loading-data) => true))))

(specification "Rendering lazily loaded data"
  (let [ready-props {:ui/fetch-state (dfi/make-data-state :ready)}
        loading-props (update ready-props :ui/fetch-state dfi/set-loading!)
        failed-props (update ready-props :ui/fetch-state dfi/set-failed!)
        props {:foo :bar}]

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

(specification "Query response processing (loaded-callback with post mutation)"
  (let [item (dfi/set-loading! (dfi/ready-state {:ident                [:item 2]
                                                 :query                [:id :b]
                                                 :refresh              [:x :y]
                                                 :post-mutation        'qrp-loaded-callback
                                                 :post-mutation-params {:x 1}}))
        state (atom {:untangled/loads-in-progress #{(dfi/data-uuid item)}
                     :item                        {2 {:id :original-data}}})
        items [item]
        queued (atom [])
        rendered (atom false)
        merged (atom false)
        globally-marked (atom false)
        loaded-cb (dfi/loaded-callback :reconciler)
        response {:id 2}]
    (when-mocking
      (om/app-state r) => state
      (om/merge! r resp query) => (reset! merged true)
      (udom/force-render r ks) => (reset! rendered ks)
      (dfi/set-global-loading r) => (reset! globally-marked true)

      (loaded-cb response items)

      (assertions
        "Merges response with app state"
        @merged => true
        "Runs post-mutations"
        (:callback-done @state) => true
        (:callback-params @state) => {:x 1}
        "Triggers a render for :ui/loading-data and any addl keys requested by mutations"
        @rendered => [:ui/loading-data :x :y]
        "Removes loading markers for results that didn't materialize"
        (get-in @state (dfi/data-path item) :fail) => nil
        "Updates the global loading marker"
        @globally-marked => true))))

(specification "Query response processing (loaded-callback with no post-mutations)"
  (let [item (dfi/set-loading! (dfi/ready-state {:ident [:item 2] :query [:id :b] :refresh [:a]}))
        state (atom {:untangled/loads-in-progress #{(dfi/data-uuid item)}
                     :item                        {2 {:id :original-data}}})
        items [item]
        queued (atom [])
        rendered (atom false)
        merged (atom false)
        globally-marked (atom false)
        loaded-cb (dfi/loaded-callback :reconciler)
        response {:id 2}]
    (when-mocking
      (om/app-state r) => state
      (om/merge! r resp query) => (reset! merged true)
      (udom/force-render r items) => (reset! queued (set items))
      (dfi/set-global-loading r) => (reset! globally-marked true)

      (loaded-cb response items)

      (assertions
        "Merges response with app state"
        @merged => true
        "Queues the refresh items for refresh"
        @queued =fn=> #(contains? % :a)
        "Queues the global loading marker for refresh"
        @queued =fn=> #(contains? % :ui/loading-data)
        "Removes loading markers for results that didn't materialize"
        (get-in @state (dfi/data-path item) :fail) => nil
        "Updates the global loading marker"
        @globally-marked => true))))

(defmethod m/mutate 'qrp-error-fallback [{:keys [state]} n p] (swap! state assoc :fallback-done true))

(specification "Query response processing (error-callback)"
  (let [item (dfi/set-loading! (dfi/ready-state {:ident [:item 2] :query [:id :b] :fallback 'qrp-error-fallback :refresh [:x :y]}))
        state (atom {:untangled/loads-in-progress #{(dfi/data-uuid item)}
                     :item                        {2 {:id :original-data}}})
        items [item]
        globally-marked (atom false)
        queued (atom [])
        rendered (atom false)
        error-cb (dfi/error-callback :reconciler)
        response {:id 2}]
    (when-mocking
      (om/app-state r) => state
      (omp/queue! r items) => (reset! queued (set items))
      (omp/schedule-render! r) => (reset! rendered true)
      (om/merge! r resp) => (assertions
                              "updates the react key to force full DOM re-render"
                              (contains? resp :ui/react-key) => true)
      (dfi/set-global-loading r) => (reset! globally-marked true)

      (error-cb response items))

    (assertions
      "Runs fallbacks"
      (:fallback-done @state) => true
      "Queues the refresh items for refresh"
      @queued =fn=> #(contains? % :x)
      @queued =fn=> #(contains? % :y)
      "Queues the global loading marker for refresh"
      @queued =fn=> #(contains? % :ui/loading-data)
      "Triggers render"
      @rendered => true
      "Rewrites load markers as error markers"
      (dfi/failed? (get-in @state (conj (dfi/data-path item) :ui/fetch-state) :fail)) => true
      "Updates the global loading marker"
      @globally-marked => true)))

(specification "fetch marker data-path"
  (assertions
    "is the field path for a load-field marker"
    (dfi/data-path {::dfi/ident [:obj 1] ::dfi/field :f}) => [:obj 1 :f]
    "is the data-query-key by default"
    (dfi/data-path {::dfi/query [:obj]}) => [:obj]
    "is the explicit target if supplied"
    (dfi/data-path {::dfi/target [:a :b]}) => [:a :b]))

(specification "Load markers"
  (let [state (atom {:t {1 {:id 1}
                         2 {:id 2}}})
        item-1 (dfi/ready-state {:query [:comments] :ident [:t 1] :field :comments})
        item-2 (dfi/ready-state {:query [:comments] :ident [:t 2] :field :comments :marker false})]

    (dfi/place-load-markers state [item-1 item-2])

    (assertions
      "are placed in app state when the fetch requests a marker"
      (get-in @state [:t 1 :comments]) =fn=> #(contains? % :ui/fetch-state)
      (get-in @state [:t 2]) => {:id 2}
      "are tracked by UUID in :untangled/loads-in-progress"
      (get @state :untangled/loads-in-progress) =fn=> #(= 2 (count %)))))

(specification "relocating server results"
  (behavior "Does nothing if the item is based on a simple query with no targeting"
    (let [simple-state-atom (atom {:a [[:x 1]]})
          simple-items #{(dfi/ready-state {:query [{:a [:x]}]})}]

      (dfi/relocate-targeted-results simple-state-atom simple-items)

      (assertions
        @simple-state-atom => {:a [[:x 1]]})))
  (behavior "Does nothing if the item is a field query"
    (let [simple-state-atom (atom {:a [[:x 1]]})
          mistargeted-items #{(dfi/ready-state {:ident [:obj 1] :field :boo :query [:boo] :target [:obj 1 :boo]})}]

      (dfi/relocate-targeted-results simple-state-atom mistargeted-items)

      (assertions
        @simple-state-atom => {:a [[:x 1]]})))
  (behavior "Moves simple query results to explicit target"
    (let [simple-state-atom (atom {:a [[:x 1]]})
          maptarget-state-atom (atom {:a   [[:x 1]]
                                      :obj {1 {:boo {:n 1}}}})
          vectarget-state-atom (atom {:a   [[:x 1]]
                                      :obj {1 {:boo []}}})
          targeted-items #{(dfi/ready-state {:query [{:a [:x]}] :target [:obj 1 :boo]})}]

      (dfi/relocate-targeted-results simple-state-atom targeted-items)
      (dfi/relocate-targeted-results maptarget-state-atom targeted-items)
      (dfi/relocate-targeted-results vectarget-state-atom targeted-items)

      (assertions
        @simple-state-atom => {:obj {1 {:boo [[:x 1]]}}}
        @maptarget-state-atom => {:obj {1 {:boo [[:x 1]]}}}
        @vectarget-state-atom => {:obj {1 {:boo [[:x 1]]}}}))))

(specification "Splits items to load by join key / ident kind."
  (let [q-a-x {::dfi/query [{:a [:x]}]}
        q-a-y {::dfi/query [{:a [:y]}]}
        q-a-z {::dfi/query [{:a [:z]}]}
        q-a-w {::dfi/query [{:a [:w]}]}
        q-b-x {::dfi/query [{:b [:x]}]}
        q-c-x {::dfi/query [{[:c 999] [:x]}]}
        q-c-y {::dfi/query [{[:c 999] [:y]}]}
        q-c-z {::dfi/query [{[:c 998] [:z]}]}
        q-c-w {::dfi/query [{[:c 998] [:w]}]}
        q-ab-x {::dfi/query [{:a [:x]} {:b [:x]}]}
        q-bc-x {::dfi/query [{:b [:x]} {:c [:x]}]}
        q-cd-x {::dfi/query [{:c [:x]} {:d [:x]}]}
        q-de-x {::dfi/query [{:d [:x]} {:e [:x]}]}]
    (assertions
      "loads all items immediately when no join key conflicts"
      (dfi/split-items-ready-to-load [q-a-x]) => [#{q-a-x} []]
      (dfi/split-items-ready-to-load [q-a-x q-b-x]) => [#{q-a-x q-b-x} []]
      (dfi/split-items-ready-to-load [q-a-x q-c-x]) => [#{q-a-x q-c-x} []]
      (dfi/split-items-ready-to-load [q-a-x q-b-x q-c-x]) => [#{q-a-x q-b-x q-c-x} []]

      "defers loading when join key conflict"
      (dfi/split-items-ready-to-load [q-a-x q-a-y q-a-z q-a-w]) => [#{q-a-x} [q-a-y q-a-z q-a-w]]
      (dfi/split-items-ready-to-load [q-a-y q-a-z q-a-w]) => [#{q-a-y} [q-a-z q-a-w]]
      (dfi/split-items-ready-to-load [q-a-z q-a-w]) => [#{q-a-z} [q-a-w]]
      (dfi/split-items-ready-to-load [q-a-w]) => [#{q-a-w} []]

      "defers loading when ident key conflict"
      (dfi/split-items-ready-to-load [q-c-x q-c-y q-c-z q-c-w]) => [#{q-c-x} [q-c-y q-c-z q-c-w]]
      (dfi/split-items-ready-to-load [q-c-y q-c-z q-c-w]) => [#{q-c-y} [q-c-z q-c-w]]
      (dfi/split-items-ready-to-load [q-c-z q-c-w]) => [#{q-c-z} [q-c-w]]
      (dfi/split-items-ready-to-load [q-c-w]) => [#{q-c-w} []]

      "defers loading when any key conflicts"
      (dfi/split-items-ready-to-load
        [q-a-x q-a-y q-a-z
         q-b-x
         q-c-x q-c-y q-c-z]) => [#{q-a-x q-b-x q-c-x} [q-a-y q-a-z q-c-y q-c-z]]

      "defers loading when join keys partially conflict"
      (dfi/split-items-ready-to-load [q-ab-x q-bc-x q-cd-x q-de-x]) => [#{q-ab-x q-cd-x} [q-bc-x q-de-x]]
      (dfi/split-items-ready-to-load [q-bc-x q-de-x]) => [#{q-bc-x q-de-x} []])))
