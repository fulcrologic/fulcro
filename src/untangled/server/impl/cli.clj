(ns untangled.server.impl.cli
  (:require [clojure.tools.cli :refer [cli]]
            [datomic.api :as d]
            [io.rkn.conformity :as c]
            [taoensso.timbre :as timbre]
            [untangled.server.impl.components.database :as cd]
            [untangled.server.impl.database.migration :as m]))

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

(defn migrate-all [db-configs]
  (doseq [[_ config] db-configs]
    (let [{:keys [url schema]} config
          connection (d/connect url)]
      (cd/run-core-schema connection)
      (m/migrate connection schema)
      )))

(defn migration-status-all [db-configs verbose]
  (reduce
    (fn [acc config]
      (let [{:keys [url schema]} config
            connection (d/connect url)
            migrations (m/all-migrations schema)]
        (into acc (check-migration-conformity connection migrations verbose)))
      ) #{} (vals db-configs))
  )

(defn main-handler [config args]
  (let [[opts args banner]
        (cli args
             ["-h" "--help" "Print this help." :default false :flag true]
             ["-v" "--verbose" "Be verbose." :default false :flag true]
             ["-l" "--list-dbs" "List databases that can be migrated." :default false :flag true]
             ["-m" "--migrate" "Apply migrations to a database, or `all` for all databases." :default nil]
             ["-s" "--migration-status" (str "Check a whether a database has all possible migrations. "
                                             "Use `all` to check all databases.") :default nil])
        argument (single-arg (select-keys opts [:list-dbs :migrate :migration-status :help]))]
    (if-not argument
      (do (fatal "Only one argument at a time is supported.") (println banner))
      (let [target-db (-> argument first second)
            db-config (if (= target-db "all")
                        config
                        (->> target-db keyword (get config)))]
        (cond (:list-dbs opts) (info "Available databases configured for migration:\n" (mapv name (keys config)))
              (:migrate opts) (migrate-all db-config)
              (:migration-status opts) (let [migs (migration-status-all db-config (:verbose opts))]
                                         (if (empty? migs)
                                           (timbre/info "Database conforms to all migrations!")
                                           (timbre/warn "Database does NOT conform to these migrations: " migs))))))))
