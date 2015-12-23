(ns untangled.datomic-schema.validation-spec
  (:require
    [untangled.datomic-schema.validation :as v]
    [clojure.test :refer :all]
    [untangled-spec.core :refer [specification assertions when-mocking component behavior]]
    [datomic.api :as d]
    [seeddata.auth :as a]
    [untangled.util.seed :as s]
    [untangled.util.fixtures :refer [with-db-fixture]]
    [resources.datomic-schema.validation-schema.initial]
    )
  (:import (clojure.lang ExceptionInfo)
           (java.util.concurrent ExecutionException)))

(defn- user-entity-id [conn email]
  (d/q '[:find ?e . :in $ ?v :where [?e :user/email ?v]] (d/db conn) email))

(defn- seed-validation [conn]
  (let [entities (concat
                   (a/create-base-user-and-realm)
                   [[:db/add :tempid/user1 :user/realm :tempid/realm1] [:db/add :tempid/user2 :user/realm :tempid/realm1]]
                   [(s/generate-entity {:db/id            :tempid/prop-entitlement
                                        :entitlement/kind :entitlement.kind/property
                                        })
                    (s/generate-entity {:db/id            :tempid/comp-entitlement
                                        :entitlement/kind :entitlement.kind/component
                                        })])]
    (s/link-and-load-seed-data conn entities)))

(specification "as-set"
  (behavior "converts scalars to singular sets"
    (assertions
      (v/as-set 1) => #{1}))
  (behavior "converts lists to sets"
    (assertions
      (v/as-set '(1 2 2)) => #{1 2}))
  (behavior "converts vectors to sets"
    (assertions
      (v/as-set [1 2 2]) => #{1 2}))
  (behavior "leaves sets as sets"
    (assertions
      (v/as-set #{1 2}) => #{1 2}))
  (behavior "throws an exception if passed a map"
    (assertions
      (v/as-set {:a 1}) =throws=> (AssertionError #"does not work on maps"))))

(specification "Attribute derivation"
  (with-db-fixture dbcomp
    (let [c        (:connection dbcomp)
          db       (d/db c)
          id-map   (-> dbcomp :seed-result)
          realm-id (:tempid/realm1 id-map)
          user1id  (:tempid/user1 id-map)
          user2id  (:tempid/user2 id-map)
          compe-id (:tempid/comp-entitlement id-map)
          prope-id (:tempid/prop-entitlement id-map)]
      (behavior "foreign-attributes can find the allowed foreign attributes for an entity type"
        (assertions
          (v/foreign-attributes db :user) => #{:authorization-role/name}) ; see schema in initial.clj )

        (behavior "foreign-attributes accepts a string for kind"
          (assertions
            (v/foreign-attributes db "user") => #{:authorization-role/name}))

        (behavior "core-attributes can find the normal attributes for an entity type"
          (assertions
            (v/core-attributes db :user) => #{:user/password :user/property-entitlement :user/user-id :user/email
                                              :user/is-active :user/validation-code
                                              :user/realm :user/authorization-role}))

        (behavior "core-attributes accepts a string for kind"
          (assertions
            (v/core-attributes db "user") => #{:user/password :user/property-entitlement :user/user-id :user/email
                                               :user/is-active :user/validation-code
                                               :user/realm :user/authorization-role}))

        (behavior "all-attributes can find all allowed attributes for an entity type including foreign"
          (assertions
            (v/all-attributes db :user) => #{:user/password :user/property-entitlement :user/user-id :user/email
                                             :user/is-active :user/validation-code
                                             :user/realm :user/authorization-role :authorization-role/name}))

        (behavior "definitive-attributes finds all of the definitive attributes in the schema."
          (assertions
            (v/definitive-attributes db) => #{:user/user-id :realm/account-id}))

        (behavior "entity-types finds all of the types that a given entity conforms to"
          (let [new-db     (:db-after (d/with db [[:db/add user1id :realm/account-id "boo"]]))
                user-realm (d/entity new-db user1id)]
            (assertions
              (v/entity-types new-db user-realm) => #{:user :realm})))))

    :migrations "resources.datomic-schema.validation-schema"
    :seed-fn seed-validation))

(specification "Validation"
  (with-db-fixture dbcomp
    (let [c        (:connection dbcomp)
          db       (d/db c)
          id-map   (-> dbcomp :seed-result)
          realm-id (:tempid/realm1 id-map)
          user1id  (:tempid/user1 id-map)
          user2id  (:tempid/user2 id-map)
          compe-id (:tempid/comp-entitlement id-map)
          prope-id (:tempid/prop-entitlement id-map)]
      (component "reference-constraint-for-attribute"
        (behavior "returns nil for non-constrained attributes"
          (assertions
            (v/reference-constraint-for-attribute db :user/name) => nil))

        (behavior "finds constraint data about schema"
          (assertions
            (select-keys (v/reference-constraint-for-attribute db :user/property-entitlement) [:constraint/references :constraint/with-values])
            => {:constraint/references  :entitlement/kind
                :constraint/with-values #{:entitlement.kind/property-group
                                          :entitlement.kind/property
                                          :entitlement.kind/all-properties}}))

        (behavior "finds constraint data even when there are no constrained values "
          (assertions
            (:constraint/references (v/reference-constraint-for-attribute db :realm/subscription)) => :subscription/name))

        (behavior "includes the referencing (source) attribute"
          (assertions
            (:constraint/attribute (v/reference-constraint-for-attribute db :realm/subscription)) => :realm/subscription)))

      (behavior "entity-has-attribute? can detect if an entity has an attribute"
        (when-mocking
          (d/db c) => db
          (let [user1-eid (user-entity-id c "user1@example.net")]
            (assertions
              (v/entity-has-attribute? db user1-eid :user/user-id) => true
              (v/entity-has-attribute? db user1-eid :realm/account-id) => false))))

      (component "entities-in-tx"
        (behavior "finds the resolved temporary entity IDs and real IDs in a transaction"
          (let [newuser-tempid (d/tempid :db.part/user)
                datoms         [[:db/add newuser-tempid :user/email "sample"] [:db/add user2id :user/email "sample2"]]
                result         (d/with db datoms)
                newuser-realid (d/resolve-tempid (:db-after result) (:tempids result) newuser-tempid)]
            (assertions
              (v/entities-in-tx result true) => #{newuser-realid user2id})))

        (behavior "can optionally elide entities that were completely removed"
          (let [email  (:user/email (d/entity db user1id))
                datoms [[:db.fn/retractEntity prope-id] [:db/retract user1id :user/email email]]
                result (d/with db datoms)]
            (assertions
              (v/entities-in-tx result) => #{user1id}
              (v/entities-in-tx result true) => #{user1id prope-id})))

        (behavior "includes entities that were updated because of reference nulling"
          (let [datoms [[:db.fn/retractEntity user2id]]
                result (d/with db datoms)]
            (assertions
              (v/entities-in-tx result) => #{realm-id}      ; realm is modified because it refs the user
              (v/entities-in-tx result true) => #{user2id realm-id}))))

      (behavior "entities-that-reference returns the IDs of entities that reference a given entity"
        (assertions
          (into #{} (v/entities-that-reference db realm-id)) => #{user1id user2id}))

      (component "invalid-references"
        (behavior "returns nil when all outgoing MANY references are valid"
          (assertions
            (v/invalid-references db realm-id) => nil))

        (behavior "returns nil when all outgoing ONE references are valid"
          (assertions
            (v/invalid-references db user1id) => nil))

        (behavior "returns a list of bad references when outgoing references are incorrect"
          (let [new-db (-> (d/with db [[:db/add user1id :user/realm compe-id]
                                       [:db/add user2id :user/property-entitlement compe-id]])
                         :db-after)]
            (assertions
              (select-keys (first (v/invalid-references new-db user1id)) [:target-attr :reason])
              => {:target-attr :realm/account-id :reason "Target attribute is missing"}

              (select-keys (first (v/invalid-references new-db user2id)) [:target-attr :reason])
              => {:target-attr :entitlement/kind :reason "Target attribute has incorrect value"}))))

      (component "invalid-attributes"
        (behavior "returns nil when the entity contains only valid attributes"
          (assertions
            (v/invalid-attributes db (d/entity db user1id)) => nil))

        (behavior "returns a set of invalid attributes on an entity"
          (let [new-db (:db-after (d/with db [[:db/add user1id :subscription/name "boo"]]))]
            (assertions
              (v/invalid-attributes new-db (d/entity new-db user1id)) => #{:subscription/name})))

        (behavior "allows a definitive attribute to extend the allowed attributes"
          (let [new-db (:db-after (d/with db [[:db/add user1id :realm/account-id "boo"]]))]
            (assertions
              (v/invalid-attributes new-db (d/entity new-db user1id)) => nil))))

      (component "validate-transaction"
        (behavior "returns true if the transaction is empty"
          (let [new-db (d/with db [])]
            (assertions
              (v/validate-transaction new-db) => true)))

        (behavior "returns true if the transaction is valid"
          (let [new-db (d/with db [[:db/add user1id :user/property-entitlement prope-id]])]
            (assertions
              (v/validate-transaction new-db) => true)))

        (behavior
          "throws an invalid reference exception when the transaction has a bad reference in the main modification"
          (let [new-db (d/with db [[:db/add user1id :user/property-entitlement compe-id]])]
            (assertions
              (v/validate-transaction new-db) =throws=> (ExceptionInfo #"Invalid References"))))

        (behavior
          "throws an invalid reference exception when the transaction causes an existing reference to become invalid by removing the targeted attribute"
          (let [new-db (d/with db [[:db/retract realm-id :realm/account-id "realm1"]])]
            (assertions
              (v/validate-transaction new-db) =throws=> (ExceptionInfo #"Invalid References" #(= :realm/account-id (-> % ex-data :problems first :target-attr))))))

        (behavior
          "throws an invalid reference exception when the transaction causes an existing reference to become invalid by changing the targeted attribute value"
          (let [valid-db (:db-after (d/with db [[:db/add user1id :user/property-entitlement prope-id]]))
                new-db   (d/with valid-db [[:db/add user1id :user/property-entitlement compe-id]])]
            (assertions
              (v/validate-transaction new-db) =throws=> (ExceptionInfo #"Invalid References" #(re-find #"incorrect value" (-> % ex-data :problems first :reason)))
              (v/validate-transaction new-db) =throws=> (ExceptionInfo #"Invalid References" #(= :entitlement/kind (-> % ex-data :problems first :target-attr))))))

        (behavior
          "throws an invalid attribute exception when an entity affected by the transaction ends up with a disallowed attribute"
          (let [new-db (d/with db [[:db/add user1id :subscription/name "boo"]])]
            (assertions
              (v/validate-transaction new-db true) =throws=> (ExceptionInfo #"Invalid Attribute"))))))

    :migrations "resources.datomic-schema.validation-schema"
    :seed-fn seed-validation))

;; IMPORTANT NOTE: These NON-integration tests are a bit heavy (as they have to muck about with the internals of the function
;; under test quite a bit); however, they are the only way to prove that both paths of validation (optimistic and
;; pessimistic) are correct.
(specification "vtransact"
  (behavior "always validates references and attributes on the peer"
    (when-mocking
      (d/db _) => "conn"
      (d/basis-t _) => 1
      (d/with _ _) => true
      (v/validate-transaction _ true) => true
      (d/transact _ _) => (future [])
      (assertions
        (future? (v/vtransact "conn" [])) => true)))

  (behavior "skips transact when optimistic validation fails"
    (when-mocking
      (d/db _) =2x=> "db"
      (d/with _ []) =2x=> {}
      (d/basis-t _) => 1
      (v/validate-transaction {} true) =1x=> (throw (ex-info "Validation failed" {}))
      (assertions
        @(v/vtransact "conn" []) =throws=> (ExecutionException #"Validation failed"))))

  (behavior "optimistically applies changes via the transactor while enforcing version known at peer"
    (let [tx-data            [[:db/add 1 :blah 2]]
          optimistic-version 1]
      (when-mocking
        (d/db _) => "db"
        (d/basis-t _) => optimistic-version
        (d/with _ tx-data) => :anything
        (v/validate-transaction _ true) => true
        (d/transact conn tx) => (do
                                  (is (= (-> tx first) [:ensure-version optimistic-version]))
                                  (is (= (-> tx rest) tx-data))
                                  (future []))
        (v/vtransact :connection tx-data))))

  (behavior "completes if the optimistic update succeeds"
    (let [tx-data            [[:db/add 1 :blah 2]]
          optimistic-version 1
          transact-result    (future [])]
      (when-mocking
        (d/db _) => "db"
        (d/basis-t _) => optimistic-version
        (d/with _ tx-data) => "..result.."
        (v/validate-transaction _ true) => true
        (d/transact _ anything) => transact-result
        (assertions (v/vtransact "connection" tx-data) => transact-result))))

  (behavior "reverts to a pessimistic application in the transactor if optimistic update fails"
    (let [tx-data            [[:db/add 1 :blah 2]]
          optimistic-version 1
          tx-result-1        (future (throw ex-info "Bad Version"))
          tx-result-2        (future [])]
      (when-mocking
        (d/db conn) => "db"
        (d/basis-t db) => optimistic-version
        (d/with db tx-data) => "result"
        (v/validate-transaction result true) => true
        (d/transact conn tx) =1x=> (do
                                     (is (= (-> tx first) [:ensure-version optimistic-version])) ;; first attempt with ensured version fails
                                     (is (= (-> tx rest) tx-data))
                                     tx-result-1)
        (d/transact conn tx) =1x=> (do
                                     (is (= (-> tx first) [:constrained-transaction tx-data])) ;; second attempt constrained
                                     tx-result-2)

        (assertions (v/vtransact "connection" tx-data) => tx-result-2))))

  (with-db-fixture dbcomp
    (let [c           (:connection dbcomp)
          db          (d/db c)
          id-map      (-> dbcomp :seed-result)
          user1id     (:tempid/user1 id-map)
          bad-attr-tx [[:db/add user1id :subscription/name "data"]]]

      (behavior "succeeds against a real database"
        (assertions
          (user-entity-id c "user1@example.net") => user1id)
        (v/vtransact c [[:db/add user1id :user/email "updated@email.net"]]) ;; update user email address
        (assertions
          ;; can't find the old one
          (user-entity-id c "user1@example.net") => nil
          ;; CAN find the new one!
          (user-entity-id c "updated@email.net") => user1id))

      (behavior "fails against a real database when invalid attribute"
        (assertions @(v/vtransact c bad-attr-tx) =throws=> (ExecutionException #"Invalid Attribute"
                                                             #(contains? (-> % .getCause ex-data :problems) :subscription/name)))))

    :migrations "resources.datomic-schema.validation-schema"
    :seed-fn seed-validation))
