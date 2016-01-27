(ns untangled.server.impl.cli-spec
  (:require [untangled.server.impl.cli :as cli]
            [clojure.test :as t :refer (are is deftest with-test run-tests testing do-report)]
            [taoensso.timbre :as timbre]
            [datomic.api :as d]
            [untangled.datomic.impl.migration :as mig]
            [untangled.datomic.impl.components.database :as cd]
            [io.rkn.conformity :as c]
            [untangled-spec.core :refer [specification provided behavior assertions]]))

(def list-dbs-config {:survey        {:url       "datomic:mem://survey"
                                      :schema    "survey.migrations"
                                      :auto-drop false}
                      :some-other-db {:url       "datomic:mem://some-other-db"
                                      :schema    "some-other-db.migrations"
                                      :auto-drop false}})

(def migrations '({:survey.migrations/survey-20151106
                   {:txes
                    [({:db/index              false,
                       :db/valueType          :db.type/string,
                       :db/noHistory          false,
                       :db/isComponent        false,
                       :db.install/_attribute :db.part/db,
                       :db/fulltext           false,
                       :db/cardinality        :db.cardinality/one,
                       :db/doc                "Is this property awesome?",
                       :db/id                 {:part :db.part/db, :idx -1000509},
                       :db/ident              :property/awesome})]}}
                   {:survey.migrations/survey-20151109
                    {:txes
                     [({:db/index              false,
                        :db/valueType          :db.type/string,
                        :db/noHistory          false,
                        :db/isComponent        false,
                        :db.install/_attribute :db.part/db,
                        :db/fulltext           false,
                        :db/cardinality        :db.cardinality/one,
                        :db/doc                "Is this property better?",
                        :db/id                 {:part :db.part/db, :idx -1000510},
                        :db/ident              :property/better})]}}
                   {:survey.migrations/survey-20151110
                    {:txes
                     [({:db/index              false,
                        :db/valueType          :db.type/string,
                        :db/noHistory          false,
                        :db/isComponent        false,
                        :db.install/_attribute :db.part/db,
                        :db/fulltext           false,
                        :db/cardinality        :db.cardinality/one,
                        :db/doc                "Is this property hotter?",
                        :db/id                 {:part :db.part/db, :idx -1000511},
                        :db/ident              :property/hotter})]}}
                   {:survey.migrations/survey-20151111
                    {:txes
                     [({:db/index              false,
                        :db/valueType          :db.type/string,
                        :db/noHistory          false,
                        :db/isComponent        false,
                        :db.install/_attribute :db.part/db,
                        :db/fulltext           false,
                        :db/cardinality        :db.cardinality/one,
                        :db/doc                "Is this property funkier?",
                        :db/id                 {:part :db.part/db, :idx -1000512},
                        :db/ident              :property/funkier})]}}))

(specification "single-arg"
               (behavior "keeps truthy values"
                         (assertions
                           (cli/single-arg {:a nil :b false :c nil}) => nil
                           (cli/single-arg {:a true :b false :c nil}) => '([:a true])
                           (cli/single-arg {:a "string" :b false :c nil}) => '([:a "string"])
                           (cli/single-arg {:a 42 :b false :c nil}) => '([:a 42])))
               (behavior "returns nil when given more than one truthy value"
                         (assertions
                           (cli/single-arg {:a "string" :b 42 :c nil}) => nil
                           ))
               )

(specification "check-migration-conformity"
               (provided "when database conforms to migration"
                         (d/db _) => :db
                         (c/conforms-to? _ _) => true
                         (behavior "retuns an empty set"
                                   (assertions
                                     (cli/check-migration-conformity :db migrations false) => #{}
                                     ))
                         )
               (provided
                 "when database does not conform to a migration"
                 (d/db _) => :db
                 (c/conforms-to? _ _) => false
                 (behavior
                   "tersely reports nonconforming migrations"
                   (assertions
                     (cli/check-migration-conformity :db migrations false) => #{:survey.migrations/survey-20151106
                                                                                :survey.migrations/survey-20151109
                                                                                :survey.migrations/survey-20151110
                                                                                :survey.migrations/survey-20151111}))
                 (behavior
                   "verbosely reports nonconforming migrations"
                   (assertions
                     (cli/check-migration-conformity :db migrations true) => (set migrations)))))

(specification "migration-status-all"
               (provided "when multiple databases conform to migrations"
                         (d/connect _) =2x=> nil
                         (mig/all-migrations _) =2x=> nil
                         (cli/check-migration-conformity _ _ _) =1x=> #{}
                         (cli/check-migration-conformity _ _ _) =1x=> #{}
                         (behavior "returns no migrations"
                                   (assertions
                                     (cli/migration-status-all {:db1 {} :db2 {}} false) => #{}))
                         )
               (provided "when multiple databases do not conform"
                         (d/connect _) =2x=> nil
                         (mig/all-migrations _) =2x=> nil
                         (cli/check-migration-conformity _ _ _) =1x=> #{:db1.migs/db1-20151106 :db1.migs/db1-20151107}
                         (cli/check-migration-conformity _ _ _) =1x=> #{:db2.migs/db2-20151106 :db2.migs/db2-20151107}
                         (behavior "returns all non-conforming migrations"
                                   (assertions
                                     (cli/migration-status-all {:db1 {} :db2 {}} false) => #{:db2.migs/db2-20151106
                                                                                             :db2.migs/db2-20151107
                                                                                             :db1.migs/db1-20151106
                                                                                             :db1.migs/db1-20151107}))
                         ))

(specification "migrate-all"
               (provided "when given a configuration with multiple databases"
                         (d/connect _) =2x=> nil
                         (mig/migrate _ _) =2x=> nil
                         (cd/run-core-schema _) =2x=> nil
                         (behavior "runs core schema and migrations for each database"
                                   (assertions
                                     (cli/migrate-all list-dbs-config) => nil
                                     ))))

(specification "main-handler"
               (provided "when passed the --migrate option with the 'all' keyword"
                         (cli/single-arg _) => '([:migrate all])
                         (cli/migrate-all _) =1x=> nil
                         (behavior "calls migrate-all"
                                   (assertions
                                     (cli/main-handler list-dbs-config ["--migrate" "all"]) => nil
                                     )))
               (provided "when passed the --migrate option with a specific database"
                         (cli/single-arg _) => '([:migrate s])
                         (cli/migrate-all _) =1x=> nil
                         (behavior "calls migrate-all"
                                   (assertions
                                     (cli/main-handler {} ["--migrate" "s"]) => nil
                                     )))
               (provided "when passed mutually-exclusive options"
                         (cli/fatal _) =2x=> _
                         (cli/single-arg _) =2x=> nil
                         (behavior "logs a fatal error"
                                   (assertions
                                     (cli/main-handler {} ["-m" "s" "--help"]) => nil
                                     (cli/main-handler {} ["-s" "s" "-l"]) => nil
                                     )))
               (provided "when passed the --verbose option"
                         (cli/migration-status-all _ _) =1x=> #{}
                         (behavior "passes verbose along with the main option"
                                   (assertions
                                     (cli/main-handler {} ["-v" "-s" "all"]) => nil
                                     )
                                   )
                         )
               (provided "when passed the --list-dbs option"
                         (cli/info _ _) =1x=> _
                         (behavior "lists databases available in the config"
                                   (assertions
                                     (cli/main-handler list-dbs-config ["-l"]) => ["survey" "some-other-db"]
                                     )
                                   )
                         )

               )
