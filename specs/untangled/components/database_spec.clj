(ns untangled.components.database-spec
  (:require [com.stuartsierra.component :as component]
            [untangled.components.config :as cfg]
            [untangled.components.database :as db])
  (:use midje.sweet))

(def default-db-name :db1)
(def default-db-url "db1-url")
(defn make-config [m]
  {:dbs {default-db-name (merge {:url default-db-url} m)}})

(def default-config
  (make-config {:drop-on-stop true}))

(def migrate-config
  (make-config {:migrate-on-start true}))

(def seed-config
  (make-config {:seed-function (fn [_] :a-tree!)}))

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

(facts :focused "DatabaseComponent"
       (facts :focused "implements Database"
              #_TODO)
       (fact :focused "implements component/Lifecycle"
             (satisfies? component/Lifecycle
                         (db/build-database "a-db-name"))
             => true
             (fact :focused ".start loads the component"
                   (-> (start-system) :db keys)
                   => (contains #{:url :drop-on-stop :config})
                   (provided
                     (datomic.api/create-database default-db-url) => anything
                     (datomic.api/connect default-db-url) => anything))
             (fact :focused ".start can migrate if configured to"
                   (start-system migrate-config) => truthy
                   (provided
                     (datomic.api/create-database default-db-url) => anything
                     (datomic.api/connect default-db-url) => anything
                     (#'db/run-core-schema anything) => anything
                     (#'db/run-migrations anything anything anything) => anything))
             (fact :focused ".start runs seed-function if it needs to"
                   (-> (start-system seed-config) :db :seed-result)
                   => :a-tree!
                   (provided
                     (datomic.api/create-database default-db-url) => anything
                     (datomic.api/connect default-db-url) => anything
                     ))
             (fact :focused ".stop stops the component"
                   (-> (start-system) .stop :db :connection) => nil
                   (provided
                     (datomic.api/create-database anything) => anything
                     (datomic.api/connect anything) => anything
                     (datomic.api/delete-database anything) => anything)))
       )
