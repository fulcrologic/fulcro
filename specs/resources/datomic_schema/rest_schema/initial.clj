(ns resources.datomic-schema.rest-schema.initial
  (:require [untangled.datomic-schema.schema :as s]
            [datomic.api :as d]
            [untangled.datomic-schema.migration :as m])
  )

(defn transactions []
  [
   (s/generate-schema
            [
             (s/schema realm
                       (s/fields
                         [realm-id :string :unique-identity :definitive "realm-id-doc"]
                         [realm-name :string "realm-name-doc"]
                         [user :ref :many {:references :user/user-id} "realm-user-doc"]
                         [subscription :ref :many :component {:references :subscription/name} "realm-subscription-doc"]
                         )
                       )
             (s/schema subscription
                       (s/fields
                         [name :string :unique-identity :definitive]
                         [component :ref :one :component {:references :component/name}]
                         )
                       )

             (s/schema component
                       (s/fields
                         [name :string :unique-identity :definitive]
                         )
                       )

             (s/schema user
                       (s/fields
                         [user-id :uuid :unique-identity :definitive]
                         [realm :ref :one {:references :realm/realm-id}]
                         [email :string :unique-value]
                         [password :string :unpublished]
                         )
                       )
             ])
     (m/entity-extensions :user "user entity doc" [])
     (m/entity-extensions :realm "realm entity doc" [])
     (m/entity-extensions :subscription "subscription entity doc" [])
     (m/entity-extensions :component "component entity doc" [:realm/realm-id :realm/realm-name])
   ]
  )
