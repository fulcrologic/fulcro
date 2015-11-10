(ns untangled.components.database
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as datomic]
            [untangled.util.logging :refer [info fatal]]
            [untangled.datomic-schema.migration :as m]
            [untangled.datomic-schema.core-schema-definitions :as sc]
            untangled.database
            )
  (:import (untangled.database Database)))

(defn- run-core-schema [conn]
  (info "Applying core schema to database.")
  (doseq []
    (sc/ensure-constraints-conform conn)
    (sc/ensure-entities-conform conn)))

(defn- run-migrations [migration-ns kw conn]
  (info "Applying migrations " migration-ns "to" kw "database.")
  (m/migrate conn migration-ns))

(defrecord DatabaseComponent [db-name connection seed-result
                              ;url migrate-on-start drop-on-stop
                              ;seed-function migration-ns
                              ]
  Database
  (get-connection [this] (:connection this))
  (get-info [this]
    (let [{:keys [url seed-result ]} this]
      {:name db-name
                    ;:url url
                    ;:seed-result seed-result
                    ;:schema-package migration-ns
                    }))
  component/Lifecycle
  (start [this]
    (let [db-config (-> this :config :value :dbs db-name)
          {:keys [url migrate-on-start drop-on-stop
                  seed-function migration-ns]} db-config
          created (datomic/create-database url)
          c (datomic/connect url)]
      (when migrate-on-start
        (info "Ensuring core schema is defined")
        (run-core-schema c)
        (info "Running migrations on" db-name)
        (run-migrations migration-ns db-name c))
      (try
        (cond-> (assoc this :connection c)
          (and created seed-function) (assoc :seed-result
                                             (do
                                               (info "Seeding database" db-name)
                                               (seed-function c)))
          true (assoc :url url
                      :drop-on-stop drop-on-stop)
          )
        (catch Exception e (fatal e)))
      )
    )
  (stop [this]
    (info "Stopping database" db-name)
    (let [{:keys [drop-on-stop url]} this]
      (when drop-on-stop
        (info "Deleting database" db-name url)
        (datomic/delete-database url)))
    (assoc this :connection nil)
    ))

(defn build-database [database-key]
  ;(assert (:dbs config) "Missing :dbs of app config.")
  ;(let [db-config (-> config :dbs database-key)]
  ;(assert (:url db-config)
  ;        (str database-key " has no URL in dbs of app config."))
  ;(assert (:schema db-config)
  ;        (str database-key " has no Schema in dbs of app config."))
  (component/using
    (map->DatabaseComponent {:db-name database-key})
    [:config])
  ;(map->DatabaseComponent
  ;  {
  ;   :name             database-key
  ;   :migrate-on-start (boolean (or (:auto-migrate config)
  ;                                  (:auto-migrate db-config)))
  ;   :drop-on-stop     (:auto-drop db-config)
  ;   :seed-function    (:seed-on-start db-config)
  ;   :url              (:url db-config)
  ;   :migration-ns     (:schema db-config)
  ;   })
  ;)
  )
