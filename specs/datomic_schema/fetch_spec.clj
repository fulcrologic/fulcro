(ns datomic-schema.fetch-spec
  (:require
    [datomic-schema.fetch :as rest]
    [datomic.api :as d]
    [seeddata.auth :as a]
    [util.seed :as s]
    [util.fixtures :refer [with-db-fixture]]
    [resources.datomic-schema.rest-schema.initial]
    )
  (:use midje.sweet)
  )

(defn- seed-rest [conn]
  (let [entities (a/create-base-user-and-realm)]

    )
  )

;; HACK!!!!!
#_(facts :integration "datomic.schema - fetch-schema returns a configuration with"
       (against-background
         [
          (around :contents (let [c (:connection dbcomp)
                               id-map (-> dbcomp :seed-result)
                               schema-representations (rest/fetch-schema c)
                               ] ?form))
          (around :contents (with-db-fixture dbcomp ?form
                                             :migrations "resources.datomic-schema.rest-schema"
                                             :seed-fn seed-rest))
          ]

         (fact :integration "finds entity doc"
               (-> schema-representations :entities :realm :doc) => "realm entity doc"
               )
         (fact :integration "finds type db.type/string"
               (-> schema-representations :entities :realm :attributes :realm/realm-name :db/valueType) => :db.type/string
               )
         (fact :integration "finds type :db.type/ref"
               (-> schema-representations :entities :realm :attributes :realm/user :db/valueType) => :db.type/ref
               )
         (fact :integration "adds foreign-attribute to entity"
               (-> schema-representations  :entities :component :attributes :realm/realm-id :db/valueType ) => :db.type/string
               )
         (fact :integration "adds a second foreign-attribute to entity"
               (-> schema-representations  :entities :component :attributes :realm/realm-name :db/valueType ) => :db.type/string
               )
         (fact :integration "finds :db.cardinality/many on a reference that contains a many relationship"
               (-> schema-representations :entities :realm :attributes :realm/user :db/cardinality) => :db.cardinality/many
               )
         (fact :integration "finds :db.cardinality/one on a reference that contains a one relationship"
               (-> schema-representations :entities :user :attributes :user/realm :db/cardinality) => :db.cardinality/one
               )
         (fact :integration "finds :db/doc"
               (-> schema-representations :entities :realm :attributes :realm/realm-id :db/doc) => "realm-id-doc"
               )
         (fact :integration "will not find :db/doc where none exists"
               (-> schema-representations :entities :subscription :attributes :subscription/name :db/doc) => ""
               )
         (fact :integration "will not find :rest/entity-doc where none exists"
               (-> schema-representations :entities :realm :attributes :realm/realm-name :rest/entity-doc) => nil
               )
         (fact :integration "finds :constraint/definitive"
               (-> schema-representations :entities :realm :attributes :realm/realm-id :constraint/definitive) => true
               )
         (fact :integration "will not find :constraint/definitive"
               (-> schema-representations :entities :realm :attributes :realm/realm-name :constraint/definitive) => nil
               )
         (fact :integration "will build definitive list"
               (-> schema-representations :definitive) => [:subscription/name :component/name :realm/realm-id :user/user-id]
               )
         (fact :integration "finds :constraint/unpublished"
               (-> schema-representations :entities :user :attributes :user/password :constraint/unpublished) => true
               )
         )
       )
