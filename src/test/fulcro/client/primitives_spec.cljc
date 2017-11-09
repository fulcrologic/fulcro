(ns fulcro.client.primitives-spec
  (:require [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
            [fulcro.client.primitives :as prim :refer [defui]]
            [fulcro.history :as hist]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as check]
            [clojure.test.check.properties :as prop]
            [clojure.test.check :as tc]
            [clojure.spec.test.alpha :as check]
    #?@(:cljs [[goog.object :as gobj]])
            [fulcro.client.impl.protocols :as p]))

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

