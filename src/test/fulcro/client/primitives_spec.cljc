(ns fulcro.client.primitives-spec
  (:require [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
            [fulcro.client.primitives :as prim :refer [defui defsc]]
            [fulcro.history :as hist]
            [fulcro.client.dom :as dom]
            [fulcro-css.css]
            [clojure.spec.alpha :as s]
            [clojure.core.async :as async]
            fulcro.client
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as check]
            [clojure.test.check.properties :as prop]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.data-fetch :as df]
            [clojure.test.check :as tc]
            [clojure.spec.test.alpha :as check]
            [clojure.test :refer [is are]]
    #?@(:cljs [[goog.object :as gobj]])
            [fulcro.client.impl.protocols :as p]
            [fulcro.util :as util]
            [clojure.walk :as walk])
  #?(:clj
     (:import (clojure.lang ExceptionInfo))))

(defn read-times [m]
  (walk/postwalk
    (fn [x]
      (if-let [time (some-> x meta ::prim/time)]
        (-> (into {} (filter (fn [[_ v]] (or (vector? v)
                                           (and (map? v) (contains? v ::time))))) x)
          (assoc ::time time))
        x))
    m))

(specification "Add basis time"
  (assertions
    "Add time information recursively"
    (-> (prim/add-basis-time {:hello {:world {:data 2}
                                      :foo   :bar
                                      :multi [{:x 1} {:x 2}]}} 10)
        (read-times))
      => {:hello {:world {::time 10}
                  :multi [{::time 10} {::time 10}]
                  ::time 10}
          ::time 10}

      "Limit the reach when query is provided"
      (-> (prim/add-basis-time [{:hello [{:world [:item]}
                                         {:multi [:x]}]}]
            {:hello {:world {:data 2
                             :item [{:nested {:deep "bar"}}]}
                     :so    {:long {:and "more"}}
                     :foo   :bar
                     :multi [{:x 1} {:x 2}]}} 10)
        (read-times))
      => {:hello {:world {:item  [{::time 10}]
                          ::time 10}
                  :multi [{::time 10} {::time 10}]
                  ::time 10}
          ::time 10}

      "Out of query data is kept as is"
      (prim/add-basis-time [{:hello [{:world [:item]}
                                   :query-only
                                     {:multi [:x]}]}]
        {:hello {:world {:data 2
                         :item [{:nested {:deep "bar"}}]}
                 :so    {:long {:and "more"}}
                 :foo   :bar
                 :multi [{:x 1} {:x 2}]}} 10)
      => {:hello {:world {:data 2
                          :item [{:nested {:deep "bar"}}]}
                  :so    {:long {:and "more"}}
                  :foo   :bar
                  :multi [{:x 1} {:x 2}]}}

      "Handle unions"
      (-> (prim/add-basis-time [{:hello [{:union {:ua [:x]
                                                  :ub [:y]}}]}]
            {:hello {:union {:x    {:content "bar"}
                             :type :ua}}} 10)
        (read-times))
      => {:hello {:union {:x     {::time 10}
                          ::time 10}
                  ::time 10}
          ::time 10}

      "Handle recursive queries"
      (-> (prim/add-basis-time [{:hello [{:parent '...}
                                         :name]}]
            {:hello {:name   "bla"
                     :parent {:name   "meh"
                              :parent {:name "other"}}}} 10)
        (read-times))
      => {:hello {:parent {:parent {::time 10}
                           ::time  10}
                  ::time  10}
          ::time 10}

      "Handle recursive queries with limit"
      (-> (prim/add-basis-time [{:hello [{:parent 1}
                                         :name]}]
            {:hello {:name   "bla"
                     :parent {:name   "meh"
                              :parent {:name   "other"
                                       :parent {:name "one more"}}}}} 10)
        (read-times))
      => {:hello {:parent {:parent {::time 10}
                           ::time  10}
                  ::time  10}
          ::time 10}

      "Meta data is preserved"
      (-> (prim/add-basis-time [{:hello [{:world [:item]}
                                         {:multi [:x]}]}]
            ^::info {:hello {:world {:data 2
                                     :item [{:nested {:deep "bar"}}]}
                             :so    {:long {:and "more"}}
                             :foo   :bar
                             :multi [{:x 1} {:x 2}]}} 10)
        (meta))
      => {::prim/time 10 ::info true}))

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
                                                           (gobj/get props "fulcro$queryid") => :ID))

         ((prim/factory A) {}))
       :clj
       (let [class (fn [_ _ props _] (assertions (:fulcro$queryid props) => :ID))]
         (when-mocking
           (prim/query-id c q) => :ID
           (prim/init-local-state c) => nil

           ((prim/factory class) {}))))))

(defui Q
  static prim/IQuery
  (query [this] [:a :b]))

(def ui-q (prim/factory Q))

(defui UnionChildA
  static prim/Ident
  (ident [this props] [:union-child/by-id (:L props)])
  static prim/IQuery
  (query [this] [:L]))

(def ui-a (prim/factory UnionChildA))

(defui UnionChildB
  static prim/IQuery
  (query [this] [:M]))

(def ui-b (prim/factory UnionChildB))

(defui Union
  static prim/IQuery
  (query [this] {:u1 (prim/get-query ui-a)
                 :u2 (prim/get-query ui-b)}))

(def ui-union (prim/factory Union))

(defui Child
  static prim/IQuery
  (query [this] [:x]))

(def ui-child (prim/factory Child))

(defui Root
  static prim/IQuery
  (query [this] [:a
                 {:join (prim/get-query ui-child)}
                 {:union (prim/get-query ui-union)}]))

(def ui-root (prim/factory Root))

(defui UnionChildAP
  static prim/IQuery
  (query [this] '[(:L {:child-params 1})]))

(def ui-ap (prim/factory UnionChildAP))

(defui UnionP
  static prim/IQuery
  (query [this] {:u1 (prim/get-query ui-ap)
                 :u2 (prim/get-query ui-b)}))

(def ui-unionp (prim/factory UnionP))

(defui RootP
  static prim/IQuery
  (query [this] `[:a
                  ({:join ~(prim/get-query ui-child)} {:join-params 2})
                  ({:union ~(prim/get-query ui-unionp)} {:union-params 3})]))

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
    "removes tempids from maps"
    (prim/sweep-one {::prim/tempids {1 2} :tempids {3 4}}) => {}
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
    "sweeps tempids from maps"
    (prim/sweep-merge {:a 1 :c {:b 2}} {:a 2 :tempids {} :c {::prim/tempids {} :b ::prim/not-found}}) => {:a 2 :c {}}
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

(specification "merge*"
  (when-mocking
    (prim/merge-novelty! r s res q) => {:next true}

    (let [result `{:data 33 f {:tempids {1 2}} g {::prim/tempids {3 4}}}
          {:keys [keys next ::prim/tempids]} (prim/merge* :reconciler (atom {}) result [])]

      (assertions
        "Finds all of the tempid remappings"
        tempids => {1 2 3 4}
        "gives back the next state for the app"
        next => {:next true}
        "Finds all of the data keys on the response"
        keys => [:data]))))

