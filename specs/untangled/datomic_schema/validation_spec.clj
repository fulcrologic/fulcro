(ns untangled.datomic-schema.validation-spec
  (:require
    [untangled.datomic-schema.validation :as v]
    [datomic.api :as d]
    [seeddata.auth :as a]
    [untangled.util.seed :as s]
    [untangled.util.fixtures :refer [with-db-fixture]]
    [resources.datomic-schema.validation-schema.initial]
    )
  (:use midje.sweet)
  )

(facts "as-set"
       (fact "converts scalars to singular sets"
             (v/as-set 1) => #{1}
             )
       (fact "converts lists to sets"
             (v/as-set '(1 2 2)) => #{1 2}
             )
       (fact "converts vectors to sets"
             (v/as-set [1 2 2]) => #{1 2}
             )
       (fact "leaves sets as sets"
             (v/as-set #{1 2}) => #{1 2}
             )
       (fact "throws an exception if passed a map"
             (v/as-set {:a 1}) => (throws #"does not work on maps")
             )
       )

(facts "Attribute derivation"
       (against-background
         [
          (around :contents (with-db-fixture dbcomp ?form
                                             :migrations "resources.datomic-schema.validation-schema"
                                             :seed-fn seed-validation))
          (around :facts (let [c (:connection dbcomp)
                               db (d/db c)
                               id-map (-> dbcomp :seed-result)
                               realm-id (:tempid/realm1 id-map)
                               user1id (:tempid/user1 id-map)
                               user2id (:tempid/user2 id-map)
                               compe-id (:tempid/comp-entitlement id-map)
                               prope-id (:tempid/prop-entitlement id-map)
                               ] ?form))
          ]
         (fact :integration "foreign-attributes can find the allowed foreign attributes for an entity type"
               (v/foreign-attributes db :user) => #{:authorization-role/name} ; see schema in initial.clj
               )
         (fact :integration "foreign-attributes accepts a string for kind"
               (v/foreign-attributes db "user") => #{:authorization-role/name}
               )
         (fact :integration "core-attributes can find the normal attributes for an entity type"
               (v/core-attributes db :user) => #{:user/password :user/property-entitlement :user/user-id :user/email
                                                 :user/is-active :user/validation-code
                                                 :user/realm :user/authorization-role}
               )
         (fact :integration "core-attributes accepts a string for kind"
               (v/core-attributes db "user") => #{:user/password :user/property-entitlement :user/user-id :user/email
                                                  :user/is-active :user/validation-code
                                                  :user/realm :user/authorization-role}
               )
         (fact :integration "all-attributes can find all allowed attributes for an entity type including foreign"
               (v/all-attributes db :user) => #{:user/password :user/property-entitlement :user/user-id :user/email
                                                :user/is-active :user/validation-code
                                                :user/realm :user/authorization-role :authorization-role/name}
               )
         (fact :integration "definitive-attributes finds all of the definitive attributes in the schema."
               (v/definitive-attributes db) => #{:user/user-id :realm/account-id}
               )
         (fact :integration "entity-types finds all of the types that a given entity conforms to"
               (let [new-db (:db-after (d/with db [[:db/add user1id :realm/account-id "boo"]]))
                     user-realm (d/entity new-db user1id)]
                 (v/entity-types new-db user-realm) => #{:user :realm}
                 )
               )
         )
       )

