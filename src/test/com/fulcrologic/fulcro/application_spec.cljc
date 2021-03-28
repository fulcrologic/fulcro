(ns com.fulcrologic.fulcro.application-spec
  (:require
    [fulcro-spec.core :refer [specification provided! when-mocking! assertions behavior when-mocking component =>]]
    [clojure.spec.alpha :as s]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.specs]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.fulcro.application :as app :refer [fulcro-app]]
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
