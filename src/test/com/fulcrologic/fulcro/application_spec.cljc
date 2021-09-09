(ns com.fulcrologic.fulcro.application-spec
  (:require
    [fulcro-spec.core :refer [specification provided! when-mocking! assertions behavior when-mocking component =>]]
    [clojure.spec.alpha :as s]
   [clojure.walk :as walk]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.specs]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.fulcro.application :as app :refer [fulcro-app]]
   [com.fulcrologic.fulcro.raw.application :as rapp]
   [com.wsscode.pathom.core :as p]
    [clojure.test :refer [is are deftest]]))

(deftest application-constructor
  (let [app (app/fulcro-app)]
    (assertions
      "Conforms to the application spec"
      (s/explain-data ::app/app app) => nil)))

(specification "Static extensible configuration"
  (let [app (app/fulcro-app {:external-config {::x 1}})]
    (assertions
      "Allows arbitrary k-v pairs to be added to the static config"
      (comp/external-config app ::x) => 1)))

(specification "Default query elision"
  (behavior "Removes ui-namespaced keys that are in props, joins, idents, and mutation joins"
    (are [query result]
      (= result (eql/ast->query (app/default-global-eql-transform (eql/query->ast query))))
      [:ui/name :x] [:x]
      '[(:ui/name {:b 2}) :x] [:x]
      [{:ui/name [:b]} :x] [:x]
      [[:ui/name 42] :x] [:x]
      [{[:ui/name 42] [:t]} :x] [:x]
      [:ui/name :x :ui/adsf-b] [:x]))
  (behavior "Removes ui and fulcro keys from mutation join queries, but NOT mutation params"
    (are [query result]
      (= result (eql/ast->query (app/default-global-eql-transform (eql/query->ast query))))
      [{'(f {:ui/param 1 :com.fulcrologic.fulcro/param 42}) [:ui/a :b {:com.fulcrologic.fulcro.core/boo [:y]}]}]
      [{'(f {:ui/param 1 :com.fulcrologic.fulcro/param 42}) [:b]}]))
  (behavior "Removes items that are namespaced to Fulcro itself"
    (are [query result]
      (= result (eql/ast->query (app/default-global-eql-transform (eql/query->ast query))))
      [{[::uism/asm-id 42] [:y]} :x] [:x]
      [::uism/asm-id :x] [:x]
      [{::uism/asm-id [:y]} :x] [:x]
      [::dr/id ::dr/current-route [::uism/asm-id '_] :x] [:x])))

(defn sort-nested 
  "Sort keywords in vectors anywhere in the query to simplify comparison"
  [query]
  (letfn [(compare-any [x y]
            (if (every? keyword? [x y])
              (compare x y)
              (if (keyword? x) -1 1)))]
    (walk/postwalk
      #(cond-> %
         (vector? %) (->> (sort compare-any) vec))
      query)))

(defn transform-query [query]
  (-> query
      eql/query->ast
      rapp/default-global-eql-transform
      eql/ast->query))

(specification "Default EQL transform additions" :focus
  (behavior "Adds ::p/errors to all queries and mutation joins"
    (are [query result] (= result (sort-nested (transform-query query)))
      [:a] [:a ::p/errors]
      [{:d [:e]}] [::p/errors {:d [:e]}]
      '[(mutation1)] '[::p/errors (mutation1)] ; FIXME fails b/c there is also :tempids
      '[(mutation2 {:b 1})] '[::p/errors (mutation2 {:b 1})] ; FIXME fails b/c there is also :tempids
      '[{(mutation3) [:c]}] '[::p/errors {(mutation3) [:c]}])) ; FIXME fails b/c there is also :tempids
  (behavior "Adds :tempids to (only) mutation joins"
    (are [query result] (= result (remove keyword? (sort-nested (transform-query query))))
      '[(mutation2 {:b 1})] '[{(mutation2 {:b 1}) [:tempids]}]
      '[{(mutation3) [:c]}] '[{(mutation3) [:c :tempids]}])
    (behavior "Preserves Pathom's behavior of returning the whole mutation's output if the user hasn't asked for anything in particular"
      (are [query result] (= (set result) (sort-nested (transform-query query)))
        '[(mutation1)] '[{(mutation1) [* :tempids]}]))))
