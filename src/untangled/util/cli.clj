(ns untangled.util.cli
  (:require [clojure.tools.cli :refer [cli]]))

(defn main-handler [args]
  (let [[opts args banner]
        (cli args
             ["-h" "--help" "Print this help." :default false :flag true]
             ["-l" "--list-dbs" "List database that can be migrated." :default false]
             ["-m" "--migrate" "Apply migrations to a database." :default false]
             ["-s" "--migration-status" "Check a whether a database has all possible migrations." :default false]
             ["-d" "--dry-run-migration" "Report which migrations would be applied to a database" :default false]
             )]
    (clojure.pprint/pprint opts))
  )
