(defproject untangled-datomic-helpers "0.2.7"
  :description "Support for Datomic, including extensions to schema validation and tracked Datomic schema migrations."
  :url ""
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [com.datomic/datomic-pro "0.9.5206" :exclusions [joda-time] :scope "provided"]
                 [io.rkn/conformity "0.3.4" :exclusions [com.datomic/datomic-free]]
                 [datomic-helpers "1.0.0"]
                 [org.clojure/tools.namespace "0.2.10"]
                 [org.graylog2/gelfclient "1.0.0"]
                 [commons-codec "1.6"]
                 [com.stuartsierra/component "0.2.3"]
                 [com.taoensso/timbre "4.1.4"]
                 [org.clojure/java.classpath "0.2.2"]
                 [untangled-spec "0.3.0" :scope "test" :exclusions [org.clojure/google-closure-library-third-party org.clojure/google-closure-library io.aviso/pretty org.clojure/clojurescript]]
                 [crypto-password "0.1.3" :scope "test"]
                 [com.rpl/specter "0.8.0"]
                 [democracyworks/datomic-toolbox "2.0.0" :exclusions [com.datomic/datomic-pro]]]

  :repositories [["releases" "https://artifacts.buehner-fry.com/artifactory/internal-release"]
                 ["third-party" "https://artifacts.buehner-fry.com/artifactory/internal-3rdparty"]]

  :deploy-repositories [["releases" {:url           "https://artifacts.buehner-fry.com/artifactory/internal-release"
                                     :snapshots     false
                                     :sign-releases false}]
                        ["snapshots" {:url           "https://artifacts.buehner-fry.com/artifactory/internal-snapshots"
                                      :sign-releases false}]]

  :source-paths ["src"]
  :test-paths ["specs"]
  :resource-paths ["src" "resources"]

  :test-refresh {:report untangled-spec.reporters.terminal/untangled-report
                 :changes-only true}

  :test-selectors {:focused :focused}

  :profiles
  {
   :dev {
         :source-paths ["env/dev"]
         :repl-options {:init-ns user}}})
