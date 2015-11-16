(ns untangled.components.database-spec
  (:require [com.stuartsierra.component :as component]
            [untangled.components.config :as cfg]
            [untangled.components.database :as db])
  (:use midje.sweet))

(def default-db-name :db1)
(def default-db-url "db1-url")
(def default-schema "schema.default")
(defn make-config [m]
  {:dbs {default-db-name (merge {:url default-db-url
                                 :schema default-schema}
                                m)}})

(def default-config
  (make-config {:auto-drop true}))

(def migrate-config
  (make-config {:auto-migrate true}))

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

(background (untangled.util.logging/info anything)                   => nil
            (untangled.util.logging/info anything anything)          => nil
            (untangled.util.logging/info anything anything anything) => nil
            )

(facts "DatabaseComponent"
       (facts "implements Database"
              (satisfies? untangled.database/Database
                          (db/build-database "a-db-name")))
       (fact "implements component/Lifecycle"
             (satisfies? component/Lifecycle
                         (db/build-database "a-db-name"))
             => true
             (fact ".start loads the component"
                   (-> (start-system) :db keys)
                   => (contains #{:config})
                   (provided
                     (datomic.api/create-database default-db-url) => anything
                     (datomic.api/connect default-db-url) => anything))
             (fact ".start can migrate if configured to"
                   (start-system migrate-config) => truthy
                   (provided
                     (datomic.api/create-database default-db-url) => anything
                     (datomic.api/connect default-db-url) => anything
                     (#'db/run-core-schema anything) => anything
                     (#'db/run-migrations anything anything anything) => anything))
             (fact ".start runs seed-function if it needs to"
                   (-> (start-system seed-config) :db :seed-result)
                   => seed-result
                   (provided
                     (datomic.api/create-database default-db-url) => anything
                     (datomic.api/connect default-db-url) => anything
                     ))
             (fact ".stop stops the component"
                   (-> (start-system) .stop :db :connection) => nil
                   (provided
                     (datomic.api/create-database anything) => anything
                     (datomic.api/connect anything) => anything
                     (datomic.api/delete-database anything) => anything)))
       )
