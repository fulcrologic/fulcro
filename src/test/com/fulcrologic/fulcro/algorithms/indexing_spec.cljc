(ns com.fulcrologic.fulcro.algorithms.indexing-spec
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.algorithms.indexing :as idx]
    [fulcro-spec.core :refer [specification assertions behavior component when-mocking]]))

(declare => =throws=>)

(defsc UnionChildA [_ _]
  {:ident [:union-child/by-id :L]
   :query [:L]})

(def ui-a (comp/factory UnionChildA))

(defsc UnionChildB [_ _]
  {:query [:M]})

(def ui-b (comp/factory UnionChildB))

(defsc Union [_ _]
  {:query (fn [] {:u1 (comp/get-query ui-a)
                  :u2 (comp/get-query ui-b)})})

(def ui-union (comp/factory Union))

(defsc Child [_ _]
  {:query [:x]})

(def ui-child (comp/factory Child))

(defsc Root [_ _]
  {:query [:a
           {:join (comp/get-query ui-child)}
           {:union (comp/get-query ui-union)}]})

(def ui-root (comp/factory Root))

(defsc UnionChildAP [_ _]
  {:query [`(:L {:child-params 1})]})

(def ui-ap (comp/factory UnionChildAP))

(defsc UnionP [_ _]
  {:query (fn [] {:u1 (comp/get-query ui-ap)
                  :u2 (comp/get-query ui-b)})})

(def ui-unionp (comp/factory UnionP))

(defsc RootP [_ _]
  {:query [:a
           `({:join ~(comp/get-query ui-child)} {:join-params 2})
           `({:union ~(comp/get-query ui-unionp)} {:union-params 3})]})

(defsc LinkChild [_ _]
  {:query [[:root/prop '_] {[:table 1] (comp/get-query Child)}]})

(defsc RootLinks [_ _]
  {:query [:root/prop {:left (comp/get-query LinkChild)}]})

(specification "index-query"
  (let [prop->classes (idx/index-query (comp/get-query Root))]
    (assertions
      "Properly indexes components with props, joins, and unions"
      (prop->classes :L) => #{UnionChildA}
      (prop->classes :M) => #{UnionChildB}
      (prop->classes :a) => #{Root}
      (prop->classes :join) => #{Root}
      (prop->classes :union) => #{Root}))
  (let [prop->classes (idx/index-query (comp/get-query RootP))]
    (assertions
      "Properly indexes components that have parameterized queries"
      (prop->classes :L) => #{UnionChildAP}
      (prop->classes :M) => #{UnionChildB}
      (prop->classes :a) => #{RootP}
      (prop->classes :join) => #{RootP}
      (prop->classes :union) => #{RootP}))
  (let [prop->classes (idx/index-query (comp/get-query RootLinks))]
    (assertions
      "Properly indexes components that have link queries and ident joins"
      (prop->classes :left) => #{RootLinks}
      (prop->classes :root/prop) => #{RootLinks LinkChild}
      (prop->classes [:table 1]) => #{LinkChild})))

(specification "index-root*"
  (let [runtime-state {::app/indexes {}}
        root-query    (comp/get-query RootLinks)
        rs2           (idx/index-root* runtime-state root-query)]
    (assertions
      "Adds a list of all ident join keys to the indexes"
      (-> rs2 ::app/indexes :idents-in-joins) => #{[:table 1]}
      "Adds the prop->classes index"
      (contains? (-> rs2 ::app/indexes) :prop->classes) => true)))

(specification "index-component*"
  (let [runtime-state {::app/indexes {}}
        ra2           (idx/index-component* runtime-state :instance1 [:x 1] LinkChild)]
    (assertions
      "adds the component instance to the ident index"
      (-> ra2 ::app/indexes :ident->components (get [:x 1])) => #{:instance1}
      "adds the component instance to the class index"
      (-> ra2 ::app/indexes :class->components (get LinkChild)) => #{:instance1})))

(specification "drop-component*"
  (let [runtime-state {::app/indexes {:ident->components {[:x 1] #{:instance1}}
                                      :class->components {LinkChild #{:instance1}}}}
        ra2           (idx/drop-component* runtime-state :instance1 [:x 1] LinkChild)]
    (assertions
      "removes the component instance to the ident index"
      (-> ra2 ::app/indexes :ident->components (get [:x 1])) => #{}
      "removes the component instance to the class index"
      (-> ra2 ::app/indexes :class->components (get LinkChild)) => #{})))
