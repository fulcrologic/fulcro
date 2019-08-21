(ns com.fulcrologic.fulcro.component-dynamic-query-spec
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [fulcro-spec.core :refer [specification behavior component assertions when-mocking]]
    #?(:cljs [goog.object :as gobj])))

(defsc A [_ _] {})

(specification "Query IDs"
  (assertions
    "Start with the fully-qualifier class name"
    (comp/query-id A nil) => "com.fulcrologic.fulcro.component-dynamic-query-spec/A"
    "Include the optional qualifier"
    (comp/query-id A :x) => "com.fulcrologic.fulcro.component-dynamic-query-spec/A$:x"))

(specification "UI Factory"
  (assertions
    "Adds  react-class to the metadata of the generated factory"
    (some-> (comp/factory A) meta :class) => A
    "Adds an optional qualifier to the metadata of the generated factory"
    (some-> (comp/factory A) meta :qualifier) => nil
    (some-> (comp/factory A {:qualifier :x}) meta :qualifier) => :x)
  (behavior "Adds an internal query id to the props passed by the factory"
    #?(:cljs
       (when-mocking
         (comp/query-id c q) => :ID
         (comp/create-element class props children) => (do
                                                         (assertions
                                                           (gobj/get props "fulcro$queryid") => :ID))

         ((comp/factory A) {}))
       :clj
       (let [class (fn [_ _ props _] (assertions (:fulcro$queryid props) => :ID))]
         (when-mocking
           (comp/query-id c q) => :ID
           ;(comp/init-local-state c) => nil

           ((comp/factory class) {}))))))

(defsc Q [_ _] {:query [:a :b]})
(def ui-q (comp/factory Q))
(defsc UnionChildA [_ _]
  {:ident [:union-child/by-id :L]
   :query [:L]})
(def ui-a (comp/factory UnionChildA))
(defsc UnionChildB [_ _] {:query [:M]})
(def ui-b (comp/factory UnionChildB))
(defsc Union [_ _]
  {:query (fn [] {:u1 (comp/get-query ui-a)
                  :u2 (comp/get-query ui-b)})})
(def ui-union (comp/factory Union))
(defsc Child [_ _] {:query [:x]})
(def ui-child (comp/factory Child))
(defsc Root [_ _]
  {:query [:a
           {:join (comp/get-query ui-child)}
           {:union (comp/get-query ui-union)}]})
