(defproject untangled-datomic-helpers "0.2.2"
  :description "Support for Datomic, including extensions to schema validation and tracked Datomic schema migrations."
  :url ""
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.datomic/datomic-pro "0.9.5173" :exclusions [joda-time]]
                 [io.rkn/conformity "0.3.4" :exclusions [com.datomic/datomic-free]]
                 [datomic-helpers "1.0.0"]
                 [midje "1.6.3"]
                 [org.clojure/tools.namespace "0.2.10"]
                 [org.clojure/tools.nrepl "0.2.10"]
                 [com.stuartsierra/component "0.2.3"]
                 [com.taoensso/timbre "3.4.0"]
                 [org.clojure/java.classpath "0.2.2"]
                 [untangled-spec "0.1.1"]
                 [crypto-password "0.1.3"]
                 [com.rpl/specter "0.8.0"]
                 ]

  :repositories [["releases" "https://artifacts.buehner-fry.com/artifactory/internal-release"]
                 ["third-party" "https://artifacts.buehner-fry.com/artifactory/internal-3rdparty"]]

  :deploy-repositories [["releases" {:url           "https://artifacts.buehner-fry.com/artifactory/internal-release"
                                     :snapshots     false
                                     :sign-releases false}]
                        ["snapshots" {:url           "https://artifacts.buehner-fry.com/artifactory/internal-snapshots"
                                      :sign-releases false}]]

  :source-paths ["src"]
  :test-paths ["specs"]

  :test-refresh {:report  untangled-spec.report/untangled-report}

  :profiles
  {
   :dev {
         :source-paths ["env/dev"]
         :repl-options {
                       :init-ns user
                       }
         }})
