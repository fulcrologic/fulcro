(ns untangled.util.cli
  (:require [clojure.tools.cli :refer [cli]]
            [datomic.api :as d]
            [io.rkn.conformity :as c]
            [taoensso.timbre :as timbre]
            [untangled.datomic-schema.migration :as m]))

(defn- single-arg [args]
  (let [argslist (filter #(if (second %) true false) args)]
    (if (> (count argslist) 1) nil argslist))
  )

(defn check-migration-conformity [connection migrations]
  (let [nonconforming-migs (atom #{})]
    (doseq [migration migrations
            migration-name (keys migration)]
      (if-not (c/conforms-to? (d/db connection) migration-name)
        (swap! nonconforming-migs conj migration-name)
        )
      )
    @nonconforming-migs
    )

  )

(defn main-handler [system args]

  (let [[opts args banner]
        (cli args
             ["-h" "--help" "Print this help." :default false :flag true]
             ["-l" "--list-dbs" "List databases that can be migrated." :default false]
             ["-m" "--migrate" "Apply migrations to a database." :default false]
             ["-s" "--migration-status" "Check a whether a database has all possible migrations." :default false])
        argument (single-arg opts)]
    (if-not argument
      (timbre/fatal "Only one argument at a time is supported.")
      (let [db-config (get system (keyword (second (first argument))))
            nspace (:migration-ns db-config)
            connection (d/connect (:url db-config))
            migrations (m/all-migrations nspace)]
        (cond
              (:migrate opts) (m/migrate connection nspace)
              (:migration-status opts) (let [migs (check-migration-conformity connection migrations)]
                                         (if (empty? migs)
                                           (timbre/info "Database conforms to all migrations.")
                                           (timbre/warn "Database does not conform to these migrations: " migs)))
              )))
    )
  #_(System/exit 0))