(specification "Merge handler"
  (let [swept-state                       {:state 1}
        data-response                     {:v 1}
        mutation-response                 {'f {:x 1 ::prim/tempids {1 2}} 'g {:tempids {3 4} :y 2}}
        mutation-response-without-tempids (-> mutation-response
                                            (update 'f dissoc ::prim/tempids)
                                            (update 'g dissoc :tempids))
        response                          (merge data-response mutation-response)
        rh                                (fn [state k v]
                                            (assertions
                                              "return handler is passed the swept state as a map"
                                              state => swept-state
                                              "tempids are stripped from return value before calling handler"
                                              (contains? v :tempids) => false
                                              (contains? v ::prim/tempids) => false)
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

(defsc Item [this {:keys [db/id item/value]}]
  {:query [:db/id :item/value]
   :ident [:item/by-id :db/id]}
  (dom/li nil value))

(defsc ItemList [this {:keys [db/id list/title list/items] :as props}]
  {:query [:db/id :list/title {:list/items (prim/get-query Item)}]
   :ident [:list/by-id :db/id]}
  (dom/div nil (dom/h3 nil title)))

(specification "Mutation joins"
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

(defmutation f [params]
  (action [env] true)
  (remote [env] true))
(defmutation g [params]
  (action [env] true)
  (rest-remote [env] true))
(defmutation h [params]
  (action [env] true))
(defmethod m/mutate `unhappy-mutation [env _ params]
  (throw (ex-info "Boo!" {})))

(specification "pessimistic-transaction->transaction"
  (assertions
    "Returns the transaction if it only contains a single call"
    (prim/pessimistic-transaction->transaction `[(f {:x 1})]) => `[(f {:x 1})]
    "Includes the follow-on reads in a single-call"
    (prim/pessimistic-transaction->transaction `[(f {:y 2}) :read]) => `[(f {:y 2}) :read]
    "Converts a sequence of calls to the proper nested structure, deferring against the correct remotes"
    (prim/pessimistic-transaction->transaction `[(f) (g) (h)]) => `[(f)
                                                                    (df/deferred-transaction {:remote :remote
                                                                                              :tx     [(g) (df/deferred-transaction
                                                                                                             {:remote :rest-remote
                                                                                                              :tx     [(h)]})]})]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; query spec, with generators for property-based tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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


(specification "Static Queries"
  (behavior "Maintain their backward-compatible functionality" :manual-test))

#?(:clj
   (specification "defsc helpers" :focused
     (component "legal-keys"
       (assertions
         "Finds all of the top-level props in a query"
         (#'prim/legal-keys [:a :b]) => #{:a :b}
         "Finds all of the top-level join keys"
         (#'prim/legal-keys [{:a [:x]} {:b [:y]}]) => #{:a :b}
         "Finds all of the unique ident (links to root) when used as props"
         (#'prim/legal-keys [[:x ''_] [:y ''_]]) => #{:x :y}
         "Finds all of the unique ident (links to root) when used as anchor of joins"
         (#'prim/legal-keys [{[:x ''_] [:a]} {[:y ''_] [:b]}]) => #{:x :y}
         "Finds keys that are parameterized"
         (#'prim/legal-keys '[(:a {:n 1})]) => #{:a}
         (#'prim/legal-keys '[({:a [:x]} {:n 1})]) => #{:a}
         (#'prim/legal-keys '[([:x '_] {:n 1})]) => #{:x}
         (#'prim/legal-keys '[({[:x '_] [:a]} {:n 1})]) => #{:x}
         ))
     (component "build-query-forms"
       (assertions
         "Support a method form"
         (#'prim/build-query-forms 'X 'this 'props {:method '(fn [] [:db/id])})
         => `(~'static fulcro.client.primitives/IQuery (~'query [~'this] [:db/id]))
         "Uses symbol from external-looking scope in output"
         (#'prim/build-query-forms 'X 'that 'props {:method '(query [] [:db/id])})
         => `(~'static fulcro.client.primitives/IQuery (~'query [~'that] [:db/id]))
         "Honors the symbol for this that is defined by defsc"
         (#'prim/build-query-forms 'X 'that 'props {:template '[:db/id]})
         => `(~'static fulcro.client.primitives/IQuery (~'query [~'that] [:db/id]))
         "Composes properties and joins into a proper query expression as a list of defui forms"
         (#'prim/build-query-forms 'X 'this 'props {:template '[:db/id :person/name {:person/job (prim/get-query Job)} {:person/settings (prim/get-query Settings)}]})
         => `(~'static fulcro.client.primitives/IQuery (~'query [~'this] [:db/id :person/name {:person/job (~'prim/get-query ~'Job)} {:person/settings (~'prim/get-query ~'Settings)}]))
         "Verifies the propargs matches queries data when not a symbol"
         (#'prim/build-query-forms 'X 'this '{:keys [db/id person/nme person/job]} {:template '[:db/id :person/name {:person/job (prim/get-query Job)}]})
         =throws=> (ExceptionInfo #"defsc X: \[person/nme\] destructured" (fn [e]
                                                                            (-> (ex-data e) :offending-symbols (= ['person/nme]))))))
     (component "build-initial-state"
       (assertions
         "Generates nothing when there is entry"
         (#'prim/build-initial-state 'S 'this nil #{} {:template []} false) => nil
         "Can build initial state from a method"
         (#'prim/build-initial-state 'S 'that {:method '(fn [p] {:x 1})} #{} {:template []} false) =>
         '(static fulcro.client.primitives/InitialAppState
            (initial-state [that p] {:x 1}))
         "Can build initial state from a template"
         (#'prim/build-initial-state 'S 'this {:template {}} #{} {:template []} false) =>
         '(static fulcro.client.primitives/InitialAppState
            (initial-state [c params]
              (fulcro.client.primitives/make-state-map {} {} params)))
         "If the query is a method, so must the initial state"
         (#'prim/build-initial-state 'S 'this {:template {:x 1}} #{} {:method '(fn [t] [])} false)
         =throws=> (ExceptionInfo #"When query is a method, initial state MUST")
         "Allows any state in initial-state method form, independent of the query form"
         (#'prim/build-initial-state 'S 'this {:method '(fn [p] {:x 1 :y 2})} #{} {:tempate []} false)
         => '(static fulcro.client.primitives/InitialAppState (initial-state [this p] {:x 1 :y 2}))
         (#'prim/build-initial-state 'S 'this {:method '(initial-state [p] {:x 1 :y 2})} #{} {:method '(query [t] [])} false)
         => '(static fulcro.client.primitives/InitialAppState (initial-state [this p] {:x 1 :y 2}))
         "In template mode: Disallows initial state to contain items that are not in the query"
         (#'prim/build-initial-state 'S 'this {:template {:x 1}} #{} {:template [:x]} false)
         =throws=> (ExceptionInfo #"Initial state includes keys that are not" (fn [e] (-> (ex-data e) :offending-keys (= #{:x}))))
         "Generates proper state parameters to make-state-map when data is available"
         (#'prim/build-initial-state 'S 'this {:template {:x 1}} #{:x} {:template [:x]} false)
         => '(static fulcro.client.primitives/InitialAppState
               (initial-state [c params]
                 (fulcro.client.primitives/make-state-map {:x 1} {} params)))
         "Adds build-form around the initial state if it is a template and there are form fields"
         (#'prim/build-initial-state 'S 'this {:template {}} #{} {:template []} true)
         => '(static fulcro.client.primitives/InitialAppState
               (initial-state [c params]
                 (fulcro.ui.forms/build-form S (fulcro.client.primitives/make-state-map {} {} params))))))
     (component "build-ident"
       (assertions
         "Generates nothing when there is no table"
         (#'prim/build-ident 't 'p nil #{}) => nil
         (#'prim/build-ident 't 'p nil #{:boo}) => nil
         "Requires the ID to be in the declared props"
         (#'prim/build-ident 't 'p {:template [:TABLE/by-id :id]} #{}) =throws=> (ExceptionInfo #"ID property of :ident")
         "Can use a ident method to build the defui forms"
         (#'prim/build-ident 't 'p {:method '(fn [] [:x :id])} #{})
         => '(static fulcro.client.primitives/Ident (ident [t p] [:x :id]))
         "Can include destructuring in props"
         (#'prim/build-ident 't '{:keys [a b c]} {:method '(fn [] [:x :id])} #{})
         => '(static fulcro.client.primitives/Ident (ident [t {:keys [a b c]}] [:x :id]))
         "Can use a vector template to generate defui forms"
         (#'prim/build-ident 't 'p {:template [:TABLE/by-id :id]} #{:id})
         => `(~'static fulcro.client.primitives/Ident (~'ident [~'this ~'props] [:TABLE/by-id (:id ~'props)]))))
     (component "rename-and-validate-fn"
       (assertions
         "Replaces the first symbol in a method/lambda form"
         (#'prim/replace-and-validate-fn 'nm [] 0 '(fn [] ...)) => '(nm [] ...)
         "Prepends the additional arguments to the front of the argument list"
         (#'prim/replace-and-validate-fn 'nm ['that 'other-thing] 3 '(fn [x y z] ...)) => '(nm [that other-thing x y z] ...)
         "Throws an exception if the arity is wrong"
         (#'prim/replace-and-validate-fn 'nm [] 2 '(fn [p] ...))
         =throws=> (ExceptionInfo #"Invalid arity for nm")))
     (component "build-css"
       (assertions
         "Can take templates and turn them into the proper protocol"
         (#'prim/build-css 'this {:template []} {:template []})
         => '(static fulcro-css.css/CSS
               (local-rules [_] [])
               (include-children [_] []))
         "Can take methods and turn them into the proper protocol"
         (#'prim/build-css 'th {:method '(fn [] [:rule])} {:method '(fn [] [CrapTastic])})
         => '(static fulcro-css.css/CSS
               (local-rules [th] [:rule])
               (include-children [th] [CrapTastic]))
         "Omits the entire protocol if neiter are supplied"
         (#'prim/build-css 'th nil nil) => nil))
     (component "build-render"
       (assertions
         "emits a list of forms for the render itself"
         (#'prim/build-render 'Boo 'this {:keys ['a]} {:keys ['onSelect]} nil '((dom/div nil "Hello")))
         => `(~'Object
               (~'render [~'this]
                 (let [{:keys [~'a]} (fulcro.client.primitives/props ~'this)
                       {:keys [~'onSelect]} (fulcro.client.primitives/get-computed ~'this)]
                   (~'dom/div nil "Hello"))))
         "all arguments after props are optional"
         (#'prim/build-render 'Boo 'this {:keys ['a]} nil nil '((dom/div nil "Hello")))
         => `(~'Object
               (~'render [~'this]
                 (let [{:keys [~'a]} (fulcro.client.primitives/props ~'this)]
                   (~'dom/div nil "Hello"))))
         "destructuring of css is allowed"
         (#'prim/build-render 'Boo 'this {:keys ['a]} {:keys ['onSelect]} '{:keys [my-class]} '((dom/div nil "Hello")))
         => `(~'Object
               (~'render [~'this]
                 (let [{:keys [~'a]} (fulcro.client.primitives/props ~'this)
                       {:keys [~'onSelect]} (fulcro.client.primitives/get-computed ~'this)
                       ~'{:keys [my-class]} (fulcro-css.css/get-classnames ~'Boo)]
                   (~'dom/div nil "Hello"))))))
     (component "make-lifecycle"
       (assertions
         "Can convert :initLocalState"
         (#'prim/make-lifecycle 'this {:initLocalState '(fn [] {:x 1})})
         => ['(initLocalState [this] {:x 1})]
         "Can convert :shouldComponentUpdate"
         (#'prim/make-lifecycle 'this {:shouldComponentUpdate '(fn [next-props next-state] false)})
         => ['(shouldComponentUpdate [this next-props next-state] false)]
         "Can convert :componentWillReceiveProps"
         (#'prim/make-lifecycle 'this {:componentWillReceiveProps '(fn [next-props] false)})
         => ['(componentWillReceiveProps [this next-props] false)]
         "Can convert :componentWillUpdate"
         (#'prim/make-lifecycle 'this {:componentWillUpdate '(fn [next-props next-state] false)})
         => ['(componentWillUpdate [this next-props next-state] false)]
         "Can convert :componentDidUpdate"
         (#'prim/make-lifecycle 'this {:componentDidUpdate '(fn [pprops pstate] false)})
         => ['(componentDidUpdate [this pprops pstate] false)]
         "Can convert :componentWillMount"
         (#'prim/make-lifecycle 'this {:componentWillMount '(fn [] false)})
         => ['(componentWillMount [this] false)]
         "Can convert :componentWillUnmount"
         (#'prim/make-lifecycle 'this {:componentWillUnmount '(fn [] false)})
         => ['(componentWillUnmount [this] false)]
         "Can convert :componentDidMount"
         (#'prim/make-lifecycle 'this {:componentDidMount '(fn [] false)})
         => ['(componentDidMount [this] false)]
         "Ignores non-lifecycle methods"
         (#'prim/make-lifecycle 'this {:goobers '(fn [] false)})
         => []))
     (component "make-state-map"
       (assertions
         "Can initialize plain state from scalar values"
         (prim/make-state-map {:db/id 1 :person/name "Tony"} {} nil) => {:db/id 1 :person/name "Tony"}
         "Can initialize plain scalar values using parameters"
         (prim/make-state-map {:db/id :param/id} {} {:id 1}) => {:db/id 1}
         "Will elide properties from missing parameters"
         (prim/make-state-map {:db/id :param/id :person/name "Tony"} {} nil) => {:person/name "Tony"}
         "Can substitute parameters into nested maps (non-children)"
         (prim/make-state-map {:scalar {:x :param/v}} {} {:v 1}) => {:scalar {:x 1}}
         "Can substitute parameters into nested vectors (non-children)"
         (prim/make-state-map {:scalar [:param/v]} {} {:v 1}) => {:scalar [1]}
         "Will include properties from explicit nil parameters"
         (prim/make-state-map {:db/id :param/id :person/name "Tony"} {} {:id nil}) => {:db/id nil :person/name "Tony"})
       (when-mocking
         (prim/get-initial-state c p) =1x=> (do
                                              (assertions
                                                "Obtains the child's initial state with the correct class and params"
                                                c => :JOB
                                                p => {:id 99})
                                              :job-99)
         (prim/get-initial-state c p) =1x=> (do
                                              (assertions
                                                "Obtains the child's initial state with the correct class and params"
                                                c => :JOB
                                                p => :JOB-PARAMS)
                                              :initialized-job)
         (prim/get-initial-state c p) =1x=> (do
                                              (assertions
                                                "Obtains the child's initial state with the correct class and params"
                                                c => :JOB
                                                p => {:id 4})
                                              :initialized-job)

         (assertions
           "Supports to-one initialization"
           (prim/make-state-map {:db/id 1 :person/job {:id 99}} {:person/job :JOB} nil) => {:db/id 1 :person/job :job-99}
           "Supports to-one initialization from a parameter"
           (prim/make-state-map {:db/id 1 :person/job :param/job} {:person/job :JOB} {:job :JOB-PARAMS}) => {:db/id 1 :person/job :initialized-job}
           "supports to-one initialization from a map with nested parameters"
           (prim/make-state-map {:db/id 1 :person/job {:id :param/job-id}} {:person/job :JOB} {:job-id 4})
           => {:db/id 1 :person/job :initialized-job}))
       (when-mocking
         (prim/get-initial-state c p) =1x=> (do
                                              (assertions
                                                "Uses parameters for the first element"
                                                c => :JOB
                                                p => {:id 1})
                                              :job1)
         (prim/get-initial-state c p) =1x=> (do
                                              (assertions
                                                "Uses parameters for the second element"
                                                c => :JOB
                                                p => {:id 2})
                                              :job2)

         (assertions
           "supports non-parameterized to-many initialization"
           (prim/make-state-map {:person/jobs [{:id 1} {:id 2}]}
             {:person/jobs :JOB} nil) => {:person/jobs [:job1 :job2]}))
       (when-mocking
         (prim/get-initial-state c p) =1x=> (do
                                              (assertions
                                                "Uses parameters for the first element"
                                                c => :JOB
                                                p => {:id 2})
                                              :A)
         (prim/get-initial-state c p) =1x=> (do
                                              (assertions
                                                "Uses parameters for the second element"
                                                c => :JOB
                                                p => {:id 3})
                                              :B)

         (assertions
           "supports to-many initialization with nested parameters"
           (prim/make-state-map {:db/id :param/id :person/jobs [{:id :param/id1} {:id :param/id2}]}
             {:person/jobs :JOB} {:id 1 :id1 2 :id2 3}) => {:db/id 1 :person/jobs [:A :B]}))
       (when-mocking
         (prim/get-initial-state c p) =1x=> (do
                                              (assertions
                                                "Uses parameters for the first element"
                                                c => :JOB
                                                p => {:id 1})
                                              :A)
         (prim/get-initial-state c p) =1x=> (do
                                              (assertions
                                                "Uses parameters for the second element"
                                                c => :JOB
                                                p => {:id 2})
                                              :B)
         (assertions
           "supports to-many initialization with nested parameters"
           (prim/make-state-map {:person/jobs :param/jobs}
             {:person/jobs :JOB} {:jobs [{:id 1} {:id 2}]}) => {:person/jobs [:A :B]})))))

#?(:clj
   (specification "defsc" :focused
     (component "css"
       (assertions
         "warns if the css destructuring is included, but no css option has been"
         (prim/defsc* '(Person [this {:keys [some-prop]} computed css] (dom/div nil "Boo")))
         =throws=> (ExceptionInfo #"You included a CSS argument, but there is no CSS localized to the component.")
         "allows one to define a simple component without options"
         (prim/defsc* '(Person [this {:keys [some-prop]}] (dom/div nil "Boo")))
         => '(fulcro.client.primitives/defui Person
               Object
               (render [this]
                 (clojure.core/let [{:keys [some-prop]} (fulcro.client.primitives/props this)]
                   (dom/div nil "Boo"))))
         "allows optional use of include when using CSS"
         (prim/defsc* '(Person [this {:keys [db/id]}]
                         {:css [:rule]}
                         (dom/div nil "Boo")))
         => '(fulcro.client.primitives/defui Person
               static
               fulcro-css.css/CSS
               (local-rules [_] [:rule])
               (include-children [_] [])
               Object
               (render [this]
                 (clojure.core/let [{:keys [db/id]} (fulcro.client.primitives/props this)]
                   (dom/div nil "Boo"))))
         "allows dropping 2 unused arguments"
         (prim/defsc* '(Job
                         "A job component"
                         [this props]
                         {}
                         (dom/span nil "TODO")))
         => '(fulcro.client.primitives/defui Job
               Object
               (render [this]
                 (clojure.core/let
                   [props (fulcro.client.primitives/props this)]
                   (dom/span nil "TODO"))))
         "allows dropping 1 unused argument"
         (prim/defsc* '(Person [this {:keys [db/id]} computed]
                         {}
                         (dom/div nil "Boo")))
         => '(fulcro.client.primitives/defui Person
               Object
               (render [this]
                 (clojure.core/let [{:keys [db/id]} (fulcro.client.primitives/props this)
                                    computed (fulcro.client.primitives/get-computed this)]
                   (dom/div nil "Boo"))))
         "allows optional use of css"
         (prim/defsc* '(Person [this {:keys [db/id]}]
                         {:query       [:db/id]
                          :css-include [A]}
                         (dom/div nil "Boo")))
         => '(fulcro.client.primitives/defui Person
               static
               fulcro-css.css/CSS
               (local-rules [_] [])
               (include-children [_] [A])
               static
               fulcro.client.primitives/IQuery
               (query [this] [:db/id])
               Object
               (render [this]
                 (clojure.core/let [{:keys [db/id]} (fulcro.client.primitives/props this)]
                   (dom/div nil "Boo"))))
         "checks method arity on css"
         (prim/defsc* '(Person
                         [this {:keys [db/id]}]
                         {:query [:db/id]
                          :css   (fn [a b] [])}
                         (dom/div nil "Boo")))
         =throws=> (ExceptionInfo #"Invalid arity for css")
         "checks method arity on css-include"
         (prim/defsc* '(Person
                         [this {:keys [db/id]}]
                         {:css-include (fn [a b] [])}
                         (dom/div nil "Boo")))
         =throws=> (ExceptionInfo #"Invalid arity for css-include")
         "allows method bodies"
         (prim/defsc* '(Person
                         [this {:keys [db/id]}]
                         {:query       [:db/id]
                          :css         (fn [] [:rule])
                          :css-include (fn [] [A])}
                         (dom/div nil "Boo")))
         => '(fulcro.client.primitives/defui Person
               static
               fulcro-css.css/CSS
               (local-rules [this] [:rule])
               (include-children [this] [A])
               static
               fulcro.client.primitives/IQuery
               (query [this] [:db/id])
               Object
               (render [this]
                 (clojure.core/let [{:keys [db/id]} (fulcro.client.primitives/props this)]
                   (dom/div nil "Boo"))))
         (prim/defsc* '(Person
                         [this {:keys [db/id]}]
                         {:query       [:db/id]
                          :css         (some-random-name [] [:rule]) ; doesn't really care what sym you use
                          :css-include (craptastic! [] [A])}
                         (dom/div nil "Boo")))
         => '(fulcro.client.primitives/defui Person
               static
               fulcro-css.css/CSS
               (local-rules [this] [:rule])
               (include-children [this] [A])
               static
               fulcro.client.primitives/IQuery
               (query [this] [:db/id])
               Object
               (render [this]
                 (clojure.core/let [{:keys [db/id]} (fulcro.client.primitives/props this)]
                   (dom/div nil "Boo"))))))
     (assertions
       "works with initial state"
       (#'prim/defsc* '(Person
                         [this {:keys [person/job db/id] :as props} {:keys [onSelect] :as computed}]
                         {:query         [:db/id {:person/job (prim/get-query Job)}]
                          :initial-state {:person/job {:x 1}
                                          :db/id      42}
                          :ident         [:PERSON/by-id :db/id]}
                         (dom/div nil "Boo")))
       => `(fulcro.client.primitives/defui ~'Person
             ~'static fulcro.client.primitives/InitialAppState
             (~'initial-state [~'c ~'params]
               (fulcro.client.primitives/make-state-map
                 {:person/job {:x 1}
                  :db/id      42}
                 {:person/job ~'Job}
                 ~'params))
             ~'static fulcro.client.primitives/Ident
             (~'ident [~'this ~'props] [:PERSON/by-id (:db/id ~'props)])
             ~'static fulcro.client.primitives/IQuery
             (~'query [~'this] [:db/id {:person/job (~'prim/get-query ~'Job)}])
             ~'Object
             (~'render [~'this]
               (let [{:keys [~'person/job ~'db/id] :as ~'props} (fulcro.client.primitives/props ~'this)
                     {:keys [~'onSelect] :as ~'computed} (fulcro.client.primitives/get-computed ~'this)]
                 (~'dom/div nil "Boo"))))
       "allows an initial state method body"
       (prim/defsc* '(Person
                       [this {:keys [person/job db/id] :as props} {:keys [onSelect] :as computed}]
                       {:query         [:db/id {:person/job (prim/get-query Job)}]
                        :initial-state (initial-state [params] {:x 1})
                        :ident         [:PERSON/by-id :db/id]}
                       (dom/div nil "Boo")))
       => `(fulcro.client.primitives/defui ~'Person
             ~'static fulcro.client.primitives/InitialAppState
             (~'initial-state [~'this ~'params] {:x 1})
             ~'static fulcro.client.primitives/Ident
             (~'ident [~'this ~'props] [:PERSON/by-id (:db/id ~'props)])
             ~'static fulcro.client.primitives/IQuery
             (~'query [~'this] [:db/id {:person/job (~'prim/get-query ~'Job)}])
             ~'Object
             (~'render [~'this]
               (let [{:keys [~'person/job ~'db/id] :as ~'props} (fulcro.client.primitives/props ~'this)
                     {:keys [~'onSelect] :as ~'computed} (fulcro.client.primitives/get-computed ~'this)]
                 (~'dom/div nil "Boo"))))
       "works without initial state"
       (prim/defsc* '(Person
                       [this {:keys [person/job db/id] :as props} {:keys [onSelect] :as computed}]
                       {:query [:db/id {:person/job (prim/get-query Job)}]
                        :ident [:PERSON/by-id :db/id]}
                       (dom/div nil "Boo")))
       => `(fulcro.client.primitives/defui ~'Person
             ~'static fulcro.client.primitives/Ident
             (~'ident [~'this ~'props] [:PERSON/by-id (:db/id ~'props)])
             ~'static fulcro.client.primitives/IQuery
             (~'query [~'this] [:db/id {:person/job (~'prim/get-query ~'Job)}])
             ~'Object
             (~'render [~'this]
               (let [{:keys [~'person/job ~'db/id] :as ~'props} (fulcro.client.primitives/props ~'this)
                     {:keys [~'onSelect] :as ~'computed} (fulcro.client.primitives/get-computed ~'this)]
                 (~'dom/div nil "Boo"))))
       "allows Object protocol"
       (prim/defsc* '(Person
                       [this props computed]
                       {:query     [:db/id]
                        :protocols (Object (shouldComponentUpdate [this p s] false))}
                       (dom/div nil "Boo")))
       => `(fulcro.client.primitives/defui ~'Person
             ~'static fulcro.client.primitives/IQuery
             (~'query [~'this] [:db/id])
             ~'Object
             (~'render [~'this]
               (let [~'props (fulcro.client.primitives/props ~'this)
                     ~'computed (fulcro.client.primitives/get-computed ~'this)]
                 (~'dom/div nil "Boo")))
             (~'shouldComponentUpdate [~'this ~'p ~'s] false))
       "Places lifecycle signatures under the Object protocol"
       (prim/defsc* '(Person [this props] {:shouldComponentUpdate (fn [next-props next-state] false)} (dom/div nil "Boo")))
       => '(fulcro.client.primitives/defui Person
             Object
             (render [this]
               (clojure.core/let
                 [props (fulcro.client.primitives/props this)]
                 (dom/div nil "Boo")))
             (shouldComponentUpdate [this next-props next-state] false))
       "Emits a placeholder body if you forget to give a body"
       (prim/defsc* '(Person [this props] {:shouldComponentUpdate (fn [props state] false)}))
       => '(fulcro.client.primitives/defui Person
             Object
             (render [this]
               (clojure.core/let
                 [props (fulcro.client.primitives/props this)]
                 (fulcro.client.dom/div nil "THIS COMPONENT HAS NO DECLARED UI")))
             (shouldComponentUpdate [this props state] false))
       "allows other protocols"
       (prim/defsc* '(Person
                       [this props computed]
                       {:query     [:db/id]
                        :protocols (static css/CSS
                                     (local-rules [_] [])
                                     (include-children [_] [])
                                     Object
                                     (shouldComponentUpdate [this p s] false))}
                       (dom/div nil "Boo")))
       => `(fulcro.client.primitives/defui ~'Person
             ~'static ~'css/CSS
             (~'local-rules [~'_] [])
             (~'include-children [~'_] [])
             ~'static fulcro.client.primitives/IQuery
             (~'query [~'this] [:db/id])
             ~'Object
             (~'render [~'this]
               (let [~'props (fulcro.client.primitives/props ~'this)
                     ~'computed (fulcro.client.primitives/get-computed ~'this)]
                 (~'dom/div nil "Boo")))
             (~'shouldComponentUpdate [~'this ~'p ~'s] false))
       "works without an ident"
       (prim/defsc* '(Person
                       [this {:keys [person/job db/id] :as props} {:keys [onSelect] :as computed}]
                       {:query [:db/id {:person/job (prim/get-query Job)}]}
                       (dom/div nil "Boo")))
       => `(fulcro.client.primitives/defui ~'Person
             ~'static fulcro.client.primitives/IQuery
             (~'query [~'this] [:db/id {:person/job (~'prim/get-query ~'Job)}])
             ~'Object
             (~'render [~'this]
               (let [{:keys [~'person/job ~'db/id] :as ~'props} (fulcro.client.primitives/props ~'this)
                     {:keys [~'onSelect] :as ~'computed} (fulcro.client.primitives/get-computed ~'this)]
                 (~'dom/div nil "Boo")))))))

(defui ^:once MergeTestChild
  static prim/Ident
  (ident [this props] [:child/by-id (:id props)])
  static prim/IQuery
  (query [this] [:id :label]))

(defui ^:once MergeTestParent
  static prim/InitialAppState
  (initial-state [this params] {:ui/checked true})
  static prim/Ident
  (ident [this props] [:parent/by-id (:id props)])
  static prim/IQuery
  (query [this] [:ui/checked :id :title {:child (prim/get-query MergeTestChild)}]))

#?(:cljs
   (specification "merge-component!"
     (assertions
       "merge-query is the component query joined on it's ident"
       (#'prim/component-merge-query MergeTestParent {:id 42}) => [{[:parent/by-id 42] [:ui/checked :id :title {:child (prim/get-query MergeTestChild)}]}])
     (component "preprocessing the object to merge"
       (let [no-state             (atom {:parent/by-id {}})
             no-state-merge-data  (:merge-data (#'prim/preprocess-merge no-state MergeTestParent {:id 42}))
             state-with-old       (atom {:parent/by-id {42 {:ui/checked true :id 42 :title "Hello"}}})
             id                   [:parent/by-id 42]
             old-state-merge-data (-> (#'prim/preprocess-merge state-with-old MergeTestParent {:id 42}) :merge-data :fulcro/merge)]
         (assertions
           "Uses the existing object in app state as base for merge when present"
           (get-in old-state-merge-data [id :ui/checked]) => true
           "Marks fields that were queried but are not present as prim/not-found"
           old-state-merge-data => {[:parent/by-id 42] {:id         42
                                                        :ui/checked true
                                                        :title      ::prim/not-found
                                                        :child      ::prim/not-found}}))
       (let [union-query {:union-a [:b] :union-b [:c]}
             state       (atom {})]
         (when-mocking
           (prim/get-ident c d) => :ident
           (prim/get-query comp) => union-query
           (prim/component-merge-query comp data) => :merge-query
           (prim/db->tree q d r) => {:ident :data}
           (prim/mark-missing d q) => (do
                                        (assertions
                                          "wraps union queries in a vector"
                                          q => [union-query])

                                        {:ident :data})
           (util/deep-merge d1 d2) => :merge-result

           (#'prim/preprocess-merge state :comp :data))))
     (let [state (atom {})
           data  {}]
       (when-mocking
         (prim/preprocess-merge s c d) => {:merge-data :the-data :merge-query :the-query}
         (prim/integrate-ident! s i op args op args) => :ignore
         (prim/get-ident c p) => [:table :id]
         (prim/merge! r d q) => :ignore
         (prim/app-state r) => state
         (p/queue! r kw) => (assertions
                              "schedules re-rendering of all affected paths"
                              kw => [:children :items])

         (prim/merge-component! :reconciler MergeTestChild data :append [:children] :replace [:items 0])))))

(specification "integrate-ident!"
  (let [state (atom {:a    {:path [[:table 2]]}
                     :b    {:path [[:table 2]]}
                     :d    [:table 6]
                     :many {:path [[:table 99] [:table 88] [:table 77]]}})]
    (behavior "Can append to an existing vector"
      (prim/integrate-ident! state [:table 3] :append [:a :path])
      (assertions
        (get-in @state [:a :path]) => [[:table 2] [:table 3]])
      (prim/integrate-ident! state [:table 3] :append [:a :path])
      (assertions
        "(is a no-op if the ident is already there)"
        (get-in @state [:a :path]) => [[:table 2] [:table 3]]))
    (behavior "Can prepend to an existing vector"
      (prim/integrate-ident! state [:table 3] :prepend [:b :path])
      (assertions
        (get-in @state [:b :path]) => [[:table 3] [:table 2]])
      (prim/integrate-ident! state [:table 3] :prepend [:b :path])
      (assertions
        "(is a no-op if already there)"
        (get-in @state [:b :path]) => [[:table 3] [:table 2]]))
    (behavior "Can create/replace a to-one ident"
      (prim/integrate-ident! state [:table 3] :replace [:c :path])
      (prim/integrate-ident! state [:table 3] :replace [:d])
      (assertions
        (get-in @state [:d]) => [:table 3]
        (get-in @state [:c :path]) => [:table 3]
        ))
    (behavior "Can replace an existing to-many element in a vector"
      (prim/integrate-ident! state [:table 3] :replace [:many :path 1])
      (assertions
        (get-in @state [:many :path]) => [[:table 99] [:table 3] [:table 77]]))))

(specification "integrate-ident"
  (let [state {:a    {:path [[:table 2]]}
               :b    {:path [[:table 2]]}
               :d    [:table 6]
               :many {:path [[:table 99] [:table 88] [:table 77]]}}]
    (assertions
      "Can append to an existing vector"
      (-> state
        (prim/integrate-ident [:table 3] :append [:a :path])
        (get-in [:a :path]))
      => [[:table 2] [:table 3]]

      "(is a no-op if the ident is already there)"
      (-> state
        (prim/integrate-ident [:table 3] :append [:a :path])
        (get-in [:a :path]))
      => [[:table 2] [:table 3]]

      "Can prepend to an existing vector"
      (-> state
        (prim/integrate-ident [:table 3] :prepend [:b :path])
        (get-in [:b :path]))
      => [[:table 3] [:table 2]]

      "(is a no-op if already there)"
      (-> state
        (prim/integrate-ident [:table 3] :prepend [:b :path])
        (get-in [:b :path]))
      => [[:table 3] [:table 2]]

      "Can create/replace a to-one ident"
      (-> state
        (prim/integrate-ident [:table 3] :replace [:d])
        (get-in [:d]))
      => [:table 3]
      (-> state
        (prim/integrate-ident [:table 3] :replace [:c :path])
        (get-in [:c :path]))
      => [:table 3]

      "Can replace an existing to-many element in a vector"
      (-> state
        (prim/integrate-ident [:table 3] :replace [:many :path 1])
        (get-in [:many :path]))
      => [[:table 99] [:table 3] [:table 77]])))

(defui ^:once MergeX
  static prim/InitialAppState
  (initial-state [this params] {:type :x :n :x})
  static prim/IQuery
  (query [this] [:n :type]))

(defui ^:once MergeY
  static prim/InitialAppState
  (initial-state [this params] {:type :y :n :y})
  static prim/IQuery
  (query [this] [:n :type]))


(defui ^:once MergeAChild
  static prim/InitialAppState
  (initial-state [this params] {:child :merge-a})
  static prim/Ident
  (ident [this props] [:mergea :child])
  static prim/IQuery
  (query [this] [:child]))

(defui ^:once MergeA
  static prim/InitialAppState
  (initial-state [this params] {:type :a :n :a :child (prim/get-initial-state MergeAChild nil)})
  static prim/IQuery
  (query [this] [:type :n {:child (prim/get-query MergeAChild)}]))

(defui ^:once MergeB
  static prim/InitialAppState
  (initial-state [this params] {:type :b :n :b})
  static prim/IQuery
  (query [this] [:n]))

(defui ^:once MergeUnion
  static prim/InitialAppState
  (initial-state [this params] (prim/get-initial-state MergeA {}))
  static prim/Ident
  (ident [this props] [:mergea-or-b :at-union])
  static prim/IQuery
  (query [this] {:a (prim/get-query MergeA) :b (prim/get-query MergeB)}))

(defui ^:once MergeRoot
  static prim/InitialAppState
  (initial-state [this params] {:a 1 :b (prim/get-initial-state MergeUnion {})})
  static prim/IQuery
  (query [this] [:a {:b (prim/get-query MergeUnion)}]))

;; Nested routing tree
;; NestedRoot
;;     |
;;     U1
;;    /  B    A = MergeRoot B = MergeB
;;    R2
;;   U2       A2
;;  X  Y

(defui ^:once U2
  static prim/InitialAppState
  (initial-state [this params] (prim/get-initial-state MergeX {}))
  static prim/IQuery
  (query [this] {:x (prim/get-query MergeX) :y (prim/get-query MergeY)}))

(defui ^:once R2
  static prim/InitialAppState
  (initial-state [this params] {:id 1 :u2 (prim/get-initial-state U2 {})})
  static prim/IQuery
  (query [this] [:id {:u2 (prim/get-query U2)}]))

(defui ^:once U1
  static prim/InitialAppState
  (initial-state [this params] (prim/get-initial-state MergeB {}))
  static prim/IQuery
  (query [this] {:r2 (prim/get-query R2) :b (prim/get-query MergeB)}))

(defui ^:once NestedRoot
  static prim/InitialAppState
  (initial-state [this params] {:u1 (prim/get-initial-state U1 {})})
  static prim/IQuery
  (query [this] [{:u1 (prim/get-query U1)}]))

;; Sibling routing tree
;; SiblingRoot
;;     |   \
;;   SU1   SU2
;;  A   B  X  Y

(defui ^:once SU1
  static prim/InitialAppState
  (initial-state [this params] (prim/get-initial-state MergeB {}))
  static prim/Ident
  (ident [this props] [(:type props) 1])
  static prim/IQuery
  (query [this] {:a (prim/get-query MergeA) :b (prim/get-query MergeB)}))

(defui ^:once SU2
  static prim/InitialAppState
  (initial-state [this params] (prim/get-initial-state MergeX {}))
  static prim/Ident
  (ident [this props] [(:type props) 2])
  static prim/IQuery
  (query [this] {:x (prim/get-query MergeX) :y (prim/get-query MergeY)}))


(defui ^:once SiblingRoot
  static prim/InitialAppState
  (initial-state [this params] {:su1 (prim/get-initial-state SU1 {}) :su2 (prim/get-initial-state SU2 {})})
  static prim/IQuery
  (query [this] [{:su1 (prim/get-query SU1)} {:su2 (prim/get-query SU2)}]))

(specification "merge-alternate-union-elements!"
  (behavior "For applications with sibling unions"
    (when-mocking
      (prim/merge-component! app comp state) =1x=> (do
                                                     (assertions
                                                       "Merges level one elements"
                                                       comp => SU1
                                                       state => (prim/get-initial-state MergeA {})))
      (prim/merge-component! app comp state) =1x=> (do
                                                     (assertions
                                                       "Merges only the state of branches that are not already initialized"
                                                       comp => SU2
                                                       state => (prim/get-initial-state MergeY {})))

      (prim/merge-alternate-union-elements! :app SiblingRoot)))

  (behavior "For applications with nested unions"
    (when-mocking
      (prim/merge-component! app comp state) =1x=> (do
                                                     (assertions
                                                       "Merges level one elements"
                                                       comp => U1
                                                       state => (prim/get-initial-state R2 {})))
      (prim/merge-component! app comp state) =1x=> (do
                                                     (assertions
                                                       "Merges only the state of branches that are not already initialized"
                                                       comp => U2
                                                       state => (prim/get-initial-state MergeY {})))

      (prim/merge-alternate-union-elements! :app NestedRoot)))
  (behavior "For applications with non-nested unions"
    (when-mocking
      (prim/merge-component! app comp state) => (do
                                                  (assertions
                                                    "Merges only the state of branches that are not already initialized"
                                                    comp => MergeUnion
                                                    state => (prim/get-initial-state MergeB {})))

      (prim/merge-alternate-union-elements! :app MergeRoot))))

(defn phone-number [id n] {:id id :number n})
(defn person [id name numbers] {:id id :name name :numbers numbers})

(defui MPhone
  static prim/IQuery
  (query [this] [:id :number])
  static prim/Ident
  (ident [this props] [:phone/by-id (:id props)]))

(defui MPerson
  static prim/IQuery
  (query [this] [:id :name {:numbers (prim/get-query MPhone)}])
  static prim/Ident
  (ident [this props] [:person/by-id (:id props)]))

(specification "merge-component"
  (let [component-tree   (person :tony "Tony" [(phone-number 1 "555-1212") (phone-number 2 "123-4555")])
        sally            {:id :sally :name "Sally" :numbers [[:phone/by-id 3]]}
        phone-3          {:id 3 :number "111-2222"}
        state-map        {:people       [[:person/by-id :sally]]
                          :phone/by-id  {3 phone-3}
                          :person/by-id {:sally sally}}
        new-state-map    (prim/merge-component state-map MPerson component-tree)
        expected-person  {:id :tony :name "Tony" :numbers [[:phone/by-id 1] [:phone/by-id 2]]}
        expected-phone-1 {:id 1 :number "555-1212"}
        expected-phone-2 {:id 2 :number "123-4555"}]
    (assertions
      "merges the top-level component with normalized links to children"
      (get-in new-state-map [:person/by-id :tony]) => expected-person
      "merges the normalized children"
      (get-in new-state-map [:phone/by-id 1]) => expected-phone-1
      (get-in new-state-map [:phone/by-id 2]) => expected-phone-2
      "leaves the original state untouched"
      (contains? new-state-map :people) => true
      (get-in new-state-map [:person/by-id :sally]) => sally
      (get-in new-state-map [:phone/by-id 3]) => phone-3)))

(def table-1 {:type :table :id 1 :rows [1 2 3]})
(defui Table
  static prim/InitialAppState
  (initial-state [c p] table-1)
  static prim/IQuery
  (query [this] [:type :id :rows]))

(def graph-1 {:type :graph :id 1 :data [1 2 3]})
(defui Graph
  static prim/InitialAppState
  (initial-state [c p] graph-1)
  static prim/IQuery
  (query [this] [:type :id :data]))

(defui Reports
  static prim/InitialAppState
  (initial-state [c p] (prim/get-initial-state Graph nil))  ; initial state will already include Graph
  static prim/Ident
  (ident [this props] [(:type props) (:id props)])
  static prim/IQuery
  (query [this] {:graph (prim/get-query Graph) :table (prim/get-query Table)}))

(defui MRRoot
  static prim/InitialAppState
  (initial-state [c p] {:reports (prim/get-initial-state Reports nil)})
  static prim/IQuery
  (query [this] [{:reports (prim/get-query Reports)}]))

(specification "merge-alternate-union-elements" :focused
  (let [initial-state (merge (prim/get-initial-state MRRoot nil) {:a 1})
        state-map     (prim/tree->db MRRoot initial-state true)
        new-state     (prim/merge-alternate-union-elements state-map MRRoot)]
    (assertions
      "can be used to merge alternate union elements to raw state"
      (get-in new-state [:table 1]) => table-1
      "(existing state isn't touched)"
      (get new-state :a) => 1
      (get new-state :reports) => [:graph 1]
      (get-in new-state [:graph 1]) => graph-1)))

(defsc A [this props] (dom/div nil "TODO"))
(defsc AQuery [this props] {:query [:x]} (dom/div nil "TODO"))
(defsc AState [this props] {:initial-state (fn [params] {})} (dom/div nil "TODO"))
(defsc AIdent [this props] {:ident (fn [] [:x 1])} (dom/div nil "TODO"))

(specification "Detection of static protocols" :focused
  (assertions
    #?(:cljs "works on client" :clj "works on server")
    (prim/has-query? A) => false
    (prim/has-query? AState) => false
    (prim/has-query? AIdent) => false
    (prim/has-initial-app-state? A) => false
    (prim/has-initial-app-state? AState) => true
    (prim/has-initial-app-state? AIdent) => false
    (prim/has-ident? A) => false
    (prim/has-ident? AState) => false
    (prim/has-ident? AIdent) => true))

