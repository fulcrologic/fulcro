(ns untangled.server.impl.database.validation
  (:require
    [clojure.set :as s]
    [datomic.api :as d]
    )
  )

; # Custom Validation of Datomic Transactions
;
; Datomic itself supports the following schema validations out of the box:
;
; * Correct field type
; * References (the only ON DELETE behavior is nullify)
;
; Our schema support adds the following (opt-in) validation enforcement:
;
; * Entities have a more definitive "kind", with a limited set of attributes defined by the schema.
; * References point to the right "kind" of entity. Normally, Datomic types are advisory (any attribute can go on
;   any entity. To enforce this we need to have a way to determine the "type" of an entity.
;
; The functions in this namespace implement these additions.
;
; The "implied" kind of an entity is the common namespace of a set of attributes (e.g. user). Since some
; arbitrary entity will have at least one of those properties when it is behaving as a user, we can
; derive the "kind" of an entity if we also declare which properties (e.g. :user/name) will *always*
; be on a "User". We call these attributes "definitive" (their presences define the "type" of the entity).
;
; Note that if you allow a foreign attribute  on an entity (e.g. :account/name on "user") and that
; foreign attribute is also definitive, then by placing both on an entity you're conferring that additional
; "type" on that entity (it is now an "account" and a "user"). This has additional validation implications
; (e.g. it can now be referred to by things that can refer to an account and a user). Conversely, removing
; such an attribute could cause validation to fail if such a removal would remove a "type" that other
; attributes or references rely on.
;
; ## Basic Operation
;
; The validation MUST be ACID compliant. Since the transactor is the only place that true ACID operations are possible,
; we must technically do some part of the validation there to ensure correctness. However, since the Datomic
; transactor is a limited resource (it is the single write thread for the entire database), we want to limit the
; overhead, and therefore adopt the following scheme for doing transactions:
;
; * First, do the full validation (correct attribute values, referential constraints correct) on the client. A
;   failure indicates no need to even *try* the real transaction. This also has the effect of pre-caching all the
;   relevant data in the peer.
; * Find the current database basis (transaction number)
; * Repeat the validation on the client/peer. The pre-cached data will make this attempt happen much more quickly than
; the first.
; * Attempt to apply a transaction *without validation*, but include a call to a database function the will throw an
; exception if the real database basis (transaction number) is different than what was read in step 2. If the transaction
; succeeds, then all is well since the database had not changed since the client validation.
; * IF the transaction fails, the database may be under too high of a write load for the optimistic approach above to
; succeed. So, instead apply the REFERENCE validations in the transactor. The attribute validations were already done
; on the client, so there is no need to repeat them in the transactor.
;
; This overall scheme is implemented by `vtransact`, which works the same as Datomic's `transact`, but does the validations
; as described above using additional data you associate with the schema.
;
; The transactor validations use support functions from this namespace, so this namespace MUST be on the transactor's
; CLASSPATH.
;
; Additionally, there are database functions you must install into your schema. See
; `untangled.server.impl.database.core-schema-definitions` for the code that installs the two transactor functions `ensure-version`
; and `constrained-transaction`.

(defn entity-has-attribute?
  "Returns true if the given entity has the given attribute in the given database.

  Parameters:
  `db` - The database to look the entity up in
  `entity-or-id` - The entity or an ID. If an entity, the db is NOT queried.
  `attr` - The attr to check

  Thus, if you pass an entity, then this function is identical to (contains? entity-or-id attr)
  "
  [db entity-or-id attr]
  (let [eid (or (:db/id entity-or-id) entity-or-id)
        result (d/q '[:find ?v .
                      :in $ ?e ?attr
                      :where [?e ?attr ?v]] db eid attr)]
    (boolean result)
    )
  )

