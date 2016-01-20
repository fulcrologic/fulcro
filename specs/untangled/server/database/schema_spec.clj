(ns untangled.server.database.schema-spec
  (:require
    [untangled.server.database.schema :as s]
    [untangled-spec.core :refer [specification behavior assertions]]))

(specification "Generate Schema generates"
  (let [schema (s/generate-schema
                 [(s/schema component
                    (s/fields
                      [name :string "my doc" :definitive :unique-identity]
                      [application :ref :one {:references :application/name}]
                      [password :string :unpublished]))])]

    (assertions
      "db/type"
      (-> schema first :db/valueType) => :db.type/string
      "db/ident"
      (-> schema first :db/valueType) => :db.type/string
      "db/index"
      (-> schema first :db/index) => false
      "db/unique"
      (-> schema first :db/unique) => :db.unique/identity
      "db/cardinality"
      (-> schema first :db/cardinality) => :db.cardinality/one
      "db/doc"
      (-> schema first :db/doc) => "my doc"
      "constraint/definitive"
      (-> schema first :constraint/definitive) => true
      "constraint/references"
      (-> schema second :constraint/references) => :application/name
      "constraint/unpublished"
      (-> schema (nth 2) :constraint/unpublished) => true
      "fulltext"
      (-> schema first :db/fulltext) => false
      "db/noHistroy"
      (-> schema first :db/noHistory) => false
      "db/isComponent"
      (-> schema first :db/isComponent) => false)))
