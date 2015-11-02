(ns datomic-schema.schema-spec
  (:require
    [datomic-schema.schema :as s]
    [datomic.api :as d]
    )
  (:use midje.sweet))


(facts "Generate Schema generates"
       (against-background
         [(around :contents (let [schema (s/generate-schema
                                           [(s/schema component
                                                      (s/fields
                                                        [name :string "my doc" :definitive :unique-identity]
                                                        [application :ref :one {:references :application/name}]
                                                        [password :string :unpublished]
                                                        )
                                                      )])]
                              ?form))]
         (fact "db/type"
               (-> schema first :db/valueType) => :db.type/string
               )
         (fact "db/ident"
               (-> schema first :db/valueType) => :db.type/string
               )
         (fact "db/index"
               (-> schema first :db/index) => false
               )
         (fact "db/unique"
               (-> schema first :db/unique) => :db.unique/identity
               )
         (fact "db/cardinality"
               (-> schema first :db/cardinality) => :db.cardinality/one
               )
         (fact "db/doc"
               (-> schema first :db/doc) => "my doc"
               )
         (fact "constraint/definitive"
               (-> schema first :constraint/definitive) => true
               )
         (fact "constraint/references"
               (-> schema second :constraint/references) => :application/name
               )
         (fact "constraint/unpublished"
               (-> schema (nth 2) :constraint/unpublished) => true
               )
         (fact "fulltext"
               (-> schema first :db/fulltext ) => false
               )
         (fact "db/noHistroy"
               (-> schema first :db/noHistory ) => false
               )
         (fact "db/isComponent"
               (-> schema first :db/isComponent ) => false
               )
         )
       )