(defn foreign-attributes
  "Get a list of the attributes that are *not* within the implied `kind` of entity, but which are still allowed to
  exist on that entity. Such attributes **must** be defined in the schema.

  Parameters:
  `db`: The database whose schema is checked
  `kind`: the keyword or string `kind` of entity to check.
  "
  [db kind]
  (set (d/q '[:find [?attr ...] :in $ ?kind
              :where
              [?e :entity/name ?kind]
              [?e :entity/foreign-attribute ?f]
              [?f :db/ident ?attr]] db kind))
  )

(defn core-attributes
  "
  Obtains the list of attributes that are within the namespace of `kind`. These are considered the *core*
   attributes of an entity of that kind. E.g. :user/name has `kind` user.

   Parameters:
   `db`: The database to check
   `kind`: The keyword or string name of the kind to examine.
  "
  [db kind]
  (set (d/q '[:find [?ident ...]
              :in $ ?kind
              :where
              [_ :db/ident ?ident]
              [(namespace ?ident) ?kind]
              ]
            db (name kind)))
  )

(defn all-attributes
  "
  Derives the allowed attributes from the given database for a specific kind of entity.

  Parameters:
  `db` : The database to check
  `kind` : The kind (as a string or keyword)

  Returns a set of namespace-qualified keywords that define the allowed attributes for the given type, including
  foreign attributes.
  "
  [db kind]
  (clojure.set/union (core-attributes db kind) (foreign-attributes db kind))
  )

(defn reference-constraint-for-attribute
  "For a given attribute, finds referential constraints (if a ref, and given).

  Parameters:
  `db` - The database to derive schema from
  `attr` - The attribute of interest

  If found it returns the constraint as a map

      {
        :constraint/attribute  attr ; the attr you passed in
        :constraint/references target
        :constraint/with-values #{allowed-values ...}
      }

  otherwise nil."
  [db attr]
  (let [result (d/q '[:find (pull ?e [:constraint/references :constraint/with-values]) .
                      :in $ ?v
                      :where [?e :db/ident ?v] [?e :db/valueType :db.type/ref]] db attr)]
    (some-> (if (:constraint/with-values result)
              (assoc result :constraint/with-values (set (:constraint/with-values result)))
              result)
            (assoc :constraint/attribute attr)
            )
    )
  )

(defn- ensure-entity
  "Ensure that the specified eid is loaded as an entity.

  Parameters:
  `db` - The database to load from if the eid is NOT an entity
  `eid` - The entity or ID

  Returns the entity from the database, or eid if eid is already an entity.
  "
  [db eid]
  (if (instance? datomic.Entity eid)
    eid
    (d/entity db eid)
    )
  )

