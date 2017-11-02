(ns fulcro.client.primitives-spec
  (:require [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
            [fulcro.client.primitives :as prim :refer [defui]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as check]
            [clojure.test.check.properties :as prop]
            [clojure.test.check :as tc]
            [clojure.spec.test.alpha :as check]
            [fulcro.client.primitives :as om+]
    #?@(:cljs [[goog.object :as gobj]])
            [fulcro.client.impl.protocols :as p]))

(defui A)

(specification "Query IDs" :focused
  (assertions
    "Start with the fully-qualifier class name"
    (om+/query-id A nil) => "fulcro$client$primitives_spec$A"
    "Include the optional qualifier"
    (om+/query-id A :x) => "fulcro$client$primitives_spec$A$:x"))

(specification "UI Factory" :focused
  (assertions
    "Adds  react-class to the metadata of the generated factory"
    (some-> (om+/factory A) meta :class) => A
    "Adds an optional qualifier to the metadata of the generated factory"
    (some-> (om+/factory A) meta :qualifier) => nil
    (some-> (om+/factory A {:qualifier :x}) meta :qualifier) => :x)
  (behavior "Adds an internal query id to the props passed by the factory"
    #?(:cljs
       (when-mocking
         (om+/query-id c q) => :ID
         (om+/create-element class props children) => (do
                                                        (assertions
                                                          (gobj/get props "omcljs$queryid") => :ID))

         ((om+/factory A) {}))
       :clj
       (let [class (fn [_ _ props _] (assertions (:omcljs$queryid props) => :ID))]
         (when-mocking
           (om+/query-id c q) => :ID
           (om+/init-local-state c) => nil

           ((om+/factory class) {}))))))

(defui Q
  static om+/IDynamicQuery
  (dynamic-query [this state] [:a :b]))

(def ui-q (om+/factory Q))

(defui UnionChildA
  static prim/Ident
  (ident [this props] [:union-child/by-id (:L props)])
  static om+/IDynamicQuery
  (dynamic-query [this state] [:L]))

(def ui-a (om+/factory UnionChildA))

(defui UnionChildB
  static om+/IDynamicQuery
  (dynamic-query [this state] [:M]))

(def ui-b (om+/factory UnionChildB))

(defui Union
  static om+/IDynamicQuery
  (dynamic-query [this state] {:u1 (om+/get-query ui-a state)
                               :u2 (om+/get-query ui-b state)}))

(def ui-union (om+/factory Union))

(defui Child
  static om+/IDynamicQuery
  (dynamic-query [this state] [:x]))

(def ui-child (om+/factory Child))

(defui Root
  static om+/IDynamicQuery
  (dynamic-query [this state] [:a
                               {:join (om+/get-query ui-child state)}
                               {:union (om+/get-query ui-union state)}]))

(def ui-root (om+/factory Root))

