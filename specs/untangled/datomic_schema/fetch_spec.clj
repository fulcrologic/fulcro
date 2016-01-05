(ns untangled.datomic-schema.fetch-spec
  (:require
    [untangled.datomic-schema.fetch :as rest]
    [seeddata.auth :as a]
    [untangled.util.fixtures :refer [with-db-fixture]]
    [resources.datomic-schema.rest-schema.initial]
    [untangled-spec.core :refer [specification
                                 assertions
                                 when-mocking
                                 component
                                 behavior]]
    [clojure.test :refer :all]))

(defn- seed-rest [conn]
  (a/create-base-user-and-realm))

(specification
  ;; TODO: ^:integration
  "datomic.schema - fetch-schema returns a configuration with"
  (with-db-fixture dbcomp

    (let [c (:connection dbcomp)
          id-map (-> dbcomp :seed-result)
          schema-representations (rest/fetch-schema c)]

      (behavior
        ;; TODO: ^:integration
        "finds entity doc"
        (assertions
          (-> schema-representations :entities :realm :doc)
          => "realm entity doc"))

      (behavior
        ;; TODO: ^:integration
        "finds type db.type/string"
        (assertions
          (-> schema-representations :entities :realm :attributes :realm/realm-name :db/valueType)
          => :db.type/string))

      (behavior
        ;; TODO: ^:integration
        "finds type :db.type/ref"
        (assertions
          (-> schema-representations :entities :realm :attributes :realm/user :db/valueType)
          => :db.type/ref))

      (behavior
        ;; TODO: ^:integration
        "adds foreign-attribute to entity"
        (assertions
          (-> schema-representations :entities :component :attributes :realm/realm-id :db/valueType)
          => :db.type/string))

      (behavior
        ;; TODO: ^:integration
        "adds a second foreign-attribute to entity"
        (assertions
          (-> schema-representations :entities :component :attributes :realm/realm-name :db/valueType)
          => :db.type/string))

      (behavior
        ;; TODO: ^:integration
        "finds :db.cardinality/many on a reference that contains a many relationship"
        (assertions
          (-> schema-representations :entities :realm :attributes :realm/user :db/cardinality)
          => :db.cardinality/many))

      (behavior
        ;; TODO: ^:integration
        "finds :db.cardinality/one on a reference that contains a one relationship"
        (assertions
          (-> schema-representations :entities :user :attributes :user/realm :db/cardinality)
          => :db.cardinality/one))

      (behavior
        ;; TODO: ^:integration
        "finds :db/doc"
        (assertions
          (-> schema-representations :entities :realm :attributes :realm/realm-id :db/doc)
          => "realm-id-doc"))

      (behavior
        ;; TODO: ^:integration
        "will not find :db/doc where none exists"
        (assertions
          (-> schema-representations :entities :subscription :attributes :subscription/name :db/doc)
          => ""))

      (behavior
        ;; TODO: ^:integration
        "will not find :rest/entity-doc where none exists"
        (assertions
          (-> schema-representations :entities :realm :attributes :realm/realm-name :rest/entity-doc)
          => nil))

      (behavior
        ;; TODO: ^:integration
        "finds :constraint/definitive"
        (assertions
          (-> schema-representations :entities :realm :attributes :realm/realm-id :constraint/definitive)
          => true))

      (behavior
        ;; TODO: ^:integration
        "will not find :constraint/definitive"
        (assertions
          (-> schema-representations :entities :realm :attributes :realm/realm-name :constraint/definitive)
          => nil))

      (behavior
        ;; TODO: ^:integration
        "will build definitive list"
        (assertions
          (-> schema-representations :definitive)
          => [:subscription/name :component/name :realm/realm-id :user/user-id]))

      (behavior
        ;; TODO: ^:integration
        "finds :constraint/unpublished"
        (assertions
          (-> schema-representations :entities :user :attributes :user/password :constraint/unpublished)
          => true)))

    :migrations "resources.datomic-schema.rest-schema"
    :seed-fn seed-rest))
