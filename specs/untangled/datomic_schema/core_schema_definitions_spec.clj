(ns untangled.datomic-schema.core-schema-definitions-spec
  (:require
    [untangled.datomic-schema.validation :as v]
    [datomic.api :as d]
    [seeddata.auth :as a]
    [untangled.util.seed :as s]
    [untangled.util.fixtures :refer [with-db-fixture]]
    [resources.datomic-schema.validation-schema.initial]
    [untangled-spec.core :refer [specification
                                 assertions
                                 when-mocking
                                 component
                                 behavior]]
    [clojure.test :refer :all])
  (:import
    (java.util.concurrent ExecutionException)))

(defn- user-entity-id [conn email]
  (d/q '[:find ?e .
         :in $ ?v
         :where [?e :user/email ?v]]
    (d/db conn) email))

(defn- seed-validation [conn]
  (let [entities (a/create-base-user-and-realm)]
    (s/link-and-load-seed-data conn entities)))

(def ^:private always
  (constantly true?))

(def ^:private anything
  true)

(specification
  ;; TODO:  ^:integration
  "ensure-version Datomic function"

  (with-db-fixture dbcomp

    (let [c        (:connection dbcomp)
          db       (d/db c)
          id-map   (-> dbcomp :seed-result)
          realm-id (:tempid/realm1 id-map)
          user1id  (:tempid/user1 id-map)
          user2id  (:tempid/user2 id-map)]

      (behavior
        ;; TODO: ^:integration
        "allows a transaction to run if the version of the database is unchanged"

        (let [t1 (d/basis-t db)]
          (assertions
            (user-entity-id c "user1@example.net") => user1id
            (d/transact c [[:ensure-version t1] [:db/add user1id :user/email "updated@email.net"]]) =fn=> always
            (user-entity-id c "user1@example.net") => nil
            (user-entity-id c "updated@email.net") => user1id)
          ;; "Undo"
          (d/transact c [[:db/add user1id :user/email "user1@example.net"]])))

      (behavior
        ;; TODO: ^:integration
        "prevents a transaction from running if the version of the database has changed"
        (assertions
          (user-entity-id c "user1@example.net") => user1id)

        (let [t1  (d/basis-t db)
              db2 @(d/transact c [[:db/add user1id :user/email "updated@email.com"]])]
          (assertions
            @(d/transact c [[:ensure-version t1] [:db/add user1id :user/email "updated@email.net"]])
            =throws=> (ExecutionException #"does not match")
            (user-entity-id c "updated@email.net") => nil
            (user-entity-id c "updated@email.com") => user1id))))

    :migrations "resources.datomic-schema.validation-schema"
    :seed-fn seed-validation))

(specification
  ;; TODO: ^:integration
  "constrained-transaction Datomic function"
  (with-db-fixture dbcomp

    (let [c        (:connection dbcomp)
          db       (d/db c)
          id-map   (-> dbcomp :seed-result)
          realm-id (:tempid/realm1 id-map)
          user1id  (:tempid/user1 id-map)
          user2id  (:tempid/user2 id-map)]

      (behavior "calls validate-transaction WITHOUT attribute check"
        (let [tx-data [:db/add user1id :user/email "updated@email.net"]]
          (when-mocking
            (d/with anything tx-data) => :..tx-result..
            (v/validate-transaction :..tx-result.. false) => anything

            (assertions
              (d/transact c [[:constrained-transaction tx-data]]) =fn=> identity))
          ;; "Undo"
          (d/transact c [[:db/add user1id :user/email "user1@example.net"]])))

      (behavior
        ;; TODO: ^:integration
        "prevents invalid transactions from running"
        (let [tx-data [[:db/add realm-id :realm/subscription user1id]]]
          (assertions
            @(d/transact c [[:constrained-transaction tx-data]])
            =throws=> (ExecutionException #"Invalid References"))))

      (behavior
        ;; TODO: ^:integration
        "allows valid transactions to run"
        (let [tx-data [[:db/add user1id :user/email "updated@email.net"]]]
          (assertions
            (user-entity-id c "user1@example.net") => user1id
            (d/transact c [[:constrained-transaction tx-data]]) =fn=> identity
            (user-entity-id c "user1@example.net") => nil
            (user-entity-id c "updated@email.net") => user1id))
        ;; "Undo"
        (d/transact c [[:db/add user1id :user/email "user1@example.net"]])))

    :migrations "resources.datomic-schema.validation-schema"
    :seed-fn seed-validation))
