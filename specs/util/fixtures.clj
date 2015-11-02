(ns util.fixtures
  (:require
    [datomic.api :as d]
    [com.stuartsierra.component :as component]
    datahub.components.database
    datahub.components.rest-routes
    datahub.components.handler
    datahub.system
    )
  )

(defn db-fixture
  "Create a test fixture version (in-memory database) of a database. Such a
  database will be auto-migrated to the current version of the schema in the
  given (optional) namespace, and the
  given seed-fn will be run as part of the database startup (see the database
  component in datahub for details on seeding).
  "
  [db-key & {:keys [migration-ns seed-fn]}]
  (let [
        uri "datomic:mem://db-fixture"
        db (datahub.components.database/build-database
             db-key {:dbs {db-key
                           (cond-> {:url uri :auto-drop true }
                             migration-ns (assoc :auto-migrate true :schema migration-ns)
                             seed-fn      (assoc :seed-on-start seed-fn))
                            }})]
    (d/delete-database uri)
    (component/start db)
    ))

(defmacro with-db-fixture
  "
  Set up the specified database, seed it using seed-fn (see Database component),
  make it available as varname, run the given form, then clean up the database. 

  Returns the result of the form.
  "
  [varname form & {:keys [migrations seed-fn] :or { :migrations nil :seed-fn nil } }]
  `(let [~varname (db-fixture :mockdb :migration-ns ~migrations :seed-fn ~seed-fn)]
     (try ~form (finally (component/stop ~varname))))
  )

(defn web-fixture
  "Create a test handler fixture and start the component for testing.
  "
  [rest-routes]
  (let [handler (datahub.components.handler/build-handler-with-rest-routes :mockhandler rest-routes)]
    (component/start handler)
    )
  )

(defmacro with-web-fixture
  "
  Set up the handler component, start the component, run the given form
  and stop the component after the form was run.

  Returns the result of the form.
  "
  [varname form dbcomp]
  `(let [restroutes# (datahub.components.rest-routes/build-rest-routes-with-database ~dbcomp)
         handler# (web-fixture restroutes#)
         ~varname (:all-routes handler#)]
     (try ~form (finally (do
                           (component/stop restroutes#)
                           (component/stop handler#)
                           ))))
  )

(defmacro with-system
  "Create and start a complete web server using the config/test-config, make said system
  available as varname, run the given forms, and then shut down the server. Used for running
  full-blown integration tests."
  [varname config & form]
  `(let [~varname (component/start (datahub.system/make-system ~config))]
    (try ~@form (finally (do
                           (component/stop ~varname)
                           )))
    )
  )
