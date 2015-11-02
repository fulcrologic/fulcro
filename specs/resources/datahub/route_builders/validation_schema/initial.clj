(ns resources.datahub.route-builders.validation-schema.initial
  (:require
    [datomic-schema.schema :as s]
    [datomic-schema.migration :as m]
    )
  )

(defn transactions []
  (concat
    [
     (s/generate-schema
       [
        (s/dbfn constrained-delete [db eid] :db.part/user
                (let [attrs (keys (datomic.api/entity db eid))
                      references-to-attr (fn [db attr eid]
                                           (datomic.api/q '[:find [?e2 ...]
                                                            :in $ ?target ?eid
                                                            :where [?e2 ?v ?eid] 
                                                            [?e :db/ident ?v] 
                                                            [?e :constraint/references ?target]] db attr eid)
                                           )
                      refs     (clojure.core/mapcat #(references-to-attr db % eid) attrs)
                      ]
                  (if (empty? refs) [[:db.fn/retractEntity eid]] (throw (Exception. "CONSTRAINTED")))
                  )
                )
        (s/schema realm
                  (s/fields
                    [account-id :string :unique-identity :definitive]
                    [account-name :string]
                    [user :ref :many {:references :user/email}]
                    [property-group :ref :many :component] ;; realm-defined property groups
                    [custom-authorization-role :ref :many :component]
                    [subscription :ref :many :component {:references :subscription/name}]
                    )
                  )

        (s/schema subscription
                  (s/fields
                    [name :string :unique-identity]
                    [application :ref :one { :references :application/name }]
                    [component :ref :one { :references :component/name }]
                    ;; ... dates, etc?
                    )
                  )

        (s/schema user
                  (s/fields
                    [user-id :uuid :unique-identity :definitive]
                    [realm :ref :one { :references :realm/account-id }]
                    [email :string :unique-value]
                    [password :string]
                    [is-active  :boolean]
                    [validation-code :string]
                    [property-entitlement :ref :many
                     { :references :entitlement/kind 
                      :with-values #{:entitlement.kind/property-group 
                                     :entitlement.kind/property
                                     :entitlement.kind/all-properties } 
                      }
                     ]
                    [authorization-role :ref :many]
                    )
                  )

        (s/schema component
                  (s/fields
                    [name :string]
                    ;; description of what this component does
                    [read-functionality :string]
                    [write-functionality :string]
                    )
                  )

        (s/schema application
                  (s/fields
                    [name :string :unique-identity]
                    [component :ref :many :component]
                    )
                  )

        (s/schema property-group ;; owned by realm
                  (s/fields
                    [name :string ]
                    [property :uuid :many ] ;; to property
                    )
                  )

        (s/schema entitlement
                  (s/fields
                    ;; The kind of entitlement.
                    [kind :enum [:all-properties :all-components :property :property-group
                                 :component :application] ]
                    ;; a property, propgroup, component, or application
                    [target :ref :one] 
                    [target-property :uuid]

                    ;; Limits are ONLY used when kind is property-related, can
                    ;; optionally limit that property access to specific
                    ;; apps/components. E.g. Reach list builder might need to
                    ;; allow access to all properties, but those property's
                    ;; financials should not be exposed by giving property access
                    ;; to Reveal.
                    [limit-to-application :ref :many]
                    [limit-to-component :ref :many]

                    [permission :enum [:read :write]]
                    )
                  )

        (s/schema authorization-role  ;; pre-defined set of entitlements under a (convenient) app-specific role name
                  (s/fields
                    [application :ref :one]
                    [name :string]
                    [entitlement :ref :many :component]
                    )
                  )
        ])
     (m/entity-extensions :user "A User" #{:authorization-role/name})
     ]
    ))

(def routes [])
