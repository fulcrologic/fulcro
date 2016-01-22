(ns untangled.server.impl.database.fixtures
  (:require
    [datomic.api :as d]
    [com.stuartsierra.component :as component]
    [untangled.server.impl.database.protocols :as udb]
    [untangled.server.core :refer [build-database]]
    [untangled.server.impl.components.logger :refer [start-logging! reset-logging!]]))

(defn db-fixture
  "Create a test fixture version (in-memory database) of a database. Such a
  database will be auto-migrated to the current version of the schema in the
  given (optional) namespace, and the
  given seed-fn will be run as part of the database startup (see the database
  component in datahub for details on seeding).
  "
  [db-key & {:keys [migration-ns seed-fn log-level]}]
  (let [
        uri "datomic:mem://db-fixture"
        db  (build-database db-key)]
    (d/delete-database uri)
    (start-logging! nil nil log-level)
    (component/start (assoc db :config {:value {:datomic {:dbs {db-key
                                                                (cond-> {:url uri :auto-drop true}
                                                                  migration-ns (assoc :auto-migrate true :schema migration-ns)
                                                                  seed-fn (assoc :seed-function seed-fn))}}}}))))

(defmacro with-db-fixture
  "
  Set up the specified database, seed it using seed-fn (see Database component),
  make it available as varname, run the given form, then clean up the database.

  Returns the result of the form.
  "
  [varname form & {:keys [migrations seed-fn log-level] :or {log-level :fatal}}]
  `(let [~varname (db-fixture :mockdb :migration-ns ~migrations :seed-fn ~seed-fn :log-level ~log-level)]
     (try ~form (finally
                  (component/stop ~varname)
                  (reset-logging!))))
  )

(defn db-fixture-defs [fixture parser]
  "Given a db-fixture and an om parser, returns a map keyed by:
    `connection`: a connection to the fixture's db
    `parse`: a partially applied call to parser with an environment containing the connection (call it with query to parse)
    `seeded-tempid-map`: return value of seed-link-and-load-data
    `get-id`: give it a temp-id from seeded data, return the real id from `seeded-tempid-map`"

  (let [connection (udb/get-connection fixture)
        parse (partial parser {:connection connection})
        tempid-map (:seed-result (udb/get-info fixture))
        get-id (partial get tempid-map)]
    {:connection        connection
     :parse             parse
     :seeded-tempid-map tempid-map
     :get-id            get-id}))
