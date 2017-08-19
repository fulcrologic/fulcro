(defproject fulcrologic/fulcro "1.0.0-beta9-SNAPSHOT"
  :description "A library for building full-stack SPA webapps in Clojure and Clojurescript"
  :url ""
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/clojurescript "1.9.908" :scope "provided"]
                 [clojure-future-spec "1.9.0-alpha17"]
                 [org.omcljs/om "1.0.0-beta1"]
                 [http-kit "2.2.0"]
                 [ring/ring-core "1.6.2" :exclusions [commons-codec]]
                 [bk/ring-gzip "0.2.1"]
                 [com.stuartsierra/component "0.3.2"]
                 [com.taoensso/timbre "4.10.0"]
                 [org.clojure/core.async "0.3.443" :exclusions [org.clojure/tools.reader]]
                 [com.ibm.icu/icu4j "58.2"]                 ; needed for i18n on server-side rendering
                 [bidi "2.1.2"]
                 [com.taoensso/sente "1.11.0"]
                 [garden "1.3.2"]

                 [fulcrologic/fulcro-spec "1.0.0-beta8" :scope "test" :exclusions [fulcrologic/fulcro]]
                 [lein-doo "0.1.7" :scope "test"]
                 [org.clojure/test.check "0.10.0-alpha1" :scope "test"]]

  :source-paths ["src/main"]
  :resource-paths ["resources"]
  :test-paths ["src/test"]
  :jar-exclusions [#"public/.*" #"private/.*"]

  :jvm-opts ["-XX:-OmitStackTraceInFastThrow" "-Xmx1024m" "-Xms512m"]
  :clean-targets ^{:protect false} ["resources/private/js" "resources/public/js" "target"]

  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-doo "0.1.7"]
            [com.jakemccrary/lein-test-refresh "0.19.0"]]

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
                               :parallel-build       false
                               :preloads             [devtools.preload]
                               :asset-path           "js/test"
                               :optimizations        :none}}
               {:id           "cards"
                :source-paths ["src/main" "src/cards"]
                :figwheel     {:devcards true}
                :compiler     {:main                 fulcro.client.card-ui
                               :output-to            "resources/public/js/cards.js"
                               :output-dir           "resources/public/js/cards"
                               :asset-path           "js/cards"
                               :preloads             [devtools.preload]
                               :parallel-build       true
                               :source-map-timestamp true
                               :optimizations        :none}}
               {:id           "demos"
                :source-paths ["src/main" "src/demos"]
                :figwheel     {:devcards true}
                :compiler     {:devcards      true
                               :output-dir    "resources/public/js/demos"
                               :asset-path    "js/demos"
                               :preloads      [devtools.preload]
                               :modules       {:entry-point {:output-to "resources/public/js/demos/demos.js"
                                                             :entries   #{cards.card-ui}}
                                               :de          {:output-to "resources/public/js/demos/de.js"
                                                             :entries   #{translations.de}}
                                               :es-MX       {:output-to "resources/public/js/demos/es-MX.js"
                                                             :entries   #{translations.es-MX}}
                                               :main        {:output-to "resources/public/js/demos/main-ui.js"
                                                             :entries   #{recipes.dynamic-ui-main}}}
                               :optimizations :none}}
               {:id           "demo-i18n"
                :source-paths ["src/main" "src/demos"]
                :compiler     {:devcards      true
                               :output-dir    "resources/public/js/demo-i18n"
                               :asset-path    "js/demo-i18n"
                               :output-to     "resources/public/js/demo-i18n.js"
                               :main          cards.card-ui
                               :optimizations :whitespace}}
               ; REMEBER TO USE devguide profile!!! Use `make guide`
               {:id           "devguide-live"
                :source-paths ["src/main" "src/devguide"]
                :compiler     {:main          fulcro-devguide.guide
                               :asset-path    "js"
                               :optimizations :advanced
                               :devcards      true
                               :output-to     "docs/js/guide.js"
                               :output-dir    "docs/js"
                               :foreign-libs  [{:provides ["cljsjs.codemirror.addons.closebrackets"]
                                                :requires ["cljsjs.codemirror"]
                                                :file     "resources/public/codemirror/closebrackets-min.js"}
                                               {:provides ["cljsjs.codemirror.addons.matchbrackets"]
                                                :requires ["cljsjs.codemirror"]
                                                :file     "resources/public/codemirror/matchbrackets-min.js"}]}}
               {:id           "devguide"
                :figwheel     {:devcards true}
                :source-paths ["src/main" "src/devguide"]
                :compiler     {:main           fulcro-devguide.guide
                               :asset-path     "js/devguide"
                               :devcards       true
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
               {:id           "i18n-extraction"
                :source-paths ["src/main" "src/test"]
                :compiler     {:output-to      "i18n/i18n.js"
                               :main           fulcro.automated-test-main
                               :output-dir     "resources/private/js/i18n"
                               :asset-path     "js/i18n"
                               :parallel-build true
                               :optimizations  :whitespace}}
               {:id           "automated-tests"
                :source-paths ["src/test" "src/main"]
                :compiler     {:output-to      "resources/private/js/unit-tests.js"
                               :main           fulcro.automated-test-main
                               :output-dir     "resources/private/js/unit-tests"
                               :asset-path     "js/unit-tests"
                               :parallel-build true
                               :optimizations  :none}}]}

  :profiles {:production {:source-paths ["src/main" "src/devguide"]
                          :dependencies [[devcards "0.2.3" :exclusions [cljsjs/react-dom cljsjs/react]]
                                         [cljsjs/d3 "3.5.7-1"]
                                         [cljsjs/victory "0.9.0-0"]
                                         [hickory "0.7.1"]
                                         [fulcrologic/fulcro-css "1.0.0"] ; demos
                                         [com.rpl/specter "1.0.1"] ; only used in demos
                                         [org.flywaydb/flyway-core "4.0.3"]
                                         [com.layerware/hugsql "0.4.7"]
                                         [org.clojure/tools.namespace "0.3.0-alpha4"]
                                         [cljsjs/codemirror "5.8.0-0"]
                                         [org.clojure/tools.nrepl "0.2.13"]]}
             :dev        {:source-paths ["src/dev" "src/main" "src/cards" "src/test" "src/devguide" "src/demos"]
                          :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                          :dependencies [[binaryage/devtools "0.9.4"]
                                         [devcards "0.2.3" :exclusions [cljsjs/react-dom cljsjs/react]]
                                         [fulcrologic/fulcro-css "1.0.0"] ; demos
                                         [com.cemerick/piggieback "0.2.1"]
                                         [figwheel-sidecar "0.5.13"]
                                         [cljsjs/d3 "3.5.7-1"]
                                         [cljsjs/victory "0.9.0-0"]
                                         [hickory "0.7.1"]
                                         [com.rpl/specter "1.0.1"] ; only used in demos
                                         [org.flywaydb/flyway-core "4.0.3"]
                                         [com.layerware/hugsql "0.4.7"]
                                         [org.clojure/tools.namespace "0.3.0-alpha4"]
                                         [cljsjs/codemirror "5.8.0-0"]
                                         [org.clojure/tools.nrepl "0.2.13"]]}})