(facts :integration "Validation"
       (against-background
         [
          (around :contents (with-db-fixture dbcomp ?form
                                             :migrations "resources.datomic-schema.validation-schema"
                                             :seed-fn seed-validation))
          (around :facts (let [c (:connection dbcomp)
                               db (d/db c)
                               id-map (-> dbcomp :seed-result)
                               realm-id (:tempid/realm1 id-map)
                               user1id (:tempid/user1 id-map)
                               user2id (:tempid/user2 id-map)
                               compe-id (:tempid/comp-entitlement id-map)
                               prope-id (:tempid/prop-entitlement id-map)
                               ] ?form))
          ]

         (fact :integration "reference-constraint-for-attribute"
               (fact :integration "returns nil for non-constrained attributes"
                     (v/reference-constraint-for-attribute db :user/name) => nil
                     )

               (fact :integration "finds constraint data about schema"
                     (v/reference-constraint-for-attribute db :user/property-entitlement) => (contains
                                                                                               {:constraint/references  :entitlement/kind
                                                                                                :constraint/with-values #{:entitlement.kind/property-group
                                                                                                                          :entitlement.kind/property
                                                                                                                          :entitlement.kind/all-properties}
                                                                                                })
                     )

               (fact :integration "finds constraint data even when there are no constrained values "
                     (v/reference-constraint-for-attribute db :realm/subscription) => (contains {:constraint/references :subscription/name})
                     )

               (fact :integration "includes the referencing (source) attribute"
                     (v/reference-constraint-for-attribute db :realm/subscription) => (contains {:constraint/attribute :realm/subscription})
                     )
               )

         (fact :integration "entity-has-attribute? can detect if an entity has an attribute"
               (let [user1-eid (user-entity-id c "user1@example.net")]
                 (v/entity-has-attribute? db user1-eid :user/user-id) => true
                 (v/entity-has-attribute? db user1-eid :realm/account-id) => false
                 )
               )
         (fact :integration "entities-in-tx"
               (fact :integration "finds the resolved temporary entity IDs and real IDs in a transaction"
                     (let [newuser-tempid (d/tempid :db.part/user)
                           datoms [[:db/add newuser-tempid :user/email "sample"] [:db/add user2id :user/email "sample2"]]
                           result (d/with db datoms)
                           newuser-realid (d/resolve-tempid (:db-after result) (:tempids result) newuser-tempid)
                           ]
                       (v/entities-in-tx result true) => (just #{newuser-realid user2id})
                       )
                     )
               (fact :integration "can optionally elide entities that were completely removed"
                     (let [email (:user/email (d/entity db user1id))
                           datoms [[:db.fn/retractEntity prope-id] [:db/retract user1id :user/email email]]
                           result (d/with db datoms)
                           ]
                       (v/entities-in-tx result) => (just #{user1id})
                       (v/entities-in-tx result true) => (just #{user1id prope-id})
                       )
                     )
               (fact :integration "includes entities that were updated because of reference nulling"
                     (let [email (:user/email (d/entity db user1id))
                           datoms [[:db.fn/retractEntity user2id]]
                           result (d/with db datoms)
                           ]
                       (v/entities-in-tx result) => (just #{realm-id}) ; realm is modified because it refs the user
                       (v/entities-in-tx result true) => (just #{user2id realm-id})
                       )
                     )
               )
         (fact :integration "entities-that-reference returns the IDs of entities that reference a given entity"
               (v/entities-that-reference db realm-id) => (just #{user1id user2id})
               )
         (fact :integration "invalid-references"
               (fact :integration "returns nil when all outgoing MANY references are valid"
                     (v/invalid-references db realm-id) => nil
                     )
               (fact :integration "returns nil when all outgoing ONE references are valid"
                     (v/invalid-references db user1id) => nil
                     )
               (fact :integration "returns a list of bad references when outgoing references are incorrect"
                     (let [new-db (-> (d/with db [
                                                  [:db/add user1id :user/realm compe-id]
                                                  [:db/add user2id :user/property-entitlement compe-id]
                                                  ]) :db-after)]
                       (first (v/invalid-references new-db user1id)) => (contains {:target-attr :realm/account-id :reason #"attribute is missing"})
                       (first (v/invalid-references new-db user2id)) => (contains {:target-attr :entitlement/kind :reason #"incorrect value"})
                       )
                     )
               )
         (fact :integration "invalid-attributes"
               (fact :integration "returns nil when the entity contains only valid attributes"
                     (v/invalid-attributes db (d/entity db user1id)) => nil
                     )
               (fact :integration "returns a set of invalid attributes on an entity"
                     (let [new-db (:db-after (d/with db [[:db/add user1id :subscription/name "boo"]]))]
                       (v/invalid-attributes new-db (d/entity new-db user1id)) => #{:subscription/name}
                       )
                     )
               (fact :integration "allows a definitive attribute to extend the allowed attributes"
                     (let [new-db (:db-after (d/with db [[:db/add user1id :realm/account-id "boo"]]))]
                       (v/invalid-attributes new-db (d/entity new-db user1id)) => nil
                       )
                     )
               )
         (fact :integration "validate-transaction"
               (fact :integration "returns true if the transaction is empty"
                     (let [new-db (d/with db [])]
                       (v/validate-transaction new-db) => true
                       )
                     )
               (fact :integration "returns true if the transaction is valid"
                     (let [new-db (d/with db [[:db/add user1id :user/property-entitlement prope-id]])]
                       (v/validate-transaction new-db) => true
                       )
                     )
               (fact :integration
                     "throws an invalid reference exception when the transaction has a bad reference in the main modification"
                     (let [new-db (d/with db [[:db/add user1id :user/property-entitlement compe-id]])]
                       (v/validate-transaction new-db) => (throws Exception #"Invalid References")
                       )
                     )
               (fact :integration
                     "throws an invalid reference exception when the transaction causes an existing reference to become invalid by removing the targeted attribute"
                     (let [new-db (d/with db [[:db/retract realm-id :realm/account-id "realm1"]])]
                       (v/validate-transaction new-db) => (throws Exception #"Invalid References" #(= :realm/account-id (-> % ex-data :problems first :target-attr)))
                       )
                     )
               (fact :integration
                     "throws an invalid reference exception when the transaction causes an existing reference to become invalid by changing the targeted attribute value"
                     (let [valid-db (:db-after (d/with db [[:db/add user1id :user/property-entitlement prope-id]]))
                           new-db (d/with valid-db [[:db/add user1id :user/property-entitlement compe-id]])
                           ]
                       (v/validate-transaction new-db) => (throws Exception #"Invalid References" #(re-find #"incorrect value" (-> % ex-data :problems first :reason)))
                       (v/validate-transaction new-db) => (throws Exception #"Invalid References" #(= :entitlement/kind (-> % ex-data :problems first :target-attr)))
                       )
                     )
               (fact :integration
                     "throws an invalid attribute exception when an entity affected by the transaction ends up with a disallowed attribute"
                     (let [new-db (d/with db [[:db/add user1id :subscription/name "boo"]])]
                       (v/validate-transaction new-db true) => (throws Exception #"Invalid Attribute")
                       )
                     )
               )
         )
       )

;; IMPORTANT NOTE: These NON-integration tests are a bit heavy (as they have to muck about with the internals of the function
;; under test quite a bit); however, they are the only way to prove that both paths of validation (optimistic and
;; pessimistic) are correct.
(facts "vtransact"
       (fact "always validates references and attributes on the peer"
             (v/vtransact ..connection.. []) => anything
             (provided
               (d/db ..connection..) => ..db..
               (d/basis-t ..db..) => 1
               (d/with ..db.. []) => ..result..
               (v/validate-transaction ..result.. true) => anything
               (d/transact ..connection.. anything) => (future [])
               )
             )
       (fact "skips transact when optimistic validation fails"
             @(v/vtransact ..connection.. []) => (throws #"Validation failed")
             (provided
               (d/db ..connection..) => ..db..
               (d/with ..db.. []) => ..result.. :times 2
               (d/basis-t ..db..) => 1
               (d/transact ..connection.. anything) => anything :times 0
               (v/validate-transaction ..result.. true) =throws=> (ex-info "Validation failed" {})
               )
             )
       (fact "optimistically applies changes via the transactor while enforcing version known at peer"
             (let [tx-data [[:db/add 1 :blah 2]]
                   optimistic-version 1
                   ]
               (v/vtransact ..connection.. tx-data) => anything
               (provided
                 (d/db ..connection..) => ..db..
                 (d/basis-t ..db..) => optimistic-version
                 (d/with ..db.. tx-data) => ..result..
                 (v/validate-transaction ..result.. true) => true
                 (d/transact ..connection.. (checker [tx]
                                                     (and
                                                       (= (-> tx first) [:ensure-version optimistic-version])
                                                       (= (-> tx rest) tx-data)
                                                       )
                                                     )) => (future [])
                 )
               )
             )
       (fact "completes if the optimistic update succeeds"
             (let [tx-data [[:db/add 1 :blah 2]]
                   optimistic-version 1
                   transact-result (future [])
                   ]
               (v/vtransact ..connection.. tx-data) => transact-result
               (provided
                 (d/db ..connection..) => ..db..
                 (d/basis-t ..db..) => optimistic-version
                 (d/with ..db.. tx-data) => ..result..
                 (v/validate-transaction ..result.. true) => true
                 (d/transact ..connection.. anything) => transact-result
                 )
               )
             )
       (fact "reverts to a pessimistic application in the transactor if optimistic update fails"
             (let [tx-data [[:db/add 1 :blah 2]]
                   optimistic-version 1
                   tx-result-1 (future (throw ex-info "Bad Version"))
                   tx-result-2 (future [])
                   ]
               (v/vtransact ..connection.. tx-data) => tx-result-2
               (provided
                 (d/db ..connection..) => ..db..
                 (d/basis-t ..db..) => optimistic-version
                 (d/with ..db.. tx-data) => ..result..
                 (v/validate-transaction ..result.. true) => true
                 (d/transact ..connection.. (checker [tx]   ;; first attempt with ensured version fails
                                                     (and
                                                       (= (-> tx first) [:ensure-version optimistic-version])
                                                       (= (-> tx rest) tx-data)
                                                       )
                                                     )) => tx-result-1
                 (d/transact ..connection.. (checker [tx]   ;; second attempt constrained
                                                     (= (-> tx first) [:constrained-transaction tx-data])
                                                     )) => tx-result-2
                 )
               )
             )
       (against-background
         [
          (around :contents (with-db-fixture dbcomp ?form
                                             :migrations "resources.datomic-schema.validation-schema"
                                             :seed-fn seed-validation))
          (around :facts (let [c (:connection dbcomp)
                               db (d/db c)
                               id-map (-> dbcomp :seed-result)
                               realm-id (:tempid/realm1 id-map)
                               user1id (:tempid/user1 id-map)
                               user2id (:tempid/user2 id-map)
                               compe-id (:tempid/comp-entitlement id-map)
                               prope-id (:tempid/prop-entitlement id-map)
                               bad-attr-tx [[:db/add user1id :subscription/name "data"]]
                               ] ?form))
          ]
         (fact :integration "succeeds against a real database"
               (user-entity-id c "user1@example.net") => user1id
               (v/vtransact c [[:db/add user1id :user/email "updated@email.net"]]) => anything
               (user-entity-id c "user1@example.net") => nil
               (user-entity-id c "updated@email.net") => user1id
               )
         (fact :integration "fails against a real database when invalid attribute"
               @(v/vtransact c bad-attr-tx) => (throws #"Invalid Attribute"
                                                       #(contains? (-> % .getCause ex-data :problems) :subscription/name))

               )
         )
       )
