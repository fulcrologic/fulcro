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
    [untangled.client.logging :as log]))

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
                              :ident [:item/by-id 1]
                              :field :comments
                              :without without-set
                              :query (om/focus-query (om/get-query Item) [:comments])))

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
    (let [ready-state (dfi/ready-state :query (om/get-query Item) :without #{:name})]
      (is (= [:db/id {:comments [:db/id :title {:author [:db/id :username]}]}] (::dfi/query ready-state)))))

  (behavior "can include parameters when eliding top-level keys from the query"
    (let [ready-state (dfi/ready-state :query (om/get-query Item) :without #{:name} :params {:db/id {:x 1}})]
      (is (= '[(:db/id {:x 1}) {:comments [:db/id :title {:author [:db/id :username]}]}] (::dfi/query ready-state))))))

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

      (let [marker (first (get-in @app-state [::om/ready-to-load]))]
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
                                   follow-on-reads => #{:a :b}
                                   "directly uses the query."
                                   (:query params) => [{:items (om/get-query Item)}]))

        (df/load-data 'reconciler [{:items (om/get-query Item)}] :reads [:a :b]))))

  (component "Loading a collection/singleton from within another mutation"
    (let [app-state (atom {})]

      (df/load-data-action app-state (om/get-query PanelRoot) :without #{:items})

      (let [marker (first (get-in @app-state [::om/ready-to-load]))]
        (assertions
          "places a ready marker in the app state"
          marker =fn=> (fn [marker] (df/ready? marker))
          "includes the focused query"
          (dfi/data-query marker) => [{:panel [:db/id]}])))))

(specification "full-query"
  (let [item-ready-markers [(dfi/ready-state :ident [:db/id 1] :field :author :query [{:author [:name]}])
                            (dfi/ready-state :ident [:db/id 2] :field :author :query [{:author [:name]}])]
        top-level-markers [(dfi/ready-state :query [{:questions [:db/id :name]}])
                           (dfi/ready-state :query [{:answers [:db/id :name]}])]]
    (behavior "composes items queries"
      (is (= [{[:db/id 1] [{:author [:name]}]} {[:db/id 2] [{:author [:name]}]}] (dfi/full-query item-ready-markers))))
    (behavior "composes top-level queries"
      (is (= [{:questions [:db/id :name]} {:answers [:db/id :name]}] (dfi/full-query top-level-markers))))))

(defn mark-loading-mutate [])
(defn mark-loading-fallback [])

(defmethod m/mutate 'mark-loading-test/callback [e k p] {:action #(mark-loading-mutate)})
(defmethod m/mutate 'mark-loading-test/fallback [_ _ _] {:action #(mark-loading-fallback)})

;; This test is kind crappy. Breaks all the time due to crashes after mounting in the DOM.
#_(specification "mark-loading"
    (let [state-tree {:panel {:db/id 1
                              :items [{:db/id 2 :name "Item A"} {:db/id 3 :name "Item B"} {:db/id 4 :name "Item C"}]}}
          item-query (om/get-query Item)
          comment-query (om/get-query Comment)
          reconciler (om/reconciler {:state      state-tree
                                     :merge-tree util/deep-merge
                                     :parser     (om/parser {:read (constantly nil)})})
          _ (om/add-root! reconciler PanelRoot "invisible-specs")
          state (om/app-state reconciler)]

      (when-mocking
        (om/get-query c) => item-query
        (om/get-ident c) => (case c
                              :mock-2 [:items/id 2]
                              :mock-3 [:items/id 3]
                              :mock-4 [:items/id 4])
        (om/transact! c tx) => (let [params (apply concat (-> tx first second (assoc :state state)))]
                                 (apply dfi/mark-ready params))

        (mark-loading-mutate) => :check-that-invoked
        (mark-loading-fallback) => :check-that-invoked

        (let [_ (df/load-field :mock-2 :comments :post-mutation 'mark-loading-test/callback) ; place ready markers in state
              _ (df/load-field :mock-3 :comments :params {:comments {:max-length 20}} :fallback 'mark-loading-test/fallback)
              _ (df/load-field :mock-4 :comments)           ; TODO: we should be able to select :on-missing behavior
              {:keys [query on-load on-error]} (dfi/mark-loading reconciler) ; transition to loading
              loading-state @state
              comments-2 [{:db/id 5 :title "C"} {:db/id 6 :title "D"}]
              comments-3 [{:db/id 8 :title "A"} {:db/id 9 :title "B"}]
              good-response {[:items/id 3] {:comments comments-3}
                             [:items/id 2] {:comments comments-2}}
              item-4-expr {[:items/id 4] [{:comments comment-query}]}
              item-3-expr `{[:items/id 3] [({:comments ~comment-query} {:max-length 20})]}
              item-2-expr {[:items/id 2] [{:comments comment-query}]}
              normalized-response {[:items/id 3]   {:comments [[:comments/id 8] [:comments/id 9]]},
                                   [:items/id 2]   {:comments [[:comments/id 5] [:comments/id 6]]},
                                   :comments/id    {8 {:db/id 8, :title "A"},
                                                    9 {:db/id 9, :title "B"},
                                                    5 {:db/id 5, :title "C"},
                                                    6 {:db/id 6, :title "D"}},
                                   :om.next/tables #{:comments/id}}]

          (component "modifies the app state by"
            (behavior "placing loading markers at each item data path."
              (are [id] (df/loading? (get-in @state [:items/id id :comments :ui/fetch-state])) 2 3 4))

            (behavior "clearing the ready markers."
              (is (empty? (-> @state :om.next/ready-to-load))))

            (behavior "marking the top-level app-state with loading indicator."
              (is (:ui/loading-data @state))))

          (component "generated query"
            (assertions
              "composes together all of the item queries, with desired parameters"
              query => [item-4-expr item-3-expr item-2-expr]
              "has metadata for proper normalization of a response"
              (om/tree->db query good-response true) => normalized-response))

          (component "includes an on-load handler (reconciler using deep merge)"
            true                                            ;(on-load good-response)

            (behavior "that leaves existing data in place"
              (are [path v] (= v (get-in @state path))
                   [:panel/id 1] {:db/id 1, :items [[:items/id 2] [:items/id 3] [:items/id 4]]}))

            (assertions
              "merges each ident-keyed item into existing tables"
              (get-in normalized-response [[:items/id 2] :comments]) => (get-in @state [:items/id 2 :comments])
              (get-in normalized-response [[:items/id 3] :comments]) => (get-in @state [:items/id 3 :comments])

              "merges top-keys for data"
              (get @state :comments/id) => (get normalized-response :comments/id))

            (behavior "marks missing data in the app state as not present."
              (is (nil? (get-in @state [:items/id 4 :comments :ui/fetch-state])))))

          (component "generates an on-error handler"
            (reset! state loading-state)
            (are [id] (df/loading? (get-in @state [:items/id id :comments :ui/fetch-state])) 2 3 4)
            (on-error {:some :error})

            (behavior "Marks all loading states as failed"
              (are [id] (df/failed? (get-in @state [:items/id id :comments :ui/fetch-state])) 2 3 4))
            (assertions
              "Sets global error marker"
              (get @state :untangled/server-error) => {:some :error}))))))

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

(defmethod m/mutate 'qrp-loaded-callback [{:keys [state]} n p] (swap! state assoc :callback-done true))

(specification "Query response processing (loaded-callback)"
  (let [item (dfi/set-loading! (dfi/ready-state :ident [:item 2] :query [:id :b] :post-mutation 'qrp-loaded-callback))
        state (atom {:untangled/loads-in-progress #{(dfi/data-uuid item)}
                     :item                        {2 {:id :original-data}}})
        items [item]
        rendered (atom false)
        merged (atom false)
        globally-marked (atom false)
        loaded-cb (dfi/loaded-callback :reconciler)
        response {:id 2}]
    (when-mocking
      (om/app-state r) => state
      (om/merge! r resp query) => (reset! merged true)
      (om/force-root-render! r) => (reset! rendered true)
      (dfi/set-global-loading r) => (reset! globally-marked true)

      (loaded-cb response items)

      (assertions
        "Merges response with app state"
        @merged => true
        "Runs post-mutations"
        (:callback-done @state) => true
        "Re-renders the application"
        @rendered => true
        "Removes loading markers for results that didn't materialize"
        (get-in @state (dfi/data-path item) :fail) => nil
        "Updates the global loading marker"
        @globally-marked => true))))

(defmethod m/mutate 'qrp-error-fallback [{:keys [state]} n p] (swap! state assoc :fallback-done true))

(specification "Query response processing (error-callback)"
  (let [item (dfi/set-loading! (dfi/ready-state :ident [:item 2] :query [:id :b] :fallback 'qrp-error-fallback))
        state (atom {:untangled/loads-in-progress #{(dfi/data-uuid item)}
                     :item                        {2 {:id :original-data}}})
        items [item]
        globally-marked (atom false)
        rendered (atom false)
        error-cb (dfi/error-callback :reconciler)
        response {:id 2}]
    (when-mocking
      (om/app-state r) => state
      (om/force-root-render! r) => (reset! rendered true)
      (om/merge! r resp) => (assertions
                              "updates the react key to force full DOM re-render"
                              (contains? resp :ui/react-key) => true)
      (dfi/set-global-loading r) => (reset! globally-marked true)

      (error-cb response items)

      (assertions
        "Runs fallbacks"
        (:fallback-done @state) => true
        "Rewrites load markers as error markers"
        (dfi/failed? (get-in @state (dfi/data-path item) :fail)) => true
        "Updates the global loading marker"
        @globally-marked => true))))
