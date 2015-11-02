(ns resources.datahub.route-builders.schema.initial
  (:require [datomic-schema.schema :as s]
            [datomic.api :as d]
            [datomic-schema.migration :as m])
  )

(defn transactions []
  [
   (s/generate-schema
     [
      (s/schema realm
                (s/fields
                  [realm-id :string :unique-identity :definitive "realm-id-doc"]
                  [account-id :string :unique-identity :definitive]
                  [account-name :string]
                  [user :ref :many {:references :user/user-id} "realm-user-doc"]
                  )
                )

      (s/schema user
                (s/fields
                  [user-id :uuid :unique-identity :definitive]
                  [realm :ref :one {:references :realm/realm-id}]
                  [email :string :unique-value]
                  [password :string :unpublished]
                  [is-active  :boolean]
                  [validation-code :string]
                  )
                )

      (s/schema test-data-types
                (s/fields
                  [uuid-type      :uuid :unique-identity :definitive]
                  [keyword-type :keyword]
                  [string-type :string]
                  [boolean-type :boolean]
                  [long-type :long]
                  [bigint-type :bigint]
                  [float-type :float]
                  [double-type :double]
                  [bigdec-type :bigdec]
                  [ref-type :ref :one]
                  [instant-type :instant]
                  [uri-type :uri]
                  [bytes-type :bytes]
                  )
                )
      ])
   (m/entity-extensions :user "user entity doc" [])
   (m/entity-extensions :realm "realm entity doc" [])
   ]
  )
