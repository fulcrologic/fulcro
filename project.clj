(defproject untangled-web/untangled "1.0.0-SNAPSHOT"
  :description "A library for building full-stack SPA webapps"
  :url ""
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha14" :scope "provided"]
                 [org.clojure/clojurescript "1.9.494" :scope "provided"]
                 [com.lucasbradstreet/cljs-uuid-utils "1.0.2"]
                 [devcards "0.2.2" :scope "provided"]
                 [lein-doo "0.1.7" :scope "test"]
                 [http-kit "2.2.0"]
                 [ring/ring-defaults "0.2.3"]
                 [ring/ring-core "1.6.0-RC2"]
                 [bk/ring-gzip "0.2.1"]
                 [commons-codec "1.10"]
                 [com.stuartsierra/component "0.3.2"]
                 [com.taoensso/timbre "4.8.0"]
                 [navis/untangled-spec "1.0.0-alpha3" :scope "test"]
                 [org.clojure/core.async "0.3.442" :exclusions [org.clojure/tools.reader]]
                 [com.ibm.icu/icu4j "58.2"]                 ; needed for i18n on server-side rendering
                 [bidi "2.0.16"]                            ; todo make dynamic
                 [org.omcljs/om "1.0.0-alpha48" :scope "provided"]
                 [com.taoensso/sente "1.11.0"]
                 [org.clojure/test.check "0.9.0" :scope "test"]]

  :source-paths ["src/main"]
  :resource-paths ["resources"]

  :jvm-opts ["-XX:-OmitStackTraceInFastThrow" "-Xmx512m" "-Xms256m"]
  :clean-targets ^{:protect false} ["resources/private/js" "resources/public/js/cards" "resources/public/js/test" "resources/public/js/compiled" "target"]

  :plugins [[lein-cljsbuild "1.1.5"]
            [lein-doo "0.1.7"]
            [com.jakemccrary/lein-test-refresh "0.19.0"]]

  :test-paths ["src/test"]
  :test-refresh {:report       untangled-spec.reporters.terminal/untangled-report
                 :changes-only false
                 :with-repl    true}
  :test-selectors {:focused :focused}

  :doo {:build "automated-tests"
        :paths {:karma "node_modules/karma/bin/karma"}}

  :figwheel {:server-port 8080}

  :cljsbuild {:builds
              [{:id           "test"
                :source-paths ["src/main" "src/dev" "src/test"]
                :figwheel     {:on-jsload "cljs.user/spec-report"}
                :compiler     {:main                 cljs.user
                               :output-to            "resources/public/js/test/test.js"
                               :output-dir           "resources/public/js/test/out"
                               :recompile-dependents true
                               :preloads             [devtools.preload]
                               :asset-path           "js/test/out"
                               :optimizations        :none}}
               {:id           "cards"
                :source-paths ["src/main" "src/cards"]
                :figwheel     {:devcards true}
                :compiler     {:main                 untangled.client.card-ui
                               :output-to            "resources/public/js/cards/cards.js"
                               :output-dir           "resources/public/js/cards/out"
                               :asset-path           "js/cards/out"
                               :source-map-timestamp true
                               :optimizations        :none}}
               {:id           "automated-tests"
                :source-paths ["src/test" "src/main"]
                :compiler     {:output-to     "resources/private/js/unit-tests.js"
                               :main          untangled.all-tests
                               :output-dir    "resources/private/js/out"
                               :asset-path    "js/out"
                               :optimizations :none}}]}

  :profiles {:dev {:source-paths ["src/dev" "src/main" "src/cards" "src/test"]
                   :repl-options {:init-ns          clj.user
                                  :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                   :dependencies [[binaryage/devtools "0.9.2"]
                                  [com.cemerick/piggieback "0.2.1"]
                                  [figwheel-sidecar "0.5.9"]
                                  [org.clojure/test.check "0.9.0"]
                                  [org.clojure/tools.namespace "0.3.0-alpha3"]
                                  [org.clojure/tools.nrepl "0.2.12"]]}})
