(ns sample-migrations.migrations.auth.users-20150609
  (:require [untangled.datomic-schema.schema :as s]
            [untangled.datomic-schema.migration :as m]
            [datomic.api :as d])
  )

(defn transactions []
  [;; list of transactions (list of lists)
   (s/generate-schema
     [
      (s/schema realm
                (s/fields
                  [account-id :string :unique-identity :definitive]
                  [account-name :string]
                  [user :ref :many]
                  [property :uuid :many]                    ;; all of this realm's properties
                  [property-group :ref :many :component]    ;; realm-defined property groups
                  [custom-authorization-role :ref :many :component]
                  [subscription :ref :many :component {:references :subscription/name}]
                  )
                )

      (s/schema subscription
                (s/fields
                  [name :string :unique-identity :definitive]
                  [application :ref :one {:references :application/name}]
                  [component :ref :one {:references :component/name}]
                  ;; ... dates, etc?
                  )
                )

      (s/schema user
               (s/fields
                  [user-id :uuid "Unique User ID for the user, this value should not be changed as it defines the user over time. This value is used as the :sub value in the OAuth tokens." :unique-identity :definitive]
                  [email :string :unique-value "Email for the user. Must be unique across all users"]
                  [password :string :unpublished "Hash encoded password"]
                  [is-active :boolean "Used to monitor user state"]
                  [validation-code :string "Code for current Two Factor authentication"]
                  [property-entitlement :ref :many {:references :property-entitlement/kind}
                   "A set of property entitilements that are unique to this users, the allowed types of entitlements are :entitlement.kind/property-group, :entitlement.kind/property and :entitlement.kind/all-properties"
                   ]
                  [authorization-role :ref :many "A set of authorization roles assigned to the User"]
                  )
                )

      (s/schema component
                (s/fields
                  [name :string :unique-identity]
                  ;; description of what this component does
                  [read-functionality :string]
                  [write-functionality :string]
                  )
                )

      (s/schema application
                (s/fields
                  [application-id :uuid :unique-identity]
                  [name :string]
                  [component :ref :many :component {:references :component/name}]
                  )
                )

      (s/schema property-group                              ;; owned by realm
                (s/fields
                  [name :string]
                  [property :uuid :many]                    ;; to property
                  )
                )

      (s/schema entitlement
                (s/fields
                  [permission :enum [:read :write]]
                  )
                )

      (s/schema property-entitlement
                (s/fields
                  ;; The kind of entitlement.
                  [kind :enum [:all-properties :property :property-group]]
                  [target-group :ref :one {:references :property-group/name}]
                  [target-property :uuid]

                  ;; Limits are ONLY used when kind is property-related, can
                  ;; optionally limit that property access to specific
                  ;; apps/components. E.g. Reach list builder might need to
                  ;; allow access to all properties, but those property's
                  ;; financials should not be exposed by giving property access
                  ;; to Reveal.
                  [limit-to-application :ref :many]
                  [limit-to-component :ref :many]
                  )
                )

      (s/schema software-entitlement
                (s/fields
                  ;; The kind of entitlement.
                  [kind :enum [:all-components :component :application]]
                  [target-component :ref :one {:references :component/name}]
                  [target-application :ref :one {:references :application/name}]
                  )
                )


      (s/schema authorization-role                          ;; pre-defined set of entitlements under a (convenient) app-specific role name
                (s/fields
                  [name :string]
                  ;; If defined, this role is for a specific application
                  [application :ref :one {:references :application/id}]
                  [software-entitlements :ref :many :component {:references :software-entitlement/kind}]
                  [property-entitlements :ref :many :component {:references :property-entitlement/kind}]
                  )
                )
      ]
     {:index-all? true}
     )
   (m/entity-extensions :user "User represents and indidual who accesses the navis system and the associated software and property entitlements and authorization roles." [])
   (m/entity-extensions :realm "Realm represents a navis clients and the associated users, properties, property groups, subscriptions and authorization roles." [])
   (m/entity-extensions :subscription "Subscripton represents which application and application components that are allowed for a realm" [])
   (m/entity-extensions :component "Component represents a logically grouped set of applicaiton functions that can be exposed to applications and subscriptions" [])
   (m/entity-extensions :application "Application represents an overall navis application that contains 1 or more components" [])
   (m/entity-extensions :property-group "Property Group represents a logical group of properties for a navis client that contain 1 or more properties" [])
   (m/entity-extensions :entitlement "An abstract representation of entitlement" [])
   (m/entity-extensions :software-entitlement "Entitlement represents an individual permision to a component, all components or application.
                                Permissions of read/write and links to specific a specific property, application or coponent when required." [:entitlement/permission])
   (m/entity-extensions :property-entitlement "Entitlement represents an individual permision to a property, all properties, property-group, component, all components or application.
                                Permissions of read/write and links to specific a specific property, application or coponent when required." [:entitlement/permission])
   (m/entity-extensions :authorization-role "Authorization Role is a logical grouping application specific roles with specified entitlements." [])
   ]
  )
