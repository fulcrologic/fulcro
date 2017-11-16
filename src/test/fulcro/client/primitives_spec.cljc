(ns fulcro.client.primitives-spec
  (:require [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
            [fulcro.client.primitives :as prim :refer [defui]]
            [fulcro.history :as hist]
            [fulcro.client.dom :as dom]
            [clojure.spec.alpha :as s]
            [clojure.core.async :as async]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as check]
            [clojure.test.check.properties :as prop]
            [clojure.test.check :as tc]
            [clojure.spec.test.alpha :as check]
            [clojure.test :refer [is are]]
    #?@(:cljs [[goog.object :as gobj]])
            [fulcro.client.impl.protocols :as p]
            [fulcro.util :as util]))

(defui A)

(specification "Query IDs"
  (assertions
    "Start with the fully-qualifier class name"
    (prim/query-id A nil) => "fulcro$client$primitives_spec$A"
    "Include the optional qualifier"
    (prim/query-id A :x) => "fulcro$client$primitives_spec$A$:x"))

(specification "UI Factory"
  (assertions
    "Adds  react-class to the metadata of the generated factory"
    (some-> (prim/factory A) meta :class) => A
    "Adds an optional qualifier to the metadata of the generated factory"
    (some-> (prim/factory A) meta :qualifier) => nil
    (some-> (prim/factory A {:qualifier :x}) meta :qualifier) => :x)
  (behavior "Adds an internal query id to the props passed by the factory"
    #?(:cljs
       (when-mocking
         (prim/query-id c q) => :ID
         (prim/create-element class props children) => (do
                                                         (assertions
                                                           (gobj/get props "omcljs$queryid") => :ID))

         ((prim/factory A) {}))
       :clj
       (let [class (fn [_ _ props _] (assertions (:omcljs$queryid props) => :ID))]
         (when-mocking
           (prim/query-id c q) => :ID
           (prim/init-local-state c) => nil

           ((prim/factory class) {}))))))

(defui Q
  static prim/IDynamicQuery
  (dynamic-query [this state] [:a :b]))

(def ui-q (prim/factory Q))

(defui UnionChildA
  static prim/Ident
  (ident [this props] [:union-child/by-id (:L props)])
  static prim/IDynamicQuery
  (dynamic-query [this state] [:L]))

(def ui-a (prim/factory UnionChildA))

(defui UnionChildB
  static prim/IDynamicQuery
  (dynamic-query [this state] [:M]))

(def ui-b (prim/factory UnionChildB))

(defui Union
  static prim/IDynamicQuery
  (dynamic-query [this state] {:u1 (prim/get-query ui-a state)
                               :u2 (prim/get-query ui-b state)}))

(def ui-union (prim/factory Union))

(defui Child
  static prim/IDynamicQuery
  (dynamic-query [this state] [:x]))

(def ui-child (prim/factory Child))

(defui Root
  static prim/IDynamicQuery
  (dynamic-query [this state] [:a
                               {:join (prim/get-query ui-child state)}
                               {:union (prim/get-query ui-union state)}]))

(def ui-root (prim/factory Root))