(def ui-root (comp/factory Root))
(defsc UnionChildAP [_ _] {:query (fn [] '[(:L {:child-params 1})])})
(def ui-ap (comp/factory UnionChildAP))
(defsc UnionP [_ _] {:query (fn [] {:u1 (comp/get-query ui-ap)
                                    :u2 (comp/get-query ui-b)})})
(def ui-unionp (comp/factory UnionP))
(defsc RootP [_ _]
  {:query (fn [] `[:a
                   ({:join ~(comp/get-query ui-child)} {:join-params 2})
                   ({:union ~(comp/get-query ui-unionp)} {:union-params 3})])})

(def ui-rootp (comp/factory RootP))

(specification "link-query"
  (assertions
    "Replaces nested queries with their string ID"
    (comp/link-query (comp/get-query ui-root {})) => [:a {:join (comp/query-id Child nil)} {:union (comp/query-id Union nil)}]))

(specification "normalize-query"
  (let [union-child-a-id (comp/query-id UnionChildA nil)
        union-child-b-id (comp/query-id UnionChildB nil)
        child-id         (comp/query-id Child nil)
        root-id          (comp/query-id Root nil)
        union-id         (comp/query-id Union nil)
        existing-query   {:id    union-child-a-id
                          :query [:OTHER]}]
    (assertions
      "Adds simple single-level queries into app state under a reserved key"
      (comp/normalize-query {} (comp/get-query ui-a {})) => {::comp/queries {union-child-a-id
                                                                             {:id    union-child-a-id
                                                                              :query [:L]}}}
      "Single-level queries are not added if a query is already set in state"
      (comp/normalize-query {::comp/queries {union-child-a-id existing-query}} (comp/get-query ui-a {})) => {::comp/queries {union-child-a-id existing-query}}
      "More complicated queries normalize correctly"
      (comp/normalize-query {} (comp/get-query ui-root {}))
      => {::comp/queries {root-id          {:id    root-id
                                            :query [:a {:join child-id} {:union union-id}]}
                          union-id         {:id    union-id
                                            :query {:u1 union-child-a-id :u2 union-child-b-id}}
                          union-child-b-id {:id    union-child-b-id
                                            :query [:M]}
                          union-child-a-id {:id    union-child-a-id
                                            :query [:L]}
                          child-id         {:query [:x]
                                            :id    child-id}}}
      "Can normalize parameterized union queries."
      (comp/get-query ui-rootp (comp/normalize-query {} (comp/get-query ui-rootp {})))
      => '[:a ({:join [:x]} {:join-params 2}) ({:union {:u1 [(:L {:child-params 1})] :u2 [:M]}} {:union-params 3})])))

(specification "get-query*"
  (assertions
    "Obtains the static query from a given class"
    (comp/get-query Q) => [:a :b]
    "Obtains the static query when the state has no stored queries"
    (comp/get-query ui-q {}) => [:a :b])
  (let [query        (comp/get-query ui-root {})
        top-level    query
        join-target  (get-in query [1 :join])
        union-target (get-in query [2 :union])
        union-left   (get union-target :u1)
        union-right  (get union-target :u2)]
    (assertions
      "Places a query ID on the metadata of each component's portion of the query"
      (-> top-level meta :queryid) => "com.fulcrologic.fulcro.component-dynamic-query-spec/Root"
      (-> join-target meta :queryid) => "com.fulcrologic.fulcro.component-dynamic-query-spec/Child"
      (-> union-target meta :queryid) => "com.fulcrologic.fulcro.component-dynamic-query-spec/Union"
      (-> union-left meta :queryid) => "com.fulcrologic.fulcro.component-dynamic-query-spec/UnionChildA"
      (-> union-right meta :queryid) => "com.fulcrologic.fulcro.component-dynamic-query-spec/UnionChildB"))
  (let [app-state (comp/normalize-query {} (comp/get-query ui-root {}))
        app-state (assoc-in app-state [::comp/queries (comp/query-id UnionChildA nil) :query] [:UPDATED])]
    (behavior "Pulls a denormalized query from app state if one exists."
      (assertions
        (comp/get-query ui-root app-state) => [:a {:join [:x]} {:union {:u1 [:UPDATED] :u2 [:M]}}]))
    (behavior "Allows a class instead of a factory"
      (assertions
        "with state"
        (comp/get-query Root app-state) => [:a {:join [:x]} {:union {:u1 [:UPDATED] :u2 [:M]}}]
        "without state (raw static query)"
        (comp/get-query Root) => [:a {:join [:x]} {:union {:u1 [:L] :u2 [:M]}}]))))

(specification "Normalization"
  (let [query               (comp/get-query ui-root {})
        parameterized-query (comp/get-query ui-rootp {})
        state               (comp/normalize-query {} query)
        state-parameterized (comp/normalize-query {} parameterized-query)]
    (assertions
      "Preserves the query when parameters are not present"
      (comp/get-query ui-root state) => query
      "Preserves the query when parameters are present"
      (comp/get-query ui-rootp state-parameterized) => parameterized-query)))

(specification "Setting a query"
  (let [query               (comp/get-query ui-root {})
        state               (comp/normalize-query {} query)
        state-modified      (comp/set-query* state ui-b {:query [:MODIFIED]})
        expected-query      (assoc-in query [2 :union :u2 0] :MODIFIED)
        state-modified-root (comp/set-query* state Root {:query [:b
                                                                 {:join (comp/get-query ui-child state)}
                                                                 {:union (comp/get-query ui-union state)}]})
        expected-root-query (assoc query 0 :b)]
    (assertions
      "Can update a node by factory"
      (comp/get-query ui-root state-modified) => expected-query
      "Can update a node by class"
      (comp/get-query ui-root state-modified-root) => expected-root-query
      "Maintains metadata (when used without state)"
      (-> (comp/get-query ui-root) meta :component) => Root
      (-> (comp/get-query ui-root) second first second meta :component) => Child
      (-> (comp/get-query ui-root) (nth 2) first second meta :component) => Union
      ;; FIXME: Dynamic queries are broken with respect to auto-normalization because they don't have
      ;; the component with them.  Going to take some effort to fix.
      "Maintains top metadata (when used with state)"
      (-> (comp/get-query ui-root state-modified) meta :component) => Root
      "Maintains children metadata (when used with state)"
      (-> (comp/get-query ui-root state-modified) second first second meta :component) => Child
      "Maintains union metadata (when used with state)"
      (-> (comp/get-query ui-root state-modified) (nth 2) :union meta :component) => Union
      (-> (comp/get-query ui-root state-modified) (nth 2) :union :u1 meta :component) => UnionChildA
      (-> (comp/get-query ui-root state-modified) (nth 2) :union :u2 meta :component) => UnionChildB)))

(defsc RecursiveChild [_ _]
  {:query (fn [] [:name {:link '...}])})

(defsc RecursiveChild2 [_ _]
  {:query (fn [] [:place {:link 3}])})

(defsc RecursiveParent [_ _]
  {:query (fn [] [:name
                  {:child (comp/get-query RecursiveChild)}
                  {:child2 (comp/get-query RecursiveChild2)}])})

(specification "Recursion Support for Dynamic Queries"
  (component "Normalization"
    (let [query            (comp/get-query RecursiveParent {})
          state            (comp/normalize-query {} query)
          round-trip-query (comp/get-query RecursiveParent state)]
      (assertions
        "Normalizes recursive queries so that they can be retrieved"
        round-trip-query => query)))
  (component "Explicit sets"
    (let [query           (comp/get-query RecursiveParent {})
          state           (comp/normalize-query {} query)
          state-after-set (comp/set-query* state RecursiveChild {:query [:boo {:link 4}]})
          new-query       (comp/get-query RecursiveParent state-after-set)]
      (assertions
        "Normalizes recursive queries so that they can be retrieved"
        new-query => '[:name {:child [:boo {:link 4}]} {:child2 [:place {:link 3}]}]))))

(defsc T2C1 [_ _]
  {:query [:id :name]
   :ident [:x/by-id :id]})

(defsc T2 [_ _]
  {:query [:id {:x (comp/get-query T2C1)}]
   :ident [:a/by-id :id]})

(specification "Compound set-query"
  (let [state       {}
        new-state   (as-> state s
                      (comp/set-query* s T2C1 {:query [:id :address]})
                      (comp/set-query* s T2 {:query [:id :prop {:x (comp/get-query T2C1 s)}]}))
        saved-query (comp/get-query T2 new-state)]
    (assertions
      "Updates the query"
      saved-query => [:id :prop {:x [:id :address]}]
      "preserves metadata on the top-level"
      (-> saved-query meta :component) => T2
      "preserves metadata on the query joins"
      (-> saved-query (nth 2) :x meta :component) => T2C1)))

