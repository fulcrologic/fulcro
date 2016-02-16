(ns untangled.client.data-fetch-spec
  (:require
    [untangled.client.data-fetch :as df]
    [untangled.client.impl.data-fetch :as dfi]
    [untangled.client.impl.util :as util]
    [om.next :as om :refer-macros [defui]]
    [cljs.test :refer-macros [is are]]
    [untangled-spec.core :refer-macros
     [specification behavior assertions provided component when-mocking]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; SETUP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defui Person
  static om/IQuery (query [_] [:db/id :username :name])
  static om/Ident (ident [_ props] [:person/id (:db/id props)]))

(defui Comment
  static om/IQuery (query [this] [:db/id :title {:author (om/get-query Person)}])
  static om/Ident (ident [this props] [:comments/id (:db/id props)]))

(defui Item
  static om/IQuery (query [this] [:db/id :name {:comments (om/get-query Comment)}])
  static om/Ident (ident [this props] [:items/id (:db/id props)]))

(defui Panel
  static om/IQuery (query [this] [:db/id {:items (om/get-query Item)}])
  static om/Ident (ident [this props] [:panel/id (:db/id props)]))

(defui PanelRoot
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
      (dfi/data-query without-multi-prop) => [{[:item/by-id 1] [{:comments [:title {:author [:username :name]}]}]}])))

