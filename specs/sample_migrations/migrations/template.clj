;; Name YOUR namespace as follows: datahub.migrations.YYYYMMDDHHmm-description 
;; This means your filename will be: src/datahub/migrations/YYYYMMDDHHmm_description (note the underscore)
(ns sample-migrations.migrations.template
  (:require [untangled.datomic-schema.schema :as s])
  )

;; THIS FILE WILL NEVER RUN. The migration system ignores anything in *.template namespaces.
;; This is simply a sample of the kinds of things you can do in a migration.

;; TODO: Make this support def instead of using defn
(defn transactions []
  [ ;; YOUR transactions function in YOUR namespace MUST return a list of transactions (list of lists)
   ;; transaction 1: transactions themselves are lists of maps (or lists of lists). See Datomic docs.
   [{ 
     :db/index true, 
     :db.install/_attribute :db.part/db, 
     :db/id #db/id[:db.part/db], 
     :db/ident :user/boogey, 
     :db/valueType :db.type/string, 
     :db/cardinality :db.cardinality/one
     }
    {
     :db/index true, 
     :db.install/_attribute :db.part/db, 
     :db/id #db/id[:db.part/db], 
     :db/ident :user/other, 
     :db/valueType :db.type/string, 
     :db/cardinality :db.cardinality/one
     }]
   ;; transaction 2: The untangled.datomic-schema library can be used to generate transactions.
   (s/generate-schema 
     [(s/schema user ;; An easy way to create a set of attributes under the same conceptual entity name
                (s/fields 
                  [username :string] ;; see untangled.datomic-schema docs
                  [email :string :unique-identity]
                  [status :enum [:active :pending]]
                  )
                )]
     {:index-all? true} ;; Stu Halloway recommends indexing everything...
     )
   ]
  )