(defui UnionChildAP
  static om+/IDynamicQuery
  (dynamic-query [this state] '[(:L {:child-params 1})]))

(def ui-ap (om+/factory UnionChildAP))

(defui UnionP
  static om+/IDynamicQuery
  (dynamic-query [this state] {:u1 (om+/get-query ui-ap state)
                               :u2 (om+/get-query ui-b state)}))

(def ui-unionp (om+/factory UnionP))

(defui RootP
  static om+/IDynamicQuery
  (dynamic-query [this state] `[:a
                                ({:join ~(om+/get-query ui-child state)} {:join-params 2})
                                ({:union ~(om+/get-query ui-unionp state)} {:union-params 3})]))

(def ui-rootp (om+/factory RootP))

(specification "link-query" :focused
  (assertions
    "Replaces nested queries with their string ID"
    (om+/link-query (om+/get-query ui-root {})) => [:a {:join (om+/query-id Child nil)} {:union (om+/query-id Union nil)}]))

(specification "normalize-query" :focused
  (let [union-child-a-id (om+/query-id UnionChildA nil)
        union-child-b-id (om+/query-id UnionChildB nil)
        child-id         (om+/query-id Child nil)
        root-id          (om+/query-id Root nil)
        union-id         (om+/query-id Union nil)
        existing-query   {:id    union-child-a-id
                          :query [:OTHER]}]
    (assertions
      "Adds simple single-level queries into app state under a reserved key"
      (om+/normalize-query {} (om+/get-query ui-a {})) => {::om+/queries {union-child-a-id
                                                                          {:id    union-child-a-id
                                                                           :query [:L]}}}
      "Single-level queries are not added if a query is already set in state"
      (om+/normalize-query {::om+/queries {union-child-a-id existing-query}} (om+/get-query ui-a {})) => {::om+/queries {union-child-a-id existing-query}}
      "More complicated queries normalize correctly"
      (om+/normalize-query {} (om+/get-query ui-root {}))
      => {::om+/queries {root-id          {:id    root-id
                                           :query [:a {:join child-id} {:union union-id}]}
                         union-id         {:id    union-id
                                           :query {:u1 union-child-a-id :u2 union-child-b-id}}
                         union-child-b-id {:id    union-child-b-id
                                           :query [:M]}
                         union-child-a-id {:id    union-child-a-id
                                           :query [:L]}
                         child-id         {:query [:x]
                                           :id    child-id}}})))

(specification "get-query*" :focused
  (assertions
    "Obtains the static query from a given class"
    (om+/get-query Q) => [:a :b]
    "Obtains the static query when the state has no stored queries"
    (om+/get-query ui-q {}) => [:a :b])
  (let [query        (om+/get-query ui-root {})
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
  (let [app-state (om+/normalize-query {} (om+/get-query ui-root {}))
        app-state (assoc-in app-state [::om+/queries (om+/query-id UnionChildA nil) :query] [:UPDATED])]
    (behavior "Pulls a denormalized query from app state if one exists."
      (assertions
        (om+/get-query ui-root app-state) => [:a {:join [:x]} {:union {:u1 [:UPDATED] :u2 [:M]}}]))
    (behavior "Allows a class instead of a factory"
      (assertions
        "with state"
        (om+/get-query Root app-state) => [:a {:join [:x]} {:union {:u1 [:UPDATED] :u2 [:M]}}]
        "without state (raw static query)"
        (om+/get-query Root) => [:a {:join [:x]} {:union {:u1 [:L] :u2 [:M]}}]))))

(specification "Normalization preserves query" :focused
  (let [query               (om+/get-query ui-root {})
        parameterized-query (om+/get-query ui-rootp {})
        state               (om+/normalize-query {} query)
        state-parameterized (om+/normalize-query {} parameterized-query)]
    (assertions
      "When parameters are not present"
      (om+/get-query ui-root state) => query
      "When parameters are present"
      (om+/get-query ui-rootp state) => parameterized-query)))

; TODO: This would be a great property-based check  (marshalling/unmarshalling) if we had generators that would work...

(specification "Setting a query" :focused
  (let [query               (om+/get-query ui-root {})
        parameterized-query (om+/get-query ui-rootp {})
        state               (om+/normalize-query {} query)
        state-modified      (om+/set-query* state ui-b {:query [:MODIFIED]})
        expected-query      (assoc-in query [2 :union :u2 0] :MODIFIED)
        state-modified-root (om+/set-query* state Root {:query [:b
                                                                {:join (om+/get-query ui-child state)}
                                                                {:union (om+/get-query ui-union state)}]})
        expected-root-query (assoc query 0 :b)
        state-parameterized (om+/normalize-query {} parameterized-query)]
    (assertions
      "Can update a node by factory"
      (om+/get-query ui-root state-modified) => expected-query
      "Can update a node by class"
      (om+/get-query ui-root state-modified-root) => expected-root-query)))

(specification "Indexing" :focused
  (component "Gathering keys for a query"
    (assertions
      "finds the correct prop keys (without parameters)"
      (om+/gather-keys (om+/get-query ui-root {})) => #{:a :join :union}
      (om+/gather-keys (om+/get-query ui-union {})) => #{:u1 :u2}
      (om+/gather-keys (om+/get-query ui-a {})) => #{:L}
      (om+/gather-keys (om+/get-query ui-b {})) => #{:M}
      (om+/gather-keys (om+/get-query ui-child {})) => #{:x}
      "finds the correct prop keys (with parameters)"
      (om+/gather-keys (om+/get-query ui-rootp {})) => #{:a :join :union}
      (om+/gather-keys (om+/get-query ui-ap {})) => #{:L}
      (om+/gather-keys (om+/get-query ui-unionp {})) => #{:u1 :u2}))
  (component "Indexer"
    (let [indexer (om+/map->Indexer {:indexes (atom {})})
          indexer (assoc indexer :state {})]

      (p/index-root indexer Root)

      (assertions
        "Properly indexes components"
        (-> indexer :indexes deref :prop->classes :L) => #{UnionChildA}
        (-> indexer :indexes deref :prop->classes :M) => #{UnionChildB}
        (-> indexer :indexes deref :prop->classes :a) => #{Root}
        (-> indexer :indexes deref :prop->classes :join) => #{Root}
        (-> indexer :indexes deref :prop->classes :union) => #{Root}))
    (let [indexer (om+/map->Indexer {:indexes (atom {})})
          indexer (assoc indexer :state {})]

      (p/index-root indexer RootP)

      (assertions
        "Properly indexes components that have parameterized queries"
        (-> indexer :indexes deref :prop->classes :L) => #{UnionChildAP}
        (-> indexer :indexes deref :prop->classes :M) => #{UnionChildB}
        (-> indexer :indexes deref :prop->classes :a) => #{RootP}
        (-> indexer :indexes deref :prop->classes :join) => #{RootP}
        (-> indexer :indexes deref :prop->classes :union) => #{RootP}))
    (let [indexer   (om+/map->Indexer {:indexes (atom {})})
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

(specification "Static Queries" :focused
  (behavior "Maintain their backward-compatible functionality" :manual-test))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Om Next query spec, with generators for property-based tests
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