(defn entity-exists?
  "
  Query the database to see if the given entity ID exists in the database. This function is necessary because Datomic
  will *always* return an entity from `(datomic.api/entity *id*)`, which means you cannot test existence that way.

  Parameters:
  `conn-or-db` : A datomic connection *or* database. This allows the function to work on whichever is more convenient.
  `entity-id` : The numeric ID of the entity to test.
  "
  [conn-or-db entity-id]
  (let [db (if (= datomic.db.Db (type conn-or-db)) conn-or-db (d/db conn-or-db))]
    (-> (d/q '[:find ?eid :in $ ?eid :where [?eid]] db entity-id) seq boolean)
    )
  )

(defn entities-in-tx
  "Returns a set of entity IDs that were modified in the given transaction.

  Parameters:
  `tx-result` : The result of the transaction that was run (e.g. @(d/transact ...))
  "
  ([tx-result] (entities-in-tx tx-result false))
  ([tx-result include-deleted]
   (let [db (:db-after tx-result)
         tx (:tx-data tx-result)
         ids (set (->> tx (filter #(not= (.e %) (.tx %))) (map #(.e %))))
         ]
     (if include-deleted
       ids
       (set (filter #(entity-exists? db %) ids))
       )
     ))
  )

(defn entities-that-reference
  "Finds all of the entities that reference the given entity.

  Parameters:
  `db` : The database
  `e`  : The entity (or entity ID) that is referenced
  "
  [db e]
  (let [eid (if (instance? datomic.Entity e) (:db/id e) e)]
    (d/q '[:find [?e ...] :in $ ?eid :where [?e _ ?eid]] db eid)
    )
  )

(defn as-set
  "A quick helper function that ensures the given collection (or singular item) is a set.

  Parameters:
  `v`: A scalar, sequence, or set.

  Returns a set.
  "
  [v]
  (assert (not (map? v)) "as-set does not work on maps")
  (cond
    (seq? v) (set v)
    (vector? v) (set v)
    (set? v) v
    :else #{v}
    ))

(defn- is-reference-attribute-valid?
  "
  Determine, given the constraints on an attribute (which includes the targeted entity(ies)), if the given entity has
  a valid value for that attribute.

  Parameters:
  `db` - The database to validate against
  `entity` - The entity (or eid) to validate
  `constraint` - The database constraint to validate (as returned by reference-constraint-for-attribute)
  "
  [db entity constraint]
  (let [entity (ensure-entity db entity)
        source-attr (:constraint/attribute constraint)
        targetids (as-set (source-attr entity))
        target-attr (:constraint/references constraint)
        allowed-values (:constraint/with-values constraint)
        value-incorrect? (fn [entity attr allowed-values]
                           (let [value (attr entity)]
                             (not ((set allowed-values) value))
                             )
                           )
        error-msg (fn [msg entity attr target] {:source entity :reason msg :target-attr attr :target target})
        ]
    (some #(cond
            (not (entity-has-attribute? db % target-attr)) (error-msg "Target attribute is missing" entity target-attr %)
            (and allowed-values
                 (value-incorrect? % target-attr allowed-values)) (error-msg "Target attribute has incorrect value"
                                                                             entity target-attr %)
            :else false)
          targetids)
    )
  )

(defn invalid-references
  "
  Returns all of the references *out* of the given entity that are invalid according to the schema's constraints. Returns
  nil if all references are valid.

  Parameters:
  `db` : The database that defines the schema
  `e` : The entity or entity ID to check
  "
  [db e]
  (let [entity (ensure-entity db e)
        attributes (keys entity)
        get-constraint (fn [attr] (reference-constraint-for-attribute db attr))
        drop-empty (partial filter identity)
        constraints-to-check (drop-empty (map get-constraint attributes))
        check-attribute (partial is-reference-attribute-valid? db entity)
        problems (drop-empty (map check-attribute constraints-to-check))
        ]
    (if (empty? problems) nil problems)
    ))



(defn definitive-attributes
  "
  Returns a set of attributes (e.g. :user/email) that define the 'kinds' of entities in the specified database.
  When such an attribute appears on an entity, it implies that entity is allowed to be treated as-if it has
  the 'kind' of that attribute's namespace (e.g. the presence of :user/email on an entity implies that
  the entity has kind 'user').

  Such attributes are marked as :definitive in the schema.
  "
  [db]
  (set (map :db/ident (d/q '[:find [(pull ?e [:db/ident]) ...]
                             :where
                             [?e :db/valueType _]
                             [?e :constraint/definitive true]
                             ] db)))
  )

(defn entity-types
  "Given a db and an entity, this function determines all of the types that the given entity conforms to (by scanning
  for attributes on the entity that have the :definitive markers in the schema.

  Parameters:
  `db` - The database (schema) to check against
  `e` - The Entity (or ID) to check
  "
  [db e] []
  (let [entity (ensure-entity db e)
        attrs (set (keys entity))
        type-markers (definitive-attributes db)
        type-keywords (s/intersection attrs type-markers)
        type-of (comp keyword namespace)
        ]
    (->> type-keywords (map type-of) (set))
    )
  )

(defn invalid-attributes
  "Check the given entity against the given database schema and determine if it has attributes that are not allowed
  according to the schema.

  Parameters:
  `db` : The database
  `entity` : The entity

  Returns nil if there are only valid attributes; otherwise returns a set of attributes that are present
  but are not allowed by the schema.
  "
  [db entity]
  (let [types (entity-types db entity)
        valid-attrs (set (mapcat (partial all-attributes db) types))
        current-attrs (set (keys entity))
        invalid-attrs (s/difference current-attrs valid-attrs)
        ]
    (if (empty? invalid-attrs) nil invalid-attrs)
    )
  )

(defn validate-transaction
  "
  Examines a transaction result to determine if the changes are valid. Throws exceptions if there are problems.

  ## Validation Algorithm:

  1. Find all of the entitied that were modified.
  - Entities whose attribute set changed
  - Entities that were updated due to reference 'nulling'
  2. Ignore any entities that were removed
  3. For every modified entity E:
  - Find all of the entities R that refer to E and verify that the references R->E are still valid
  - Verify that all references out of E are still valid
  - Derive the types T using definitive-attributes for E
  - Find all valid attributes A for T using schema
  - Verify that E contains only those attributes in A

  Parameters:
  `tx-result` - The return value of datomic `transact`, `with`, or similar function.
  `validate-attributes?` - Should the attributes be checkd? Defaults to false. Do NOT do in transactor, since it is costly.

  This function returns true if the changes are valid; otherwise it throws exceptions. When run within the transactor
  an exception will cause the transacation to abort, and this function is used by the client **and** the transactor
  db function.

  The thrown transaction will include the message 'Invalid References' if the transaction is invalid due to referential
  constraints. The message will include 'Invalid Attributes' if the transaction is invalid due to attributes on
  entities that are not allowed (e.g. a realm attribute on a user type).
  "
  ([tx-result] (validate-transaction tx-result false))
  ([tx-result validate-attributes?]
   (let [db (:db-after tx-result)
         modified-entities (entities-in-tx tx-result)       ;; 1 & 2 all except removed
         referencing-entities (fn [e] (entities-that-reference db e))
         ]
     (doseq [E modified-entities]
       (let [E (ensure-entity db E)]
         (if-let [bad-attrs (and validate-attributes? (invalid-attributes db E))]
           (throw (ex-info "Invalid Attributes" {:problems bad-attrs})))
         (if-let [bad-refs (invalid-references db E)] (throw (ex-info "Invalid References" {:problems bad-refs})))
         (doseq [R (referencing-entities E)]
           (if-let [bad-refs (invalid-references db R)] (throw (ex-info "Invalid References" {:problems bad-refs})))
           )
         )
       )
     true
     )
    )
  )

(defn vtransact
  "
  Execute a transaction in datomic while enforcing schema validations. The internal algorithm of this function
  attempts full validation on the peer (client). If that validation succeeds, then it attempts to run the same
  transaction on the transactor but only if the version of the database has not changed (optimistic concurrency).

  If the database *has* changed, then this function requests that the transactor do just the reference validations (the
  earlier validation will be ok for 'allowed attributes').

  The supporting transactor functions are in core_schema_definitions.clj.

  This function returns exactly what datomic.api/transact does, or throws an exception if the transaction cannot
  be applied.
  "
  [connection tx-data]
  (let [db-ignored (d/db connection)
        _ (d/with db-ignored tx-data) ;; this is a pre-caching attempt
        db (d/db connection)
        version (d/basis-t db)
        optimistic-result (d/with db tx-data) ; the 'real' attempt
        version-enforced-tx (cons [:ensure-version version] tx-data)
        reference-checked-tx [[:constrained-transaction tx-data]]
        ]
    (try
      (validate-transaction optimistic-result true)         ;; throws if something is wrong
      (try
        (let [result (d/transact connection version-enforced-tx)]
          @result                                           ;; evaluate the future...will throw if there was a problem
          result
          )
        (catch Exception e
          (d/transact connection reference-checked-tx)
          )
        )
      (catch Exception e
        (future (throw e))
        )
      )
    )
  )