(defui UnionChildAP
  static prim/IDynamicQuery
  (dynamic-query [this state] '[(:L {:child-params 1})]))

(def ui-ap (prim/factory UnionChildAP))

(defui UnionP
  static prim/IDynamicQuery
  (dynamic-query [this state] {:u1 (prim/get-query ui-ap state)
                               :u2 (prim/get-query ui-b state)}))

(def ui-unionp (prim/factory UnionP))

(defui RootP
  static prim/IDynamicQuery
  (dynamic-query [this state] `[:a
                                ({:join ~(prim/get-query ui-child state)} {:join-params 2})
                                ({:union ~(prim/get-query ui-unionp state)} {:union-params 3})]))

(def ui-rootp (prim/factory RootP))

(specification "link-query"
  (assertions
    "Replaces nested queries with their string ID"
    (prim/link-query (prim/get-query ui-root {})) => [:a {:join (prim/query-id Child nil)} {:union (prim/query-id Union nil)}]))

(specification "normalize-query"
  (let [union-child-a-id (prim/query-id UnionChildA nil)
        union-child-b-id (prim/query-id UnionChildB nil)
        child-id         (prim/query-id Child nil)
        root-id          (prim/query-id Root nil)
        union-id         (prim/query-id Union nil)
        existing-query   {:id    union-child-a-id
                          :query [:OTHER]}]
    (assertions
      "Adds simple single-level queries into app state under a reserved key"
      (prim/normalize-query {} (prim/get-query ui-a {})) => {::prim/queries {union-child-a-id
                                                                             {:id    union-child-a-id
                                                                              :query [:L]}}}
      "Single-level queries are not added if a query is already set in state"
      (prim/normalize-query {::prim/queries {union-child-a-id existing-query}} (prim/get-query ui-a {})) => {::prim/queries {union-child-a-id existing-query}}
      "More complicated queries normalize correctly"
      (prim/normalize-query {} (prim/get-query ui-root {}))
      => {::prim/queries {root-id          {:id    root-id
                                            :query [:a {:join child-id} {:union union-id}]}
                          union-id         {:id    union-id
                                            :query {:u1 union-child-a-id :u2 union-child-b-id}}
                          union-child-b-id {:id    union-child-b-id
                                            :query [:M]}
                          union-child-a-id {:id    union-child-a-id
                                            :query [:L]}
                          child-id         {:query [:x]
                                            :id    child-id}}})))

(specification "get-query*"
  (assertions
    "Obtains the static query from a given class"
    (prim/get-query Q) => [:a :b]
    "Obtains the static query when the state has no stored queries"
    (prim/get-query ui-q {}) => [:a :b])
  (let [query        (prim/get-query ui-root {})
        top-level    query
        join-target  (get-in query [1 :join])
        union-target (get-in query [2 :union])
        union-left   (get union-target :u1)
        union-right  (get union-target :u2)]
    (assertions
      "Places a query ID on the metadata of each component's portion of the query"
      (-> top-level meta :queryid) => "fulcro$client$primitives_spec$Root"
      (-> join-target meta :queryid) => "fulcro$client$primitives_spec$Child"
      (-> union-target meta :queryid) => "fulcro$client$primitives_spec$Union"
      (-> union-left meta :queryid) => "fulcro$client$primitives_spec$UnionChildA"
      (-> union-right meta :queryid) => "fulcro$client$primitives_spec$UnionChildB"))
  (let [app-state (prim/normalize-query {} (prim/get-query ui-root {}))
        app-state (assoc-in app-state [::prim/queries (prim/query-id UnionChildA nil) :query] [:UPDATED])]
    (behavior "Pulls a denormalized query from app state if one exists."
      (assertions
        (prim/get-query ui-root app-state) => [:a {:join [:x]} {:union {:u1 [:UPDATED] :u2 [:M]}}]))
    (behavior "Allows a class instead of a factory"
      (assertions
        "with state"
        (prim/get-query Root app-state) => [:a {:join [:x]} {:union {:u1 [:UPDATED] :u2 [:M]}}]
        "without state (raw static query)"
        (prim/get-query Root) => [:a {:join [:x]} {:union {:u1 [:L] :u2 [:M]}}]))))

(specification "Normalization preserves query"
  (let [query               (prim/get-query ui-root {})
        parameterized-query (prim/get-query ui-rootp {})
        state               (prim/normalize-query {} query)
        state-parameterized (prim/normalize-query {} parameterized-query)]
    (assertions
      "When parameters are not present"
      (prim/get-query ui-root state) => query
      "When parameters are present"
      (prim/get-query ui-rootp state) => parameterized-query)))

; TODO: This would be a great property-based check  (marshalling/unmarshalling) if we had generators that would work...

(specification "Setting a query"
  (let [query                       (prim/get-query ui-root {})
        parameterized-query         (prim/get-query ui-rootp {})
        state                       (prim/normalize-query {} query)
        state-modified              (prim/set-query* state ui-b {:query [:MODIFIED]})
        expected-query              (assoc-in query [2 :union :u2 0] :MODIFIED)
        state-modified-root         (prim/set-query* state Root {:query [:b
                                                                         {:join (prim/get-query ui-child state)}
                                                                         {:union (prim/get-query ui-union state)}]})
        queryid                     (prim/query-id Root nil)
        state-queryid-modified-root (prim/set-query* state queryid {:query [:b
                                                                            {:join (prim/get-query ui-child state)}
                                                                            {:union (prim/get-query ui-union state)}]})
        expected-root-query         (assoc query 0 :b)
        state-parameterized         (prim/normalize-query {} parameterized-query)]
    (assertions
      "Can update a node by factory"
      (prim/get-query ui-root state-modified) => expected-query
      "Can update a node by class"
      (prim/get-query ui-root state-modified-root) => expected-root-query
      "Can be done directly by ID"
      (prim/get-query ui-root state-queryid-modified-root) => expected-root-query)))

(specification "Indexing"
  (component "Gathering keys for a query"
    (assertions
      "finds the correct prop keys (without parameters)"
      (prim/gather-keys (prim/get-query ui-root {})) => #{:a :join :union}
      (prim/gather-keys (prim/get-query ui-union {})) => #{:u1 :u2}
      (prim/gather-keys (prim/get-query ui-a {})) => #{:L}
      (prim/gather-keys (prim/get-query ui-b {})) => #{:M}
      (prim/gather-keys (prim/get-query ui-child {})) => #{:x}
      "finds the correct prop keys (with parameters)"
      (prim/gather-keys (prim/get-query ui-rootp {})) => #{:a :join :union}
      (prim/gather-keys (prim/get-query ui-ap {})) => #{:L}
      (prim/gather-keys (prim/get-query ui-unionp {})) => #{:u1 :u2}))
  (component "Indexer"
    (let [indexer (prim/map->Indexer {:indexes (atom {})})
          indexer (assoc indexer :state {})]

      (p/index-root indexer Root)

      (assertions
        "Properly indexes components"
        (-> indexer :indexes deref :prop->classes :L) => #{UnionChildA}
        (-> indexer :indexes deref :prop->classes :M) => #{UnionChildB}
        (-> indexer :indexes deref :prop->classes :a) => #{Root}
        (-> indexer :indexes deref :prop->classes :join) => #{Root}
        (-> indexer :indexes deref :prop->classes :union) => #{Root}))
    (let [indexer (prim/map->Indexer {:indexes (atom {})})
          indexer (assoc indexer :state {})]

      (p/index-root indexer RootP)

      (assertions
        "Properly indexes components that have parameterized queries"
        (-> indexer :indexes deref :prop->classes :L) => #{UnionChildAP}
        (-> indexer :indexes deref :prop->classes :M) => #{UnionChildB}
        (-> indexer :indexes deref :prop->classes :a) => #{RootP}
        (-> indexer :indexes deref :prop->classes :join) => #{RootP}
        (-> indexer :indexes deref :prop->classes :union) => #{RootP}))
    (let [indexer   (prim/map->Indexer {:indexes (atom {})})
          id        [:union-child/by-id 1]
          id-2      [:union-child/by-id 2]
          element   (ui-a {:L 1})
          element-2 (ui-a {:L 2})]

      (p/index-component! indexer element)
      (p/index-component! indexer element-2)

      (let [ident-elements    (-> indexer :indexes deref :ref->components (get id))
            class-elements    (-> indexer :indexes deref :class->components (get UnionChildA))
            expected-ident    #{element}
            expected-by-class #{element element-2}]
        (assertions
          ;"Adds component idents to the ref->components index when indexing the component"
          ;; FIXME: Not sure I can...Shows up as broken on cljs, but this may be due to the fact that the component isn't mounted
          ;(count ident-elements) => 1 ; js objects that cannot be printed...crash spec
          "Adds the component to the index of :class->components"
          (count class-elements) => 2)))))

(specification "gather-sends"
  (behavior "Runs the parser against the given remotes and:"
    (let [q           [:some-query]
          saw-remotes (atom [])
          parser      (fn [env query remote]
                        (swap! saw-remotes conj remote)
                        (assertions
                          "passes the environment to the parser"
                          (contains? env :parser) => true
                          "passes the query to the parser"
                          query => q)
                        (get {:a '[(do-a-thing)] :b '[(do-b-thing)]} remote))
          env         {:parser parser}
          result      (prim/gather-sends env q [:a :b] 1000)]

      (assertions
        "Returns a map with keys for each remote's actions"
        result => {:a '[(do-a-thing)] :b '[(do-b-thing)]}
        "Includes the history timestamp on each entry"
        (-> result :a meta ::hist/tx-time) => 1000
        (-> result :b meta ::hist/tx-time) => 1000))))

(specification "gather-keys"
  (assertions
    "Can gather the correct keys from simple props"
    (prim/gather-keys [:a :b :c]) => #{:a :b :c}
    "Gather keys for joins, but does not recurse."
    (prim/gather-keys [:a {:b [:d]} :c]) => #{:a :b :c}
    "Allow parameterized props"
    (prim/gather-keys '[(:a {:x 1}) {:b [:d]} :c]) => #{:a :b :c}
    "Includes keywords from link queries"
    (prim/gather-keys [:a [:x '_]]) => #{:a :x}
    (prim/gather-keys [:a {[:x '_] [:prop]}]) => #{:a :x}))

(specification "building prop->classes index"
  (let [index-atom (atom {})]
    (prim/build-prop->class-index! index-atom (prim/get-query Root))
    (assertions
      "Includes entries the correct keys"
      (set (keys @index-atom)) => #{:a :join :union :x :u1 :u2 :L :M}
      "The component class count is correct"
      (count (get @index-atom :a)) => 1
      (count (get @index-atom :join)) => 1
      (count (get @index-atom :u1)) => 1
      (count (get @index-atom :u2)) => 1
      (count (get @index-atom :L)) => 1
      (count (get @index-atom :M)) => 1
      (count (get @index-atom :union)) => 1

      )))

(specification "remove-loads-and-fallbacks"
  (behavior "Removes top-level mutations that use the fulcro/load or tx/fallback symbols"
    (are [q q2] (= (prim/remove-loads-and-fallbacks q) q2)
      '[:a {:j [:a]} (f) (fulcro/load {:x 1}) (app/l) (tx/fallback {:a 3})] '[:a {:j [:a]} (f) (app/l)]
      '[(fulcro/load {:x 1}) (app/l) (tx/fallback {:a 3})] '[(app/l)]
      '[(fulcro/load {:x 1}) (tx/fallback {:a 3})] '[]
      '[(boo {:x 1}) (fulcro.client.data-fetch/fallback {:a 3})] '[(boo {:x 1})]
      '[:a {:j [:a]}] '[:a {:j [:a]}])))

(specification "fallback-query"
  (behavior "extracts the fallback expressions of a query, adds execute flags, and includes errors in params"
    (are [q q2] (= (prim/fallback-query q {:error 42}) q2)
      '[:a :b] nil

      '[:a {:j [:a]} (f) (fulcro/load {:x 1}) (app/l) (tx/fallback {:a 3})]
      '[(tx/fallback {:a 3 :execute true :error {:error 42}})]

      '[:a {:j [:a]} (tx/fallback {:b 4}) (f) (fulcro/load {:x 1}) (app/l) (fulcro.client.data-fetch/fallback {:a 3})]
      '[(tx/fallback {:b 4 :execute true :error {:error 42}}) (fulcro.client.data-fetch/fallback {:a 3 :execute true :error {:error 42}})])))

(specification "tempid handling"
  (behavior "rewrites all tempids used in pending requests in the request queue"
    (let [queue           (async/chan 10000)
          tid1            (prim/tempid)
          tid2            (prim/tempid)
          tid3            (prim/tempid)
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

      (prim/rewrite-tempids-in-request-queue queue tid->rid)

      (swap! results conj (async/poll! queue))
      (swap! results conj (async/poll! queue))
      (swap! results conj (async/poll! queue))

      (is (nil? (async/poll! queue)))
      (is (= expected-result @results))))

  (let [tid            (prim/tempid)
        tid2           (prim/tempid)
        rid            1
        state          {:thing  {tid  {:id tid}
                                 tid2 {:id tid2}}           ; this one isn't in the remap, and should not be touched
                        :things [[:thing tid]]}
        expected-state {:thing  {rid  {:id rid}
                                 tid2 {:id tid2}}
                        :things [[:thing rid]]}
        reconciler     (prim/reconciler {:state state :parser {:read (constantly nil)} :migrate prim/resolve-tempids})]

    (assertions
      "rewrites all tempids in the app state (leaving unmapped ones alone)"
      ((-> reconciler :config :migrate) @reconciler {tid rid}) => expected-state)))

(specification "strip-ui"
  (let [q1     [:username :password :ui/login-dropdown-showing {:forgot-password [:email :ui/forgot-button-showing]}]
        q2     [:username :password :ui.login/dropdown-showing {:forgot-password [:email :ui.forgot/button-showing]}]
        result [:username :password {:forgot-password [:email]}]]

    (assertions
      "removes keywords with a ui namespace"
      (prim/strip-ui q1) => result
      "removes keywords with a ui.{something} namespace"
      (prim/strip-ui q2) => result))

  (let [query '[(app/x {:ui/boo 23})]]
    (assertions
      "does not remove ui prefixed data from parameters"
      (prim/strip-ui query) => query)))

(specification "mark-missing"
  (behavior "correctly marks missing properties"
    (are [query ?missing-result exp]
      (= exp (prim/mark-missing ?missing-result query))
      [:a :b]
      {:a 1}
      {:a 1 :b prim/nf}))

  (behavior "joins -> one"
    (are [query ?missing-result exp]
      (= exp (prim/mark-missing ?missing-result query))
      [:a {:b [:c]}]
      {:a 1}
      {:a 1 :b prim/nf}

      [{:b [:c]}]
      {:b {}}
      {:b {:c prim/nf}}

      [{:b [:c]}]
      {:b {:c 0}}
      {:b {:c 0}}

      [{:b [:c :d]}]
      {:b {:c 1}}
      {:b {:c 1 :d prim/nf}}))

  (behavior "join -> many"
    (are [query ?missing-result exp]
      (= exp (prim/mark-missing ?missing-result query))

      [{:a [:b :c]}]
      {:a [{:b 1 :c 2} {:b 1}]}
      {:a [{:b 1 :c 2} {:b 1 :c prim/nf}]}))

  (behavior "idents and ident joins"
    (are [query ?missing-result exp]
      (= exp (prim/mark-missing ?missing-result query))
      [{[:a 1] [:x]}]
      {[:a 1] {}}
      {[:a 1] {:x prim/nf}}

      [{[:b 1] [:x]}]
      {[:b 1] {:x 2}}
      {[:b 1] {:x 2}}

      [{[:c 1] [:x]}]
      {}
      {[:c 1] {:ui/fetch-state {:fulcro.client.impl.data-fetch/type :not-found}
               :x              prim/nf}}

      [{[:e 1] [:x :y :z]}]
      {}
      {[:e 1] {:ui/fetch-state {:fulcro.client.impl.data-fetch/type :not-found}
               :x              prim/nf
               :y              prim/nf
               :z              prim/nf}}

      [[:d 1]]
      {}
      {[:d 1] {:ui/fetch-state {:fulcro.client.impl.data-fetch/type :not-found}}}))

  (behavior "parameterized"
    (are [query ?missing-result exp]
      (= exp (prim/mark-missing ?missing-result query))
      '[:z (:y {})]
      {:z 1}
      {:z 1 :y prim/nf}

      '[:z (:y {})]
      {:z 1 :y 0}
      {:z 1 :y 0}

      '[:z ({:y [:x]} {})]
      {:z 1 :y {}}
      {:z 1 :y {:x prim/nf}}))

  (behavior "nested"
    (are [query ?missing-result exp]
      (= exp (prim/mark-missing ?missing-result query))
      [{:b [:c {:d [:e]}]}]
      {:b {:c 1}}
      {:b {:c 1 :d prim/nf}}

      [{:b [:c {:d [:e]}]}]
      {:b {:c 1 :d {}}}
      {:b {:c 1 :d {:e prim/nf}}}))

  (behavior "upgrades value to maps if necessary"
    (are [query ?missing-result exp]
      (= exp (prim/mark-missing ?missing-result query))
      [{:l [:m]}]
      {:l 0}
      {:l {:m prim/nf}}

      [{:b [:c]}]
      {:b nil}
      {:b {:c prim/nf}}))

  (behavior "unions"
    (assertions
      "singletons"
      (prim/mark-missing {:j {:c {}}} [{:j {:a [:c] :b [:d]}}]) => {:j {:c {} :d prim/nf}}

      "singleton with no result"
      (prim/mark-missing {} [{:j {:a [:c] :b [:d]}}]) => {:j prim/nf}

      "list to-many with 1"
      (prim/mark-missing {:j [{:c "c"}]} [{:j {:a [:c] :b [:d]}}]) => {:j [{:c "c" :d prim/nf}]}

      "list to-many with 2"
      (prim/mark-missing {:items [{:id 0 :image "img1"} {:id 1 :text "text1"}]} [{:items {:photo [:id :image] :text [:id :text]}}]) => {:items [{:id 0 :image "img1" :text prim/nf} {:id 1 :image prim/nf :text "text1"}]}

      "list to-many with no results"
      (prim/mark-missing {:j []} [{:j {:a [:c] :b [:d]}}]) => {:j []}))

  (behavior "if the query has a ui.*/ attribute, it should not be marked as missing"
    (are [query ?missing-result exp]
      (= exp (prim/mark-missing ?missing-result query))

      [:a :ui/b :c]
      {:a {}
       :c {}}
      {:a {}
       :c {}}

      [{:j [:ui/b :c]}]
      {:j {:c 5}}
      {:j {:c 5}}

      [{:j [{:ui/b [:d]} :c]}]
      {:j {:c 5}}
      {:j {:c 5}}))

  (behavior "mutations!"
    (are [query ?missing-result exp]
      (= exp (prim/mark-missing ?missing-result query))

      '[(f) {:j [:a]}]
      {'f {}
       :j {}}
      {'f {}
       :j {:a prim/nf}}

      '[(app/add-q {:p 1}) {:j1 [:p1]} {:j2 [:p2]}]
      {'app/add-q {:tempids {}}
       :j1        {}
       :j2        [{:p2 2} {}]}
      {'app/add-q {:tempids {}}
       :j1        {:p1 prim/nf}
       :j2        [{:p2 2} {:p2 prim/nf}]}))

  (behavior "correctly walks recursive queries to mark missing data"
    (behavior "when the recursive target is a singleton"
      (are [query ?missing-result exp]
        (= exp (prim/mark-missing ?missing-result query))
        [:a {:b '...}]
        {:a 1 :b {:a 2}}
        {:a 1 :b {:a 2 :b prim/nf}}

        [:a {:b '...}]
        {:a 1 :b {:a 2 :b {:a 3}}}
        {:a 1 :b {:a 2 :b {:a 3 :b prim/nf}}}

        [:a {:b 9}]
        {:a 1 :b {:a 2 :b {:a 3 :b {:a 4}}}}
        {:a 1 :b {:a 2 :b {:a 3 :b {:a 4 :b prim/nf}}}}))
    (behavior "when the recursive target is to-many"
      (are [query ?missing-result exp]
        (= exp (prim/mark-missing ?missing-result query))
        [:a {:b '...}]
        {:a 1 :b [{:a 2 :b [{:a 3}]}
                  {:a 4}]}
        {:a 1 :b [{:a 2 :b [{:a 3 :b prim/nf}]}
                  {:a 4 :b prim/nf}]})))
  (behavior "marks leaf data based on the query where"
    (letfn [(has-leaves [leaf-paths] (fn [result] (every? #(prim/leaf? (get-in result %)) leaf-paths)))]
      (assertions
        "plain data is always a leaf"
        (prim/mark-missing {:a 1 :b {:x 5}} [:a {:b [:x]}]) =fn=> (has-leaves [[:b :x] [:a] [:missing]])
        "data structures are properly marked in singleton results"
        (prim/mark-missing {:b {:x {:data 1}}} [{:b [:x :y]}]) =fn=> (has-leaves [[:b :x]])
        "data structures are properly marked in to-many results"
        (prim/mark-missing {:b [{:x {:data 1}} {:x {:data 2}}]} [{:b [:x]}]) =fn=> (has-leaves [[:b 0 :x] [:b 1 :x]])
        (prim/mark-missing {:b []} [:a {:b [:x]}]) =fn=> (has-leaves [[:b]])
        "unions are followed"
        (prim/mark-missing {:a [{:x {:data 1}} {:y {:data 2}}]} [{:a {:b [:x] :c [:y]}}]) =fn=> (has-leaves [[:a 0 :x] [:a 1 :y]])
        "unions leaves data in place when the result is empty"
        (prim/mark-missing {:a 1} [:a {:z {:b [:x] :c [:y]}}]) =fn=> (has-leaves [[:a]])))))

(specification "Sweep one"
  (assertions
    "removes not-found values from maps"
    (prim/sweep-one {:a 1 :b ::prim/not-found}) => {:a 1}
    "is not recursive"
    (prim/sweep-one {:a 1 :b {:c ::prim/not-found}}) => {:a 1 :b {:c ::prim/not-found}}
    "maps over vectors not recursive"
    (prim/sweep-one [{:a 1 :b ::prim/not-found}]) => [{:a 1}]
    "retains metadata"
    (-> (prim/sweep-one (with-meta {:a 1 :b ::prim/not-found} {:meta :data}))
      meta) => {:meta :data}
    (-> (prim/sweep-one [(with-meta {:a 1 :b ::prim/not-found} {:meta :data})])
      first meta) => {:meta :data}
    (-> (prim/sweep-one (with-meta [{:a 1 :b ::prim/not-found}] {:meta :data}))
      meta) => {:meta :data}))

(specification "Sweep merge"
  (assertions
    "recursively merges maps"
    (prim/sweep-merge {:a 1 :c {:b 2}} {:a 2 :c 5}) => {:a 2 :c 5}
    (prim/sweep-merge {:a 1 :c {:b 2}} {:a 2 :c {:x 1}}) => {:a 2 :c {:b 2 :x 1}}
    "stops recursive merging if the source element is marked as a leaf"
    (prim/sweep-merge {:a 1 :c {:d {:x 2} :e 4}} {:a 2 :c (prim/as-leaf {:d {:x 1}})}) => {:a 2 :c {:d {:x 1}}}
    "sweeps values that are marked as not found"
    (prim/sweep-merge {:a 1 :c {:b 2}} {:a 2 :c {:b ::prim/not-found}}) => {:a 2 :c {}}
    (prim/sweep-merge {:a 1 :c 2} {:a 2 :c {:b ::prim/not-found}}) => {:a 2 :c {}}
    (prim/sweep-merge {:a 1 :c 2} {:a 2 :c [{:x 1 :b ::prim/not-found}]}) => {:a 2 :c [{:x 1}]}
    (prim/sweep-merge {:a 1 :c {:data-fetch :loading}} {:a 2 :c [{:x 1 :b ::prim/not-found}]}) => {:a 2 :c [{:x 1}]}
    (prim/sweep-merge {:a 1 :c nil} {:a 2 :c [{:x 1 :b ::prim/not-found}]}) => {:a 2 :c [{:x 1}]}
    (prim/sweep-merge {:a 1 :b {:c {:ui/fetch-state {:post-mutation 's}}}} {:a 2 :b {:c [{:x 1 :b ::prim/not-found}]}}) => {:a 2 :b {:c [{:x 1}]}}
    "clears normalized table entries that has an id of not found"
    (prim/sweep-merge {:table {1 {:a 2}}} {:table {::prim/not-found {:db/id ::prim/not-found}}}) => {:table {1 {:a 2}}}
    "clears idents whose ids were not found"
    (prim/sweep-merge {} {:table {1 {:db/id 1 :the-thing [:table-1 ::prim/not-found]}}
                          :thing [:table-2 ::prim/not-found]}) => {:table {1 {:db/id 1}}}
    "sweeps not-found values from normalized table merges"
    (prim/sweep-merge {:subpanel  [:dashboard :panel]
                       :dashboard {:panel {:view-mode :detail :surveys {:ui/fetch-state {:post-mutation 's}}}}
                       }
      {:subpanel  [:dashboard :panel]
       :dashboard {:panel {:view-mode :detail :surveys [[:s 1] [:s 2]]}}
       :s         {
                   1 {:db/id 1, :survey/launch-date ::prim/not-found}
                   2 {:db/id 2, :survey/launch-date "2012-12-22"}
                   }}) => {:subpanel  [:dashboard :panel]
                           :dashboard {:panel {:view-mode :detail :surveys [[:s 1] [:s 2]]}}
                           :s         {
                                       1 {:db/id 1}
                                       2 {:db/id 2 :survey/launch-date "2012-12-22"}
                                       }}
    "overwrites target (non-map) value if incoming value is a map"
    (prim/sweep-merge {:a 1 :c 2} {:a 2 :c {:b 1}}) => {:a 2 :c {:b 1}}))

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
      (prim/sweep-merge t s) => (do
                                  (assertions
                                    "Passes source, cleaned of symbols, to sweep-merge"
                                    s => {:v 1})
                                  swept-state)

      (let [actual (prim/merge-handler rh {} response)]
        (assertions
          "Returns the swept state reduced over the return handlers"
          ;; Function under test:
          actual => swept-state
          (meta actual) => mutation-response-without-tempids)))))

(defsc Item [this {:keys [db/id item/value]} _ _]
  {:query [:db/id :item/value]
   :ident [:item/by-id :db/id]}
  (dom/li nil value))

(defsc ItemList [this {:keys [db/id list/title list/items] :as props} _ _]
  {:query [:db/id :list/title {:list/items (prim/get-query Item)}]
   :ident [:list/by-id :db/id]}
  (dom/div nil (dom/h3 nil title)))


(specification "Mutation joins" :focused
  (let [q            [{'(f {:p 1}) (prim/get-query ItemList)}]
        d            {'f {:db/id 1 :list/title "A" :list/items [{:db/id 1 :item/value "1"}]}}
        result       (prim/merge-mutation-joins {:top-key 1} q d)

        existing-db  {:item/by-id {1 {:db/id 1 :item/value "1"}}}
        missing-data {'f {:db/id 1 :list/title "A" :list/items [{:db/id 1}]}}
        result-2     (prim/merge-mutation-joins existing-db q missing-data)]
    (assertions
      "mutation responses are merged"
      result => {:top-key    1
                 :list/by-id {1 {:db/id 1 :list/title "A" :list/items [[:item/by-id 1]]}}
                 :item/by-id {1 {:db/id 1 :item/value "1"}}}
      "mutation responses do proper sweep merge"
      result-2 => {:list/by-id {1 {:db/id 1 :list/title "A" :list/items [[:item/by-id 1]]}}
                   :item/by-id {1 {:db/id 1}}}))
  (let [mj {'(f {:p 1}) [:a]}]
    (assertions
      "Detects mutation joins as joins"
      (util/join? mj) => true
      "give the correct join key for mutation joins"
      (util/join-key mj) => 'f
      "give the correct join value for mutation joins"
      (util/join-value mj) => [:a])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Om Next query spec, with generators for property-based tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(specification "Static Queries"
  (behavior "Maintain their backward-compatible functionality" :manual-test))

(comment
  (defn anything? [x] true)
  (def om-keyword? (s/with-gen
                     keyword?
                     #(s/gen #{:a :b :c :d :e :f :g :h})))
  (def anything-gen #(s/gen #{"hello" 45 99.99 'abc -1 0 1 -1.0 0.0 1.0}))

  (s/def ::ident-expr (s/tuple om-keyword? (s/with-gen anything? anything-gen)))

  (def recur-gen #(s/gen #{'... 4 9 11}))
  (def om-ident? (s/with-gen
                   ::ident-expr
                   #(s/gen #{[:x 1] [:y "hello"] [:boo/other 22] [:poo nil]})))
  (def keyword-or-ident?
    (s/with-gen
      (s/or :keyword om-keyword? :ident om-ident?)
      #(s/gen #{[:x 1] [:poo nil] [:b 44] :a :b :c :d})))

  (s/def ::union-expr (s/with-gen (s/map-of om-keyword? ::query-root :gen-max 1 :count 1)
                        #(gen/fmap (fn [v] (with-meta v {:queryid (futil/unique-key)})) (s/gen (s/map-of om-keyword? ::query-root :gen-max 1 :count 1)))))
  (s/def ::recur-expr (s/with-gen
                        (s/or :infinite #{'...} :to-depth (s/and number? pos? integer?))
                        recur-gen))
  (s/def ::join-expr (s/map-of keyword-or-ident?
                       (s/or :root ::query-root :union ::union-expr :recur ::recur-expr)
                       :gen-max 1 :count 1))
  (s/def ::param-map-expr (s/map-of om-keyword? (s/with-gen anything? anything-gen) :gen-max 1 :count 1))
  (s/def ::param-expr (s/cat :first ::plain-query-expr :second ::param-map-expr))
  (s/def ::plain-query-expr (s/or :keyword om-keyword? :ident om-ident? :join ::join-expr))
  (s/def ::query-expr (s/or :query ::plain-query-expr :parameterized ::param-expr))
  (s/def ::query-root (s/with-gen (s/and vector?
                                    (s/every ::query-expr :min-count 1))
                        #(gen/fmap (fn [v] (with-meta v {:queryid (futil/unique-key)})) (gen/vector (s/gen ::query-expr)))))

  (prim/defui A)
  (def f (factory A))

  ; This property isn't holding, but I don't know if it is the spec generators not adding metadata correctly, or what
  (def prop-marshall
    (prop/for-all [v (s/gen ::query-root)]
      (try
        (= v (get-query* (normalize-query {} (gen/generate (s/gen ::query-root))) v))
        (catch #?(:cljs :default :clj StackOverflowError) e
          true)))))

(comment
  (tc/quick-check 10 prop-marshall)
  (check/check (normalize-query {} (gen/generate (s/gen ::query-root))))
  (let [expr (gen/generate (s/gen ::union-expr))]
    (and (-> expr meta :queryid)
      (every? (fn [[k v]]
                (-> v meta :queryid)
                ) expr)))

  (gen/sample (s/gen ::query-root))
  (gen/generate (s/gen ::join-expr))
  (s/valid? ::query-root '[(:a {:x 1})])
  (gen/sample (s/gen (s/cat :k om-keyword? :v (s/or :root ::query-root :union ::union-expr :recur ::recur-expr))))
  (gen/sample (s/gen (s/or :n number? :s string?)))

  (s/valid? ::param-expr '({:x [:a]} {:f 1})))


