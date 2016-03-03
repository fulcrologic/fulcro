(defproject navis/untangled-server "0.4.6-SNAPSHOT"
  :description "Library for creating Untangled web servers"
  :url ""
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [org.clojure/math.combinatorics "0.1.1"]
                 [org.clojure/tools.namespace "0.2.10"]
                 [org.omcljs/om "1.0.0-alpha30"]
                 [http-kit "2.1.19"]
                 [environ "1.0.0"]
                 [bidi "1.21.1"]
                 [ring/ring-defaults "0.1.5"]
                 [bk/ring-gzip "0.1.1"]
                 [commons-codec "1.6"]
                 [com.stuartsierra/component "0.3.1"]
                 [com.taoensso/timbre "4.3.1"]
                 [org.clojure/java.classpath "0.2.2"]
                 [navis/untangled-spec "0.3.5" :scope "test" :exclusions [org.clojure/google-closure-library-third-party org.clojure/google-closure-library io.aviso/pretty org.clojure/clojurescript]]
                 [navis/untangled-datomic "0.4.4" :scope "test"]
                 [com.datomic/datomic-free "0.9.5206" :scope "test" :exclusions [joda-time]]
                 [com.rpl/specter "0.8.0"]]

  :plugins [[com.jakemccrary/lein-test-refresh "0.14.0"]]

  :source-paths ["src"]
  :test-paths ["specs" "specs/config"]
  :resource-paths ["src" "resources"]

  :jvm-opts ["-server" "-Xmx1024m" "-Xms512m" "-XX:-OmitStackTraceInFastThrow"]

  :test-refresh {:report       untangled-spec.reporters.terminal/untangled-report
                 :changes-only true}

  :test-selectors {:focused :focused}

  :profiles
  {
   :dev {
         :source-paths ["env/dev"]
         :repl-options {:init-ns user}}})
