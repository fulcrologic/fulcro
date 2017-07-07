(ns untangled.client.impl.om-plumbing-spec
  (:require
    [om.next :as om]
    [untangled.client.impl.om-plumbing :as impl]
    [untangled.i18n :as i18n]
    [cljs.core.async :as async]
    [untangled-spec.core :refer-macros [specification behavior assertions provided component when-mocking]]
    [cljs.test :refer-macros [is are]]))

(specification "Local read can"
  (let [state  (atom {:top-level    :top-level-value
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
        parser           (partial (om/parser {:read (partial impl/read-local (constantly false))}) {:state state})
        augmented-parser (partial (om/parser {:read (partial impl/read-local custom-read)}) {:state state})]

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
          parser (partial (om/parser {:read (partial impl/read-local (constantly nil))}) {:state (atom state)})]

      (assertions
        "read recursion nested in a join underneath a union"
        (parser '[{:curr-view {:settings [*] :main [{:curr-item [:foo {:sub-items ...}]}]}}]) =>
        {:curr-view {:curr-item [{:foo :baz :sub-items [{:foo :bar}]}]}}))))

(specification "remove-loads-and-fallbacks"
  (behavior "Removes top-level mutations that use the untangled/load or tx/fallback symbols"
    (are [q q2] (= (impl/remove-loads-and-fallbacks q) q2)
                '[:a {:j [:a]} (f) (untangled/load {:x 1}) (app/l) (tx/fallback {:a 3})] '[:a {:j [:a]} (f) (app/l)]
                '[(untangled/load {:x 1}) (app/l) (tx/fallback {:a 3})] '[(app/l)]
                '[(untangled/load {:x 1}) (tx/fallback {:a 3})] '[]
                '[(boo {:x 1}) (untangled.client.data-fetch/fallback {:a 3})] '[(boo {:x 1})]
                '[:a {:j [:a]}] '[:a {:j [:a]}])))

(specification "fallback-query"
  (behavior "extracts the fallback expressions of a query, adds execute flags, and includes errors in params"
    (are [q q2] (= (impl/fallback-query q {:error 42}) q2)
                '[:a :b] nil

                '[:a {:j [:a]} (f) (untangled/load {:x 1}) (app/l) (tx/fallback {:a 3})]
                '[(tx/fallback {:a 3 :execute true :error {:error 42}})]

                '[:a {:j [:a]} (tx/fallback {:b 4}) (f) (untangled/load {:x 1}) (app/l) (untangled.client.data-fetch/fallback {:a 3})]
                '[(tx/fallback {:b 4 :execute true :error {:error 42}}) (untangled.client.data-fetch/fallback {:a 3 :execute true :error {:error 42}})])))

(specification "tempid handling"
  (behavior "rewrites all tempids used in pending requests in the request queue"
    (let [queue           (async/chan 10000)
          tid1            (om/tempid)
          tid2            (om/tempid)
          tid3            (om/tempid)
          rid1            4
          rid2            2
          rid3            42
          tid->rid        {tid1 rid1
                           tid2 rid2
                           tid3 rid3}
          q               (fn [id] {:query `[(app/thing {:id ~id})]})
          expected-result [(q rid1) (q rid2) (q rid3)]
          results         (atom [])]

      (async/offer! queue (q tid1))
      (async/offer! queue (q tid2))
      (async/offer! queue (q tid3))

      (impl/rewrite-tempids-in-request-queue queue tid->rid)

      (swap! results conj (async/poll! queue))
      (swap! results conj (async/poll! queue))
      (swap! results conj (async/poll! queue))

      (is (nil? (async/poll! queue)))
      (is (= expected-result @results))))

  (let [tid            (om/tempid)
        tid2           (om/tempid)
        rid            1
        state          {:thing  {tid  {:id tid}
                                 tid2 {:id tid2}}           ; this one isn't in the remap, and should not be touched
                        :things [[:thing tid]]}
        expected-state {:thing  {rid  {:id rid}
                                 tid2 {:id tid2}}
                        :things [[:thing rid]]}
        reconciler     (om/reconciler {:state state :parser {:read (constantly nil)} :migrate impl/resolve-tempids})]

    (assertions
      "rewrites all tempids in the app state (leaving unmapped ones alone)"
      ((-> reconciler :config :migrate) @reconciler {tid rid}) => expected-state)))

(specification "strip-ui"
  (let [q1     [:username :password :ui/login-dropdown-showing {:forgot-password [:email :ui/forgot-button-showing]}]
        q2     [:username :password :ui.login/dropdown-showing {:forgot-password [:email :ui.forgot/button-showing]}]
        result [:username :password {:forgot-password [:email]}]]

    (assertions
      "removes keywords with a ui namespace"
      (impl/strip-ui q1) => result
      "removes keywords with a ui.{something} namespace"
      (impl/strip-ui q2) => result))

  (let [query '[(app/x {:ui/boo 23})]]
    (assertions
      "does not remove ui prefixed data from parameters"
      (impl/strip-ui query) => query)))

(specification "mark-missing"
  (behavior "correctly marks missing properties"
    (are [query ?missing-result exp]
      (= exp (impl/mark-missing ?missing-result query))
      [:a :b]
      {:a 1}
      {:a 1 :b impl/nf}))

  (behavior "joins -> one"
    (are [query ?missing-result exp]
      (= exp (impl/mark-missing ?missing-result query))
      [:a {:b [:c]}]
      {:a 1}
      {:a 1 :b impl/nf}

      [{:b [:c]}]
      {:b {}}
      {:b {:c impl/nf}}

      [{:b [:c]}]
      {:b {:c 0}}
      {:b {:c 0}}

      [{:b [:c :d]}]
      {:b {:c 1}}
      {:b {:c 1 :d impl/nf}}))

  (behavior "join -> many"
    (are [query ?missing-result exp]
      (= exp (impl/mark-missing ?missing-result query))

      [{:a [:b :c]}]
      {:a [{:b 1 :c 2} {:b 1}]}
      {:a [{:b 1 :c 2} {:b 1 :c impl/nf}]}))

  (behavior "idents and ident joins"
    (are [query ?missing-result exp]
      (= exp (impl/mark-missing ?missing-result query))
      [{[:a 1] [:x]}]
      {[:a 1] {}}
      {[:a 1] {:x impl/nf}}

      [{[:b 1] [:x]}]
      {[:b 1] {:x 2}}
      {[:b 1] {:x 2}}

      [{[:c 1] [:x]}]
      {}
      {[:c 1] {:ui/fetch-state {:untangled.client.impl.data-fetch/type :not-found}
               :x              impl/nf}}

      [{[:e 1] [:x :y :z]}]
      {}
      {[:e 1] {:ui/fetch-state {:untangled.client.impl.data-fetch/type :not-found}
               :x              impl/nf
               :y              impl/nf
               :z              impl/nf}}

      [[:d 1]]
      {}
      {[:d 1] {:ui/fetch-state {:untangled.client.impl.data-fetch/type :not-found}}}))

  (behavior "paramterized"
    (are [query ?missing-result exp]
      (= exp (impl/mark-missing ?missing-result query))
      '[:z (:y {})]
      {:z 1}
      {:z 1 :y impl/nf}

      '[:z (:y {})]
      {:z 1 :y 0}
      {:z 1 :y 0}

      '[:z ({:y [:x]} {})]
      {:z 1 :y {}}
      {:z 1 :y {:x impl/nf}}))

  (behavior "nested"
    (are [query ?missing-result exp]
      (= exp (impl/mark-missing ?missing-result query))
      [{:b [:c {:d [:e]}]}]
      {:b {:c 1}}
      {:b {:c 1 :d impl/nf}}

      [{:b [:c {:d [:e]}]}]
      {:b {:c 1 :d {}}}
      {:b {:c 1 :d {:e impl/nf}}}))

  (behavior "upgrades value to maps if necessary"
    (are [query ?missing-result exp]
      (= exp (impl/mark-missing ?missing-result query))
      [{:l [:m]}]
      {:l 0}
      {:l {:m impl/nf}}

      [{:b [:c]}]
      {:b nil}
      {:b {:c impl/nf}}))

  (behavior "unions"
    (are [query ?missing-result exp]
      (= exp (impl/mark-missing ?missing-result query))

      ;singleton
      [{:j {:a [:c]
            :b [:d]}}]
      {:j {:c {}}}
      {:j {:c {}
           :d impl/nf}}

      ;singleton with no result
      [{:j {:a [:c]
            :b [:d]}}]
      {}
      {:j impl/nf}

      ;list
      [{:j {:a [:c]
            :b [:d]}}]
      {:j [{:c "c"}]}
      {:j [{:c "c" :d impl/nf}]}

      [{:items
        {:photo [:id :image]
         :text  [:id :text]}}]
      {:items
       [{:id 0 :image "img1"}
        {:id 1 :text "text1"}]}
      {:items [{:id 0 :image "img1" :text impl/nf}
               {:id 1 :image impl/nf :text "text1"}]}

      ;list with no results
      [{:j {:a [:c]
            :b [:d]}}]
      {:j []}
      {:j []}))

  (behavior "if the query has a ui.*/ attribute, it should not be marked as missing"
    (are [query ?missing-result exp]
      (= exp (impl/mark-missing ?missing-result query))

      [:a :ui/b :c]
      {:a {}
       :c {}}
      {:a {}
       :c {}}

      [{:j [:ui/b :c]}]
      {:j {:c 5}}
      {:j {:c 5}}))

  (behavior "mutations!"
    (are [query ?missing-result exp]
      (= exp (impl/mark-missing ?missing-result query))

      '[(f) {:j [:a]}]
      {'f {}
       :j {}}
      {'f {}
       :j {:a impl/nf}}

      '[(app/add-q {:p 1}) {:j1 [:p1]} {:j2 [:p2]}]
      {'app/add-q {:tempids {}}
       :j1        {}
       :j2        [{:p2 2} {}]}
      {'app/add-q {:tempids {}}
       :j1        {:p1 impl/nf}
       :j2        [{:p2 2} {:p2 impl/nf}]}))

  (behavior "correctly walks recursive queries to mark missing data"
    (behavior "when the recursive target is a singleton"
      (are [query ?missing-result exp]
        (= exp (impl/mark-missing ?missing-result query))
        [:a {:b '...}]
        {:a 1 :b {:a 2}}
        {:a 1 :b {:a 2 :b impl/nf}}

        [:a {:b '...}]
        {:a 1 :b {:a 2 :b {:a 3}}}
        {:a 1 :b {:a 2 :b {:a 3 :b impl/nf}}}

        [:a {:b 9}]
        {:a 1 :b {:a 2 :b {:a 3 :b {:a 4}}}}
        {:a 1 :b {:a 2 :b {:a 3 :b {:a 4 :b impl/nf}}}}))
    (behavior "when the recursive target is to-many"
      (are [query ?missing-result exp]
        (= exp (impl/mark-missing ?missing-result query))
        [:a {:b '...}]
        {:a 1 :b [{:a 2 :b [{:a 3}]}
                  {:a 4}]}
        {:a 1 :b [{:a 2 :b [{:a 3 :b impl/nf}]}
                  {:a 4 :b impl/nf}]})))
  (behavior "marks leaf data based on the query where"
    (letfn [(has-leaves [leaf-paths] (fn [result] (every? #(impl/leaf? (get-in result %)) leaf-paths)))]
      (assertions
        "plain data is always a leaf"
        (impl/mark-missing {:a 1 :b {:x 5}} [:a {:b [:x]}]) =fn=> (has-leaves [[:b :x] [:a] [:missing]])
        "data structures are properly marked in singleton results"
        (impl/mark-missing {:b {:x {:data 1}}} [{:b [:x :y]}]) =fn=> (has-leaves [[:b :x]])
        "data structures are properly marked in to-many results"
        (impl/mark-missing {:b [{:x {:data 1}} {:x {:data 2}}]} [{:b [:x]}]) =fn=> (has-leaves [[:b 0 :x] [:b 1 :x]])
        (impl/mark-missing {:b []} [:a {:b [:x]}]) =fn=> (has-leaves [[:b]])
        "unions are followed"
        (impl/mark-missing {:a [{:x {:data 1}} {:y {:data 2}}]} [{:a {:b [:x] :c [:y]}}]) =fn=> (has-leaves [[:a 0 :x] [:a 1 :y]])
        "unions leaves data in place when the result is empty"
        (impl/mark-missing {:a 1} [:a {:z {:b [:x] :c [:y]}}]) =fn=> (has-leaves [[:a]])))))
