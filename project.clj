(defproject navis/untangled-client "0.6.1-SNAPSHOT"
  :description "Client-side code for Untangled Webapps"
  :url ""
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}

  :dependencies [[com.lucasbradstreet/cljs-uuid-utils "1.0.2"]
                 [devcards "0.2.2" :exclusions [org.omcljs/om org.omcljs/om org.clojure/core.async] :scope "provided"]
                 [lein-doo "0.1.7" :scope "test"]
                 [navis/untangled-spec "0.3.9" :scope "test"]
                 [org.clojure/clojure "1.9.0-alpha14" :scope "provided"]
                 [org.clojure/clojurescript "1.9.293" :scope "provided"]
                 [org.clojure/core.async "0.2.391"]
                 [org.omcljs/om "1.0.0-alpha47" :scope "provided"]
                 [org.clojure/test.check "0.9.0" :scope "test"]]

  :source-paths ["src" "src-cards"]
  :resource-paths ["src" "resources"] ; maven deploy to internal artifactory needs src here

  :jvm-opts ["-XX:-OmitStackTraceInFastThrow" "-Xmx512m" "-Xms256m"]
  :clean-targets ^{:protect false} ["resources/private/js" "resources/public/js/test" "resources/public/js/compiled" "target"]

  :plugins [[lein-cljsbuild "1.1.4"]
            [lein-doo "0.1.7"]
            [com.jakemccrary/lein-test-refresh "0.17.0"]]

  :test-paths ["spec"]
  :test-refresh {:report untangled-spec.reporters.terminal/untangled-report
                 :changes-only true
                 :with-repl true}
  :test-selectors {:test/in-progress :test/in-progress
                   :focused :focused}

  :doo {:build "automated-tests"
        :paths {:karma "node_modules/karma/bin/karma"}}

  :figwheel {:open-file-command "fw-open-file"
             :server-port 8080}

  :cljsbuild {:builds
              [{:id           "test"
                :source-paths ["src" "dev" "spec"]
                :figwheel     {:on-jsload "cljs.user/on-load"}
                :compiler     {:main                 cljs.user
                               :output-to            "resources/public/js/test/test.js"
                               :output-dir           "resources/public/js/test/out"
                               :recompile-dependents true
                               :asset-path           "js/test/out"
                               :optimizations        :none}}
               {:id           "cards"
                :source-paths ["src" "src-cards" "dev"]
                :figwheel     {:devcards true}
                :compiler     {:main untangled.client.cards
                               :output-to "resources/public/js/cards/cards.js"
                               :output-dir "resources/public/js/cards/out"
                               :asset-path "js/cards/out"
                               :source-map-timestamp true
                               :optimizations :none}}
               {:id           "automated-tests"
                :source-paths ["spec" "src"]
                :compiler     {:output-to     "resources/private/js/unit-tests.js"
                               :main          untangled.all-tests
                               :output-dir    "resources/private/js/out"
                               :asset-path    "js/out"
                               :optimizations :none}}]}

  :profiles {:dev {:source-paths ["dev" "src" "spec"]
                   :repl-options {:init-ns          clj.user
                                  :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                   :dependencies [[binaryage/devtools "0.5.2"]
                                  [com.cemerick/piggieback "0.2.1"]
                                  [figwheel-sidecar "0.5.7"]
                                  [org.clojure/test.check "0.9.0"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  [org.clojure/tools.nrepl "0.2.12"]]}})
