(ns util.cli-spec
  (:require [untangled.util.cli :as cli]
            [clojure.test :as t :refer (are is deftest with-test run-tests testing do-report)]
            [taoensso.timbre :as timbre]
            [datomic.api :as d]
            [untangled.datomic-schema.migration :as mig]
            [untangled.components.database :as cd]
            [io.rkn.conformity :as c]
            [untangled-spec.core :refer [specification provided behavior assertions]]))

(def list-dbs-config {:survey        {:url       "datomic:mem://survey"
                                      :schema    "survey.migrations"
                                      :auto-drop false}
                      :some-other-db {}})

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
               (provided "when database does not conform to a migration"
                         (d/db _) => :db
                         (c/conforms-to? _ _) => false
                         (behavior "tersely reports nonconforming migrations"
                                   (assertions
                                     (cli/check-migration-conformity :db migrations false) => #{:survey.migrations/survey-20151106
                                                                                                :survey.migrations/survey-20151109
                                                                                                :survey.migrations/survey-20151110
                                                                                                :survey.migrations/survey-20151111}
                                     ))
                         (behavior "verbosely reports nonconforming migrations"
                                   (assertions
                                     (cli/check-migration-conformity :db migrations true) => (set migrations)
                                     ))
                         ))

(specification "main-handler"
               (provided "when passed the --migrate option"
                         (cli/single-arg _) => '([:migrate s])
                         (mig/migrate _ _) => nil
                         (cd/run-core-schema _) => nil
                         (behavior "applies core-schema"
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
                         (cli/check-migration-conformity _ _ _) =1x=> #{}
                         (mig/all-migrations _) =1x=> _
                         (behavior "passes verbose along with the main option"
                                   (assertions
                                     (cli/main-handler {} ["-v" "-s" "some-db"]) => nil
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
