(defproject awkay/untangled "1.0.0-beta2-SNAPSHOT"
  :description "A library for building full-stack SPA webapps in Clojure and Clojurescript"
  :url ""
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha17" :scope "provided"]
                 [org.clojure/clojurescript "1.9.671" :scope "provided"]
                 [org.clojure/spec.alpha "0.1.123"]
                 [devcards "0.2.3" :scope "provided" :exclusions [cljsjs/react-dom cljsjs/react]]
                 [org.omcljs/om "1.0.0-beta1"]
                 [lein-doo "0.1.7" :scope "test"]
                 [http-kit "2.2.0"]
                 [ring/ring-defaults "0.2.3"]
                 [ring/ring-core "1.6.1"]
                 [bk/ring-gzip "0.2.1"]
                 [commons-codec "1.10"]
                 [com.stuartsierra/component "0.3.2"]
                 [com.taoensso/timbre "4.10.0"]
                 [awkay/untangled-spec "1.0.0-beta1" :scope "test" :exclusions [awkay/untangled]]
                 [org.clojure/core.async "0.3.443" :exclusions [org.clojure/tools.reader]]
                 [com.ibm.icu/icu4j "58.2"]                 ; needed for i18n on server-side rendering
                 [bidi "2.1.1"]
                 [com.taoensso/sente "1.11.0"]
                 [com.rpl/specter "1.0.1"]
                 [garden "1.3.2"]
                 [org.clojure/test.check "0.9.0" :scope "test"]]

  :source-paths ["src/main"]
  :resource-paths ["resources"]
  :test-paths ["src/test"]
  :jar-exclusions [#"public/.*" #"private/.*"]

  :jvm-opts ["-XX:-OmitStackTraceInFastThrow" "-Xmx1024m" "-Xms512m"]
  :clean-targets ^{:protect false} ["resources/private/js" "resources/public/js" "target"]

  :plugins [[lein-cljsbuild "1.1.6"]
            [lein-doo "0.1.7"]
            [com.jakemccrary/lein-test-refresh "0.19.0"]]

  :test-refresh {:report       untangled-spec.reporters.terminal/untangled-report
                 :changes-only false
                 :with-repl    true}
  :test-selectors {:focused :focused}

  :doo {:build "automated-tests"
        :paths {:karma "node_modules/karma/bin/karma"}}

  :figwheel {:server-port 8080}

  :cljsbuild {:builds
              [{:id           "test"
                :source-paths ["src/main" "src/test"]
                :figwheel     {:on-jsload "untangled.test-main/spec-report"}
                :compiler     {:main                 untangled.test-main
                               :output-to            "resources/public/js/test.js"
                               :output-dir           "resources/public/js/test"
                               :recompile-dependents true
                               :parallel-build       false
                               :preloads             [devtools.preload]
                               :asset-path           "js/test"
                               :optimizations        :none}}
               {:id           "cards"
                :source-paths ["src/main" "src/cards"]
                :figwheel     {:devcards true}
                :compiler     {:main                 untangled.client.card-ui
                               :output-to            "resources/public/js/cards.js"
                               :output-dir           "resources/public/js/cards"
                               :asset-path           "js/cards"
                               :preloads             [devtools.preload]
                               :parallel-build       true
                               :source-map-timestamp true
                               :optimizations        :none}}
               {:id           "demos"
                :source-paths ["src/main" "src/dev" "src/demos"]
                :figwheel     {:devcards true}
                :compiler     {:main                 cards.card_ui
                               :devcards             true
                               :output-to            "resources/public/js/demos.js"
                               :output-dir           "resources/public/js/demos"
                               :asset-path           "js/demos"
                               :preloads             [devtools.preload]
                               :parallel-build       true
                               :source-map-timestamp true
                               :optimizations        :none}}
               {:id           "devguide"
                :figwheel     {:devcards true}
                :source-paths ["src/main" "src/devguide"]
                :compiler     {:main           untangled-devguide.guide
                               :asset-path     "js/devguide"
                               :devcards true
                               :output-to      "resources/public/js/devguide.js"
                               :output-dir     "resources/public/js/devguide"
                               :preloads       [devtools.preload]
                               :parallel-build true
                               :foreign-libs   [{:provides ["cljsjs.codemirror.addons.closebrackets"]
                                                 :requires ["cljsjs.codemirror"]
                                                 :file     "resources/public/codemirror/closebrackets-min.js"}
                                                {:provides ["cljsjs.codemirror.addons.matchbrackets"]
                                                 :requires ["cljsjs.codemirror"]
                                                 :file     "resources/public/codemirror/matchbrackets-min.js"}]}}
               {:id           "automated-tests"
                :source-paths ["src/test" "src/main"]
                :compiler     {:output-to      "resources/private/js/unit-tests.js"
                               :main           untangled.automated-test-main
                               :output-dir     "resources/private/js/unit-tests"
                               :asset-path     "js/unit-tests"
                               :parallel-build true
                               :optimizations  :none}}]}

  :profiles {:dev {:source-paths ["src/dev" "src/main" "src/cards" "src/test" "src/devguide" "src/demos"]
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                   :dependencies [[binaryage/devtools "0.9.4"]
                                  [com.cemerick/piggieback "0.2.1"]
                                  [figwheel-sidecar "0.5.11"]
                                  [cljsjs/d3 "3.5.7-1"]
                                  [cljsjs/victory "0.9.0-0"]
                                  [hickory "0.7.1"]
                                  [org.flywaydb/flyway-core "4.0.3"]
                                  [com.layerware/hugsql "0.4.7"]
                                  [org.clojure/tools.namespace "0.3.0-alpha4"]
                                  [cljsjs/codemirror "5.8.0-0"]
                                  [org.clojure/tools.nrepl "0.2.13"]]}})
