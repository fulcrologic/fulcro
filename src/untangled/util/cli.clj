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
  (let [rv #{}]
    (doseq [migration migrations
            nm (keys migration)]
      (if (c/conforms-to? (d/db connection) nm)
        (do (timbre/info "Verified that database conforms to migration: " nm) (conj rv true))
        (do (timbre/warn "Database does NOT conform to migration: " nm) (conj rv false))
        )
      )
    (if (= rv #{true}) true false)
    )

  )

(defn main-handler [system args]

  (let [[opts args banner]
        (cli args
             ["-h" "--help" "Print this help." :default false :flag true]
             ["-l" "--list-dbs" "List databases that can be migrated." :default false]
             ["-m" "--migrate" "Apply migrations to a database." :default false]
             ["-s" "--migration-status" "Check a whether a database has all possible migrations." :default false]
             ["-d" "--dry-run-migration" "Report which migrations would be applied to a database" :default false])
        argument (single-arg opts)]
    (if-not argument
      (timbre/fatal "Only one argument at a time is supported.")
      (let [db-config (get system (keyword (second (first argument))))
            nspace (:migration-ns db-config)
            connection (d/connect (:url db-config))
            migrations (m/all-migrations nspace)]
        (cond (:dry-run-migration opts) (clojure.pprint/pprint banner)
              (:migrate opts) (m/migrate connection nspace)
              (:migration-status opts) (check-migration-conformity connection migrations)
              )))
    )
  (System/exit 0))
