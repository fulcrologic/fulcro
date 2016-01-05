(ns untangled.components.database-spec
  (:require [com.stuartsierra.component :as component]
            [untangled.components.config :as cfg]
            [clojure.test :refer :all]
            [untangled-spec.core :refer [specification
                                         assertions
                                         when-mocking
                                         component
                                         behavior]]
            [untangled.components.database :as db]
            [datomic-toolbox.core :as dt]))

(def default-db-name :db1)
(def default-db-url "db1-url")
(def default-schema "schema.default")
(defn make-config [m]
  {:datomic {:dbs {default-db-name (merge {:url    default-db-url
                                           :schema default-schema}
                                     m)}}})

(def default-config
  (make-config {:auto-drop true}))

(def migrate-specfic-config
  (make-config {:auto-migrate true}))

(def migrate-all-config
  (let [config-sans-migrate (update-in migrate-specfic-config [:datomic :dbs default-db-name] dissoc :auto-migrate)]
    (assoc-in config-sans-migrate
      [:datomic :auto-migrate] true)))

(def seed-result :a-tree!)
(def seed-config
  (make-config {:seed-function (fn [_] seed-result)}))

(defn start-system
  ([] (start-system default-config))
  ([cfg]
   (with-redefs [cfg/load-config (fn [_] cfg)]
     (-> (component/system-map
           :config (cfg/new-config {})
           :db (db/build-database default-db-name))
       .start))))

(specification "DatabaseComponent"

  (behavior "implements Database"
    (assertions
      (satisfies? untangled.database/Database (db/build-database "a-db-name")) => true))

  (behavior "implements component/Lifecycle"
    (assertions
      (satisfies? component/Lifecycle (db/build-database "a-db-name")) => true))

  (behavior ".start loads the component"
    (when-mocking
      (datomic.api/create-database default-db-url) => true
      (datomic.api/connect default-db-url) => true
      (assertions
        (some #(= :config %) (-> (start-system) :db keys)) => true)))

  (behavior ".start can auto-migrate if configured for all databases"
    (when-mocking
      (dt/install-migration-schema) => true
      (dt/run-migrations _) => true
      (datomic.api/create-database default-db-url) => true
      (datomic.api/connect default-db-url) => true
      (db/run-core-schema anything) => true
      (db/run-migrations anything anything anything) => true
      (assertions
        (if (start-system migrate-all-config) true) => true)))

  (behavior ".start can auto-migrate if configured for a specific database"
    (when-mocking
      (dt/install-migration-schema) => true
      (dt/run-migrations _) => true
      (datomic.api/create-database default-db-url) => true
      (datomic.api/connect default-db-url) => true
      (db/run-core-schema anything) => true
      (db/run-migrations anything anything anything) => true
      (assertions
        (if (start-system migrate-specfic-config) true) => true)))

  (behavior ".start runs seed-function if it needs to"
    (when-mocking
      (datomic.api/create-database default-db-url) => true
      (datomic.api/connect default-db-url) => true
      (assertions
        (-> (start-system seed-config) :db :seed-result) => seed-result)))

  (behavior ".stop stops the component"
    (when-mocking
      (datomic.api/create-database anything) => true
      (datomic.api/connect anything) => true
      (datomic.api/delete-database anything) => true
      (assertions
        (-> (start-system) .stop :db :connection) => nil))))
