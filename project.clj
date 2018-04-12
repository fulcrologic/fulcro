(defproject fulcrologic/fulcro "2.5.0-beta2-SNAPSHOT"
  :description "A library for building full-stack SPA webapps in Clojure and Clojurescript"
  :url ""
  :lein-min-version "2.8.1"
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.9.0" :scope "provided"]
                 [org.clojure/clojurescript "1.10.238" :scope "provided"]

                 [cljsjs/react "15.6.2-4"]
                 [cljsjs/react-dom "15.6.2-4"]
                 [cljsjs/react-dom-server "15.6.2-4"]
                 [com.cognitect/transit-clj "0.8.300"]
                 [com.cognitect/transit-cljs "0.8.243"]
                 [org.clojure/core.async "0.4.474"]
                 [com.stuartsierra/component "0.3.2"]
                 [garden "1.3.4"]

                 ;; In case someone is still using 1.8
                 [clojure-future-spec "1.9.0-beta4"]

                 ;; Dynamic dependencies. You must require these if you use fulcro server extensions.
                 [http-kit "2.2.0" :scope "provided"]
                 [ring/ring-core "1.6.3" :scope "provided" :exclusions [commons-codec]]
                 [bk/ring-gzip "0.2.1" :scope "provided"]
                 [bidi "2.1.3" :scope "provided"]
                 [com.taoensso/sente "1.12.0" :scope "provided"]

                 ;; test deps
                 [fulcrologic/fulcro-spec "2.1.0-1" :scope "test" :exclusions [fulcrologic/fulcro]]
                 [lein-doo "0.1.10" :scope "test"]
                 [com.ibm.icu/icu4j "60.2" :scope "test"]
                 [org.clojure/test.check "0.10.0-alpha1" :scope "test"]]

  :source-paths ["src/main"]
  :jar-exclusions [#"public/.*" #"private/.*"]
  :resource-paths ["resources"]
  :test-paths ["src/test"]

  :jvm-opts ~(let [version (System/getProperty "java.version")
                   base-options ["-XX:-OmitStackTraceInFastThrow" "-Xmx1024m" "-Xms512m"]
                   [major _ _] (clojure.string/split version #"\.")]
               (if (>= (Integer. major) 9)
                 (conj base-options "--add-modules" "java.xml.bind")
                 base-options))
  :clean-targets ^{:protect false} ["resources/private/js" "resources/public/js" "target" "docs/js/book"]

  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-doo "0.1.10"]
            [com.jakemccrary/lein-test-refresh "0.21.1"]]

  :test-refresh {:report       fulcro-spec.reporters.terminal/fulcro-report
                 :changes-only false
                 :with-repl    true}
  :test-selectors {:focused :focused}

  :doo {:build "automated-tests"
        :debug true
        :paths {:karma "node_modules/karma/bin/karma"}
        :karma {:config {"files" ^:prepend ["resources/public/intl-messageformat-with-locales.min.js"]}}}

  :figwheel {:server-port     8080
             :validate-config false}

  :cljsbuild {:builds
              [{:id           "test"
                :source-paths ["src/main" "src/test"]
                :figwheel     {:on-jsload "fulcro.test-main/spec-report"}
                :compiler     {:main                 fulcro.test-main
                               :output-to            "resources/public/js/test.js"
                               :output-dir           "resources/public/js/test"
                               :recompile-dependents true
                               ;:parallel-build       true
                               ;:verbose              true
                               ;:compiler-stats       true
                               :preloads             [devtools.preload]
                               :asset-path           "js/test"
                               :optimizations        :none}}
               {:id           "cards"
                :source-paths ["src/main" "src/cards"]
                :figwheel     {:devcards true}
                :compiler     {:main                 fulcro.democards.card-ui
                               :output-to            "resources/public/js/cards.js"
                               :output-dir           "resources/public/js/cards"
                               :asset-path           "js/cards"
                               :preloads             [devtools.preload]
                               ;:parallel-build       true
                               ;:verbose              true
                               ;:compiler-stats       true
                               :source-map-timestamp true
                               :optimizations        :none}}
               {:id           "cards-live"
                :source-paths ["src/main" "src/cards"]
                :compiler     {:main          fulcro.democards.card-ui
                               :output-to     "resources/public/js/cards.min.js"
                               :output-dir    "resources/public/js/cards-live"
                               :asset-path    "js/cards-live"
                               :devcards      true
                               :verbose       true
                               :optimizations :advanced}}
               {:id           "book"
                :source-paths ["src/main" "src/book"]
                :figwheel     true
                :compiler     {:output-dir "resources/public/js/book"
                               :asset-path "js/book"
                               :preloads   [devtools.preload]
                               :modules    {:entry-point {:output-to "resources/public/js/book.js"
                                                          :entries   #{book.main}}
                                            ; For the dynamic code splitting demo
                                            :main        {:output-to "resources/public/js/book/main-ui.js"
                                                          :entries   #{book.demos.dynamic-ui-main}}}
                               #_#_:parallel-build true}}
               {:id           "book-live"
                :source-paths ["src/main" "src/book"]
                :compiler     {:output-dir    "docs/js/book"
                               :asset-path    "js/book"
                               :optimizations :advanced
                               :modules       {:entry-point {:output-to "docs/js/book.js"
                                                             :entries   #{book.main}}
                                               ; For the dynamic code splitting demo
                                               :main        {:output-to "docs/js/book/main-ui.js"
                                                             :entries   #{book.demos.dynamic-ui-main}}}}}
               {:id           "automated-tests"
                :source-paths ["src/test" "src/main"]
                :compiler     {:output-to     "resources/private/js/unit-tests.js"
                               :main          fulcro.automated-test-main
                               :output-dir    "resources/private/js/unit-tests"
                               :asset-path    "js/unit-tests"
                               ;:parallel-build true
                               :optimizations :none}}]}

  :profiles {:book {:dependencies [[devcards "0.2.4" :exclusions [org.clojure/clojure cljsjs/react cljsjs/react-dom]]
                                   [fulcrologic/fulcro-inspect "2.0.1" :exclusions [fulcrologic/fulcro]]
                                   [cljsjs/d3 "3.5.7-1"]
                                   [cljsjs/victory "0.9.0-0"]
                                   [hickory "0.7.1"]
                                   [com.rpl/specter "1.1.0"]
                                   [org.flywaydb/flyway-core "4.2.0"]]}
             :dev  {:source-paths ["src/dev" "src/main" "src/cards" "src/test" "src/tutorial" "src/book"]
                    :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                    :dependencies [[binaryage/devtools "0.9.9"]
                                   [com.rpl/specter "1.1.0"] ; used by book demos
                                   [devcards "0.2.4" :exclusions [org.clojure/clojure cljsjs/react cljsjs/react-dom]]
                                   [fulcrologic/fulcro-inspect "2.0.1" :exclusions [fulcrologic/fulcro]]
                                   [com.cemerick/piggieback "0.2.2"]
                                   [figwheel-sidecar "0.5.15"]
                                   [cljsjs/d3 "3.5.7-1"]
                                   [cljsjs/victory "0.9.0-0"]
                                   [hickory "0.7.1"]
                                   [org.flywaydb/flyway-core "4.2.0"]
                                   [org.clojure/tools.namespace "0.3.0-alpha4"]
                                   [cljsjs/codemirror "5.8.0-0"]
                                   [org.clojure/tools.nrepl "0.2.13"]]}})
