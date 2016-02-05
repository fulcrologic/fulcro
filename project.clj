(defproject navis/untangled-server "0.4.3-SNAPSHOT"
  :description "Library for creating Untangled web servers"
  :url ""
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [io.rkn/conformity "0.3.4" :exclusions [com.datomic/datomic-free]]
                 [datomic-helpers "1.0.0"]
                 [org.clojure/math.combinatorics "0.1.1"]
                 [org.clojure/tools.namespace "0.2.10"]
                 [org.graylog2/gelfclient "1.0.0"]
                 [org.omcljs/om "1.0.0-alpha30"]
                 [http-kit "2.1.19"]
                 [environ "1.0.0"]
                 [bidi "1.21.1"]
                 [com.navis/common "0.1.21"]
                 [ring/ring-defaults "0.1.5"]
                 [bk/ring-gzip "0.1.1"]
                 [commons-codec "1.6"]
                 [com.stuartsierra/component "0.2.3"]
                 [com.taoensso/timbre "4.1.4"]
                 [org.clojure/java.classpath "0.2.2"]
                 [untangled-spec "0.3.1" :scope "test" :exclusions [org.clojure/google-closure-library-third-party org.clojure/google-closure-library io.aviso/pretty org.clojure/clojurescript]]
                 [navis/untangled-datomic "0.4.1" :scope "test"]
                 [crypto-password "0.1.3" :scope "test"]
                 [com.rpl/specter "0.8.0"]]

  :repositories [["releases" "https://artifacts.buehner-fry.com/artifactory/release"]]

  :deploy-repositories [["releases" {:url           "https://artifacts.buehner-fry.com/artifactory/navis-maven-release"
                                     :snapshots     false
                                     :sign-releases false}]
                        ["snapshots" {:url           "https://artifacts.buehner-fry.com/artifactory/navis-maven-snapshots"
                                      :sign-releases false}]]

  :source-paths ["src"]
  :test-paths ["specs"]
  :resource-paths ["src" "resources"]

  :test-refresh {:report       untangled-spec.reporters.terminal/untangled-report
                 :changes-only true}

  :test-selectors {:focused :focused}

  :profiles
  {
   :dev {
         :source-paths ["env/dev"]
         :repl-options {:init-ns user}}})
