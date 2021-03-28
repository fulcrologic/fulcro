(ns com.fulcrologic.fulcro.mutations-spec
  (:require
    [com.fulcrologic.fulcro.raw.components :as rc]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.mutations :as m]
    [fulcro-spec.core :refer [specification assertions behavior component =>]]))

(specification "returning"
  (behavior "adds a component query to the AST of the given mutation node"
    (let [env {:state (atom {})
               :ast   (-> (eql/query->ast '[(f)])
                        :children
                        first)}
          {new-ast :ast} (m/returning env (rc/nc [:person/id :person/name]))]
      (assertions
        "sets the query of the parent node"
        (:query new-ast) => [:person/id :person/name]
        "adds the query children nodes"
        (:children new-ast) => [{:type :prop, :dispatch-key :person/id, :key :person/id}
                                {:type :prop, :dispatch-key :person/name, :key :person/name}])))
  (behavior "supports adding query params to the query"
    (let [env {:state (atom {})
               :ast   (-> (eql/query->ast '[(f)])
                        :children
                        first)}
          {new-ast :ast} (m/returning env (rc/nc [:person/id :person/name]) {:query-params {:page 2}})]
      (assertions
        "sets the query of the parent node"
        (:query new-ast) => '[(:person/id {:page 2}) :person/name]
        "adds the query children nodes"
        (:children new-ast) => [{:type :prop, :dispatch-key :person/id, :key :person/id :params {:page 2}}
                                {:type :prop, :dispatch-key :person/name, :key :person/name}]))))
