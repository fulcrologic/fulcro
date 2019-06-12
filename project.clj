(defproject com.fulcrologic/fulcro "3.0.0-alpha-5"
  :description "A library for building full-stack SPA webapps in Clojure and Clojurescript"
  :url "https://github.com/fulcrologic/fulcro"
  :lein-min-version "2.8.1"
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}

  :source-paths ["src/main"]
  :jar-exclusions [#"public/.*" #"private/.*"]
  :resource-paths ["resources"]
  :test-paths ["src/test"]
  :jvm-opts ["-XX:-OmitStackTraceInFastThrow" "-Xmx2024m" "-Xms1512m"]
  :clean-targets ^{:protect false} ["resources/private/js" "resources/public/js" "target"]

  :plugins [[lein-tools-deps "0.4.1"]]
  :middleware [lein-tools-deps.plugin/resolve-dependencies-with-deps-edn]
  :lein-tools-deps/config {:config-files [:install :user :project]})