(specification "Lazy loading"
  (component "Loading a field within a component"
    (let [query (om/get-query Item)]
      (provided "properly calls transact"
        (om/get-ident c) =1x=> [:item/by-id 10]
        (om/get-query c) =1x=> query
        (om/transact! c tx) =1x=> (let [params (-> tx first second)]
                                    (behavior "with the given component."
                                      (is (= c 'component)))
                                    (behavior "includes the component's ident."
                                      (is (= [:item/by-id 10] (:ident params))))
                                    (behavior "focuses the query to the specified field."
                                      (is (= [{:comments [:db/id :title {:author [:db/id :username :name]}]}]
                                            (:query params))))
                                    (behavior "includes the parameters."
                                      (is (= {:sort :by-name} (:params params))))
                                    (behavior "includes the subquery exclusions."
                                      (is (= #{:excluded-attr} (:without params))))
                                    (behavior "includes the post-processing callback."
                                      (let [cb (:callback params)]
                                        (is (fn? cb))
                                        (is (= "foo" (cb))))))

        (df/load-field 'component :comments
          :without #{:excluded-attr}
          :params {:sort :by-name}
          :callback (fn [] "foo")))))

  (behavior "Loading data for the app in general"
    (provided "when requesting data for a specific ident"
      (om/transact! c tx) => (let [params (-> tx first second)]
                               (behavior "includes without."
                                 (is (= :without (:without params))))
                               (behavior "includes params."
                                 (is (= :params (:params params))))
                               (behavior "includes post-processing callback."
                                 (let [cb (:callback params)]
                                   (is (fn? cb))
                                   (is (= "foo" (cb)))))
                               (behavior "includes the ident in the data state."
                                 (is (= [:item/id 99] (:ident params))))
                               (behavior "includes the query joined to the ident."
                                 (is (= (om/get-query Item) (:query params)))))

      (df/load-singleton 'reconciler (om/get-query Item)
        :ident [:item/id 99]
        :params :params
        :without :without
        :callback (fn [] "foo")))

    (component "when requesting data for a collection"
      (when-mocking
        (om/transact! c tx) => (let [params (-> tx first second)]
                                 (behavior "directly uses the query."
                                   (is (= [{:items (om/get-query Item)}] (:query params)))))

        (df/load-collection 'reconciler [{:items (om/get-query Item)}])))))

(specification "full-query"
  (let [item-ready-markers [(dfi/ready-state :ident [:db/id 1] :field :author :query [{:author [:name]}])
                            (dfi/ready-state :ident [:db/id 2] :field :author :query [{:author [:name]}])]
        top-level-markers [(dfi/ready-state :query [{:questions [:db/id :name]}])
                           (dfi/ready-state :query [{:answers [:db/id :name]}])]]
    (behavior "composes items queries"
      (is (= [{[:db/id 1] [{:author [:name]}]} {[:db/id 2] [{:author [:name]}]}] (dfi/full-query item-ready-markers))))
    (behavior "composes top-level queries"
      (is (= [{:questions [:db/id :name]} {:answers [:db/id :name]}] (dfi/full-query top-level-markers))))))

(defn post-process-fn [data] data)

(specification "mark-loading"
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
      (post-process-fn s) => (behavior "calls post processing function (requiring reconciler app state)."
                               (is (instance? cljs.core/Atom s)))

      (let [_ (df/load-field :mock-2 :comments :callback post-process-fn) ; place ready markers in state
            _ (df/load-field :mock-3 :comments)
            _ (df/load-field :mock-4 :comments)             ; TODO: we should be able to select :on-missing behavior
            {:keys [query on-load on-error]} (df/mark-loading reconciler) ; transition to loading
            loading-state @state
            comments-2 [{:db/id 5 :title "C"} {:db/id 6 :title "D"}]
            comments-3 [{:db/id 8 :title "A"} {:db/id 9 :title "B"}]
            good-response {[:items/id 3] {:comments comments-3}
                           [:items/id 2] {:comments comments-2}}
            item-4-expr {[:items/id 4] [{:comments comment-query}]}
            item-3-expr {[:items/id 3] [{:comments comment-query}]}
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
            (is (:app/loading-data @state))))

        (component "generated query"
          (behavior "composes together all of the item queries"
            (is (= [item-4-expr item-3-expr item-2-expr] query)))

          (behavior "has metadata for proper normalization of a response"
            (is (= normalized-response (om/tree->db query good-response true)))))

        (component "generated on-load handler (reconciler using deep merge)"
          (on-load good-response)

          (behavior "leaves existing data in place"
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

        (component "generated on-error handler"
          (reset! state loading-state)
          (are [id] (df/loading? (get-in @state [:items/id id :comments :ui/fetch-state])) 2 3 4)
          (on-error {})

          (behavior "Marks all loading states as failed"
            (are [id] (df/failed? (get-in @state [:items/id id :comments :ui/fetch-state])) 2 3 4)))))))

(specification "active-loads?"
  (behavior "returns a callback predicate"
    (let [active-load (dfi/make-data-state :loading {:foo :bar})
          loading-items #{active-load (dfi/make-data-state :loading {:x :y})}
          empty-predicate (dfi/active-loads? #{})
          predicate (dfi/active-loads? loading-items)]

      (is (fn? empty-predicate))
      (is (fn? predicate))

      (behavior "that always returns false when passed an empty fetch state set."
        (is (not (empty-predicate active-load)))
        (is (not (empty-predicate nil))))

      (behavior "that returns true when a given data state is contained in the fetch state set."
        (is (predicate active-load)))

      (behavior "that returns false when a given data state is not in the fetch state set."
        (is (not (predicate (dfi/make-data-state :loading))))))))

(specification "set-global-loading"
  (let [loading-state {:app/loading-data true
                       :some             :data
                       :nested           {:information (dfi/make-data-state :loading)}
                       :om.next/tables   #{}}
        loading-reconciler (om/reconciler {:state loading-state :parser (fn [] nil)})
        not-loading-state (assoc-in loading-state [:nested :information] :has-been-loaded)
        not-loading-reconciler (om/reconciler {:state not-loading-state :parser (fn [] nil)})]

    (behavior "does not change reconciler state if any loading markers present."
      (dfi/set-global-loading loading-reconciler)

      (is (= loading-state @loading-reconciler)))

    (behavior "sets app-wide data loading key to false if no loading markers present."
      (dfi/set-global-loading not-loading-reconciler)

      (is (= (assoc not-loading-state :app/loading-data false) @not-loading-reconciler)))))
