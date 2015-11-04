(defproject untangled-datomic-helpers "0.1.1-SNAPSHOT"
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
                 [crypto-password "0.1.3"]
                 ]

  :source-paths ["src"]
  :test-paths ["specs"]

  :profiles
  {
   :dev {
         :source-paths ["env/dev"]
         :repl-options {
                       :init-ns user
                       }
         }})
