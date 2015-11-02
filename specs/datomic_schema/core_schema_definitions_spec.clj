(ns datomic-schema.core-schema-definitions-spec
  (:require
    [datomic-schema.validation :as v]
    [datomic.api :as d]
    [seeddata.auth :as a]
    [untangled.util.seed :as s]
    [untangled.util.fixtures :refer [with-db-fixture]]
    [resources.datomic-schema.validation-schema.initial]
    )
  (:use midje.sweet)
  )

(defn- user-entity-id [conn email]
  (d/q '[:find ?e . :in $ ?v :where [?e :user/email ?v]] (d/db conn) email))

(defn- seed-validation [conn]
  (let [entities (a/create-base-user-and-realm)]
    (s/link-and-load-seed-data conn entities)
    )
  )

;; HACK!!!!!
#_(facts :integration "ensure-version Datomic function"
       (against-background
         [
          (around :facts (with-db-fixture dbcomp ?form
                                          :migrations "resources.datomic-schema.validation-schema"
                                          :seed-fn seed-validation))
          (around :facts (let [c (:connection dbcomp)
                               db (d/db c)
                               id-map (-> dbcomp :seed-result)
                               realm-id (:tempid/realm1 id-map)
                               user1id (:tempid/user1 id-map)
                               user2id (:tempid/user2 id-map)
                               ] ?form))
          ]
         (fact :integration "allows a transaction to run if the version of the database is unchanged"
               (let [t1 (d/basis-t db)]
                 (user-entity-id c "user1@example.net") => user1id
                 (d/transact c [[:ensure-version t1] [:db/add user1id :user/email "updated@email.net"]]) => anything
                 (user-entity-id c "user1@example.net") => nil
                 (user-entity-id c "updated@email.net") => user1id
                 )
               )
         (fact :integration "prevents a transaction from running if the version of the database has changed"
               (user-entity-id c "user1@example.net") => user1id
               (let [t1 (d/basis-t db)
                     db2 @(d/transact c [[:db/add user1id :user/email "updated@email.com"]])
                     ]
                 @(d/transact c [[:ensure-version t1] [:db/add user1id :user/email "updated@email.net"]]) => (throws Exception #"does not match")
                 (user-entity-id c "updated@email.net") => nil
                 (user-entity-id c "updated@email.com") => user1id
                 )
               )
         )
       )


;; HACK!!!!!
#_(facts :integration "constrained-transaction Datomic function"
       (against-background
         [
          (around :facts (with-db-fixture dbcomp ?form
                                          :migrations "resources.datomic-schema.validation-schema"
                                          :seed-fn seed-validation))
          (around :facts (let [c (:connection dbcomp)
                               db (d/db c)
                               id-map (-> dbcomp :seed-result)
                               realm-id (:tempid/realm1 id-map)
                               user1id (:tempid/user1 id-map)
                               user2id (:tempid/user2 id-map)
                               ] ?form))
          ]
         (fact "calls validate-transaction WITHOUT attribute check"
               (let [tx-data [:db/add user1id :user/email "updated@email.net"]]
                 (d/transact c [[:constrained-transaction tx-data]]) => anything
                 (provided
                   (d/with anything tx-data) => ..tx-result..
                   (v/validate-transaction ..tx-result.. false) => anything
                   )
                 )
               )
         (fact :integration "prevents invalid transactions from running"
               (let [tx-data [[:db/add realm-id :realm/subscription user1id]] ]
                 @(d/transact c [[:constrained-transaction tx-data]]) => (throws Exception #"Invalid References")
                 )
               )
         (fact :integration "allows valid transactions to run"
               (let [tx-data [[:db/add user1id :user/email "updated@email.net"]] ]
                 (user-entity-id c "user1@example.net") => user1id
                 (d/transact c [[:constrained-transaction tx-data]]) => anything
                 (user-entity-id c "user1@example.net") => nil
                 (user-entity-id c "updated@email.net") => user1id
                 )
               )
         )
       )

