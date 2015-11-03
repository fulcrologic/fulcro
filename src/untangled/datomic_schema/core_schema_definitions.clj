(ns untangled.datomic-schema.core-schema-definitions
  (:require [datomic-schema.schema :as s]
            [datomic.api :as d]
            [taoensso.timbre :refer [debug info fatal error]]
            [io.rkn.conformity :as c]
            [datomic-schema.validation :as v])
  )

;; defines the constraints schema and ensures that the database has these defined
(defn ensure-constraints-conform [conn]
  (let [schema (s/generate-schema
                 [
                  (s/schema constraint
                            (s/fields
                              [references :keyword]
                              [with-values :keyword :many]
                              [definitive :boolean]
                              [unpublished :boolean]
                              ))
                  ;; Database function which will throw an exception if the given argument does not match the
                  ;; current database's tranasction number (for optimistic concurrency control on validations)
                  (s/dbfn ensure-version [db version] :db.part/user
                          (if (not= version (datomic.api/basis-t db))
                            (throw (ex-info "Transactor version of database does not match required version" {}))
                            ))
                  ;; Database function that does referential integrity checks within the transactor. Pass
                  ;; the transaction data to this function.
                  (s/with-require [[datomic-schema.validation :as v]]
                                  (s/dbfn constrained-transaction [db transaction] :db.part/user
                                          (let [result (d/with db transaction)]
                                            (datomic-schema.validation/validate-transaction result false)
                                            transaction
                                            )
                                          )
                                  )

                  ])
        norms-map {:datahub/constraint-schema {:txes (vector schema)}}]
    (doseq []
      (c/ensure-conforms conn norms-map [:datahub/constraint-schema])
      (if (c/conforms-to? (d/db conn) :datahub/constraint-schema)
        (info "Verified that database conforms to constraints")
        (error "Database does NOT conform to contstraints")
        )
      )
    )
  )


;; defines the constraints schema and ensures that the database has these defined
(defn ensure-entities-conform [conn]
  (let [schema (s/generate-schema
                 [
                  (s/schema entity
                            (s/fields
                              [name :keyword :unique-identity]
                              [doc :string]
                              [foreign-attribute :ref :many]
                              ))
                  ])
        norms-map {:datahub/entity-schema {:txes (vector schema)}}]
    (doseq []
      (c/ensure-conforms conn norms-map [:datahub/entity-schema])
      (if (c/conforms-to? (d/db conn) :datahub/entity-schema)
        (info "Verified that database conforms to entities")
        (error "Database does NOT conform to entities")
        )
      )
    )
  )

