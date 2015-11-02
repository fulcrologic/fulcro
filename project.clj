(defproject untangled-datomic-helpers "0.1.0-SNAPSHOT"
  :description "Support for Datomic, including extensions to schema validation and tracked Datomic schema migrations."
  :url ""
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.datomic/datomic-pro "0.9.5173" :exclusions [joda-time]]
                 [io.rkn/conformity "0.3.4" :exclusions [com.datomic/datomic-free]]
                 [datomic-helpers "1.0.0"]
                 [untangled-spec "0.1.1"]
                 [org.clojure/tools.namespace "0.2.10"]
                 [org.clojure/tools.nrepl "0.2.10"]
                 ]

  :source-paths ["src"]

  :profiles
  {
   :dev {
         :source-paths ["env/dev" "src" "specs"]
         :repl-options {
                       :init-ns user
                       :port 7001
                       }
         }})
