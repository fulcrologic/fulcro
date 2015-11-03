(ns untangled.components.database
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as datomic]
            [taoensso.timbre :refer [info fatal]]
            [untangled.datomic-schema.migration :as m]
            [untangled.datomic-schema.core-schema-definitions :as sc]
            untangled.database
            )
  (:import (untangled.database Database)))

(defn- run-core-schema [conn]
  (info "Applying core schema to database.")
  (doseq []
    (sc/ensure-constraints-conform conn)
    (sc/ensure-entities-conform conn)
    )
  )

(defn- run-migrations [migration-ns kw conn]
  (info "Applying migrations " migration-ns "to" kw "database.")
  (m/migrate conn migration-ns)
  )

(defrecord DatabaseComponent [name url migrate-on-start drop-on-stop connection seed-function migration-ns seed-result]
  Database
  (get-connection [this] connection)
  (get-info [this] {:name name :url url :seed-result seed-result :schema-package migration-ns})
  component/Lifecycle
  (start [this]
    (let [created (datomic/create-database url)
          c (datomic/connect url)]
      (when migrate-on-start
        (info "Ensuring core schema is defined")
        (run-core-schema c)
        (info "Running migrations on" name)
        (run-migrations migration-ns name c))
      (try
        (cond-> (assoc this :connection c)
                (and created seed-function) (assoc :seed-result (do
                                                                  (info "Seeding database" name)
                                                                  (seed-function c))
                                                   )
                )
        (catch Exception e (fatal e))
        )
      )
    )
  (stop [this]
    (info "Stopping database" name)
    (if drop-on-stop (do
                       (info "Deleting database" name url)
                       (datomic/delete-database url)))
    (assoc this :connection nil)
    )
  )

(defn build-database [database-key config]
  (assert (:dbs config) "Missing :dbs of app config.")
  (let [db-config (-> config :dbs database-key)]
    (assert (:url db-config) (str database-key " has no URL in dbs of app config."))
    (assert (:schema db-config) (str database-key " has no Schema in dbs of app config."))
    (map->DatabaseComponent {
                             :name             database-key
                             :migrate-on-start (boolean (or (:auto-migrate config) (:auto-migrate db-config)))
                             :drop-on-stop     (:auto-drop db-config)
                             :seed-function    (:seed-on-start db-config)
                             :url              (:url db-config)
                             :migration-ns     (:schema db-config)
                             })
    )
  )
