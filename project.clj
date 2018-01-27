(defproject fulcrologic/fulcro "2.1.2"
  :description "A library for building full-stack SPA webapps in Clojure and Clojurescript"
  :url ""
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/clojurescript "1.9.946" :scope "provided"]

                 [cljsjs/react "15.6.2-1"]
                 [cljsjs/react-dom "15.6.2-1"]
                 [com.cognitect/transit-clj "0.8.300"]
                 [com.cognitect/transit-cljs "0.8.243"]
                 [org.clojure/core.async "0.3.443" :exclusions [org.clojure/tools.reader]]
                 [com.ibm.icu/icu4j "59.1"]                 ; needed for i18n on server-side rendering

                 [clojure-future-spec "1.9.0-beta4"]

                 ;; easy-server and server
                 [http-kit "2.2.0"]
                 [ring/ring-core "1.6.3" :exclusions [commons-codec]]
                 [bk/ring-gzip "0.2.1"]
                 [com.stuartsierra/component "0.3.2"]
                 [com.taoensso/timbre "4.10.0"]
                 [bidi "2.1.2"]                             ; needed by easy-server
                 [com.taoensso/sente "1.11.0"]              ; websockets

                 ;; test deps
                 [fulcrologic/fulcro-spec "2.0.0-beta3" :scope "test" :exclusions [fulcrologic/fulcro]]
                 [lein-doo "0.1.8" :scope "test"]
                 [org.clojure/test.check "0.10.0-alpha1" :scope "test"]]

  :source-paths ["src/main"]
  :jar-exclusions [#"public/.*" #"private/.*"]
  :resource-paths ["resources"]
  :test-paths ["src/test"]

  :jvm-opts ["-XX:-OmitStackTraceInFastThrow" "-Xmx1024m" "-Xms512m"]
  :clean-targets ^{:protect false} ["resources/private/js" "resources/public/js" "target"]

  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-doo "0.1.8"]
            [com.jakemccrary/lein-test-refresh "0.21.1"]]

  :test-refresh {:report       fulcro-spec.reporters.terminal/fulcro-report
                 :changes-only false
                 :with-repl    true}
  :test-selectors {:focused :focused}

  :doo {:build "automated-tests"
        :paths {:karma "node_modules/karma/bin/karma"}}

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
                               :parallel-build       true
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
                               :parallel-build       true
                               :source-map-timestamp true
                               :optimizations        :none}}
               {:id           "book"
                :source-paths ["src/main" "src/book"]
                :figwheel     true
                :compiler     {:output-dir     "resources/public/js/book"
                               :asset-path     "js/book"
                               :preloads       [devtools.preload]
                               :modules        {:entry-point {:output-to "resources/public/js/book.js"
                                                              :entries   #{book.main}}
                                                ; For the dynamic i18n loading demo
                                                :de          {:output-to "resources/public/js/book/de.js"
                                                              :entries   #{translations.de}}
                                                :es-MX       {:output-to "resources/public/js/book/es-MX.js"
                                                              :entries   #{translations.es-MX}}
                                                ; For the dynamic code splitting demo
                                                :main        {:output-to "resources/public/js/book/main-ui.js"
                                                              :entries   #{book.demos.dynamic-ui-main}}}
                               :parallel-build true}}
               {:id           "book-live"
                :source-paths ["src/main" "src/book"]
                :compiler     {:output-dir     "docs/js/book"
                               :asset-path     "js/book"
                               :optimizations  :advanced
                               :modules        {:entry-point {:output-to "docs/js/book.js"
                                                              :entries   #{book.main}}
                                                ; For the dynamic i18n loading demo
                                                :de          {:output-to "docs/js/book/de.js"
                                                              :entries   #{translations.de}}
                                                :es-MX       {:output-to "docs/js/book/es-MX.js"
                                                              :entries   #{translations.es-MX}}
                                                ; For the dynamic code splitting demo
                                                :main        {:output-to "docs/js/book/main-ui.js"
                                                              :entries   #{book.demos.dynamic-ui-main}}}
                               :parallel-build true}}
               {:id           "tutorial"
                :figwheel     {:devcards true}
                :source-paths ["src/main" "src/tutorial"]
                :compiler     {:main           fulcro-tutorial.main
                               :asset-path     "js/tutorial"
                               :devcards       true
                               :output-to      "resources/public/js/tutorial.js"
                               :output-dir     "resources/public/js/tutorial"
                               :preloads       [devtools.preload]
                               :parallel-build true
                               :foreign-libs   [{:provides ["cljsjs.codemirror.addons.closebrackets"]
                                                 :requires ["cljsjs.codemirror"]
                                                 :file     "resources/public/codemirror/closebrackets-min.js"}
                                                {:provides ["cljsjs.codemirror.addons.matchbrackets"]
                                                 :requires ["cljsjs.codemirror"]
                                                 :file     "resources/public/codemirror/matchbrackets-min.js"}]}}
               {:id           "tutorial-live"
                :source-paths ["src/main" "src/tutorial"]
                :compiler     {:main           fulcro-tutorial.main
                               :devcards       true
                               :asset-path     "js"
                               :output-to      "docs/js/tutorial.js"
                               :output-dir     "resources/public/js/tutorial-live"
                               :parallel-build true
                               :optimizations  :advanced
                               :foreign-libs   [{:provides ["cljsjs.codemirror.addons.closebrackets"]
                                                 :requires ["cljsjs.codemirror"]
                                                 :file     "resources/public/codemirror/closebrackets-min.js"}
                                                {:provides ["cljsjs.codemirror.addons.matchbrackets"]
                                                 :requires ["cljsjs.codemirror"]
                                                 :file     "resources/public/codemirror/matchbrackets-min.js"}]}}
               {:id           "automated-tests"
                :source-paths ["src/test" "src/main"]
                :compiler     {:output-to     "resources/private/js/unit-tests.js"
                               :main          fulcro.automated-test-main
                               :output-dir    "resources/private/js/unit-tests"
                               :asset-path    "js/unit-tests"
                               ;:parallel-build true
                               :optimizations :none}}]}

  :profiles {:book {:dependencies [[devcards "0.2.4" :exclusions [org.clojure/clojure cljsjs/react cljsjs/react-dom]]
                                   [fulcrologic/fulcro-css "2.0.0-beta1"] ; demos
                                   [fulcrologic/fulcro-inspect "2.0.0-alpha4" :exclusions [fulcrologic/fulcro]]
                                   [cljsjs/d3 "3.5.7-1"]
                                   [cljsjs/victory "0.9.0-0"]
                                   [hickory "0.7.1"]
                                   [com.rpl/specter "1.0.5"] ; only used in demos
                                   [org.flywaydb/flyway-core "4.2.0"]]}
             :dev  {:source-paths ["src/dev" "src/main" "src/cards" "src/test" "src/tutorial" "src/book"]
                    :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                    :dependencies [[binaryage/devtools "0.9.7"]
                                   [devcards "0.2.4" :exclusions [org.clojure/clojure cljsjs/react cljsjs/react-dom]]
                                   [fulcrologic/fulcro-css "2.0.0-beta1"] ; demos
                                   [fulcrologic/fulcro-inspect "2.0.0-alpha4" :exclusions [fulcrologic/fulcro]]
                                   [com.cemerick/piggieback "0.2.2"]
                                   [figwheel-sidecar "0.5.14"]
                                   [cljsjs/d3 "3.5.7-1"]
                                   [cljsjs/victory "0.9.0-0"]
                                   [hickory "0.7.1"]
                                   [com.rpl/specter "1.0.5"] ; only used in demos
                                   [org.flywaydb/flyway-core "4.2.0"]
                                   [org.clojure/tools.namespace "0.3.0-alpha4"]
                                   [cljsjs/codemirror "5.8.0-0"]
                                   [org.clojure/tools.nrepl "0.2.13"]]}})
