(defproject fulcrologic/fulcro "2.8.0"
  :description "A library for building full-stack SPA webapps in Clojure and Clojurescript"
  :url ""
  :lein-min-version "2.8.1"
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.0" :scope "provided"]
                 [org.clojure/clojurescript "1.10.439" :scope "provided"]

                 [cljsjs/react "16.6.0-0"]
                 [cljsjs/react-dom "16.6.0-0"]
                 [cljsjs/react-dom-server "16.6.0-0"]
                 [com.cognitect/transit-clj "0.8.313"]
                 [com.cognitect/transit-cljs "0.8.256"]
                 [org.clojure/core.async "0.4.490"]
                 [com.stuartsierra/component "0.3.2"]
                 [garden "1.3.6"]

                 ;; In case someone is still using 1.8
                 [clojure-future-spec "1.9.0-beta4"]

                 ;; Dynamic dependencies. You must require these if you use fulcro server extensions.
                 [http-kit "2.3.0" :scope "provided"]
                 [ring/ring-core "1.6.3" :scope "provided" :exclusions [commons-codec]]
                 [bk/ring-gzip "0.3.0" :scope "provided"]
                 [bidi "2.1.5" :scope "provided"]
                 [com.taoensso/sente "1.14.0-RC1" :scope "provided"]

                 ;; test deps
                 [fulcrologic/fulcro-spec "2.2.0-1" :scope "test" :exclusions [fulcrologic/fulcro]]
                 [lein-doo "0.1.10" :scope "test"]
                 [com.ibm.icu/icu4j "62.1" :scope "test"]
                 [org.clojure/test.check "0.10.0-alpha3" :scope "test"]
                 [cljsjs/enzyme "3.8.0" :scope "test"]]

  :source-paths ["src/main"]
  :jar-exclusions [#"public/.*" #"private/.*"]
  :resource-paths ["resources"]
  :test-paths ["src/test"]

  :jvm-opts ["-XX:-OmitStackTraceInFastThrow" "-Xmx2024m" "-Xms1512m"]
  :clean-targets ^{:protect false} ["resources/private/js" "resources/public/js" "target" "docs/js/book"]

  :plugins [[com.jakemccrary/lein-test-refresh "0.22.0"]]

  :test-refresh {:report       fulcro-spec.reporters.terminal/fulcro-report
                 :changes-only false
                 :with-repl    true}
  :test-selectors {:focused :focused}

  :profiles {:book {:dependencies [[devcards "0.2.5" :exclusions [org.clojure/clojure cljsjs/react cljsjs/react-dom]]
                                   [fulcrologic/fulcro-inspect "2.2.1" :exclusions [fulcrologic/fulcro]]
                                   [cljsjs/d3 "4.12.0-0"]
                                   [cljsjs/victory "0.24.2-0"]
                                   [hickory "0.7.1"]
                                   [com.rpl/specter "1.1.2"]
                                   [org.flywaydb/flyway-core "4.2.0"]]}
             :dev  {:source-paths ["src/dev" "src/main" "src/cards" "src/test" "src/book"]
                    :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                    :dependencies [[binaryage/devtools "0.9.10"]
                                   [com.rpl/specter "1.1.2"] ; used by book demos
                                   [devcards "0.2.4" :exclusions [org.clojure/clojure cljsjs/react cljsjs/react-dom]]
                                   [fulcrologic/fulcro-inspect "2.2.1" :exclusions [fulcrologic/fulcro]]
                                   [com.cemerick/piggieback "0.2.2"]
                                   [figwheel-sidecar "0.5.15"]
                                   [cljsjs/d3 "4.12.0-0"]
                                   [cljsjs/victory "0.24.2-0"]
                                   [hickory "0.7.1"]
                                   [org.flywaydb/flyway-core "4.2.0"]
                                   [org.clojure/tools.namespace "0.3.0-alpha4"]
                                   [thheller/shadow-cljs "2.7.8"]
                                   [cljsjs/codemirror "5.8.0-0"]
                                   [org.clojure/tools.nrepl "0.2.13"]]}})
