(ns untangled.util.cli
  (:require [clojure.tools.cli :refer [cli]]
            [datomic.api :as d]
            [io.rkn.conformity :as c]
            [taoensso.timbre :as timbre]
            [untangled.datomic-schema.migration :as m]))

(defmacro make-fn [m]
  `(fn [& args#]
     (eval (cons '~m args#))))

(defn fatal [& msg]
  (apply (make-fn taoensso.timbre/fatal) msg))

(defn info [& msg]
  (apply (make-fn taoensso.timbre/info) msg))


(defn single-arg [args]
  (let [argslist (filter #(if (second %) true false) args)]
    (if (= (count argslist) 1) argslist nil))
  )

(defn check-migration-conformity [connection migrations verbose]
  (reduce (fn [nonconforming-migrations mig]
            (let [[migration] (keys mig)]
              (if-not (c/conforms-to? (d/db connection) migration)
               (conj nonconforming-migrations (if verbose mig migration))
               nonconforming-migrations
               ))
            ) #{} migrations))

(defn main-handler [config args]
  (let [[opts args banner]
        (cli args
             ["-h" "--help" "Print this help." :default false :flag true]
             ["-v" "--verbose" "Be verbose." :default false :flag true]
             ["-l" "--list-dbs" "List databases that can be migrated." :default false :flag true]
             ["-m" "--migrate" "Apply migrations to a database." :default false]
             ["-s" "--migration-status" "Check a whether a database has all possible migrations." :default false])
        argument (single-arg (select-keys opts [:list-dbs :migrate :migration-status :help]))]
    (if-not argument
      (do (fatal "Only one argument at a time is supported.") (println banner))
      (let [db-config (get config (keyword (second (first argument))))
            nspace (:schema db-config)
            connection (if db-config (d/connect (:url db-config)))
            migrations (m/all-migrations nspace)]
        (cond (:list-dbs opts) (info "Available databases configured for migration:\n" (mapv name (keys config)))
              (:migrate opts) (m/migrate connection nspace)
              (:migration-status opts) (let [migs (check-migration-conformity connection migrations (:verbose opts))]
                                         (if (empty? migs)
                                           (timbre/info "Database conforms to all migrations!")
                                           (timbre/warn "Database does NOT conform to these migrations: " migs)))
              ))))
  ; TODO: really exit here?
  #_(System/exit 0))
