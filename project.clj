(defproject navis/untangled-websockets "0.3.3"
  :description "Tools for making untangled leverage websockets."
  :url ""
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha14" :scope "provided"]
                 [org.clojure/clojurescript "1.9.473" :scope "provided"]
                 [com.taoensso/timbre "4.7.3"]
                 [navis/untangled-client "0.8.0" :exclusions [cljsjs/react org.omcljs/om] :scope "provided"]
                 [navis/untangled-server "0.7.0" :scope "provided"]
                 [navis/untangled-spec "0.4.0" :scope "test"]
                 [org.omcljs/om "1.0.0-alpha48" :scope "provided"]
                 [com.taoensso/sente "1.10.0"]]

  :plugins [[com.jakemccrary/lein-test-refresh "0.19.0"]
            [lein-cljsbuild "1.1.5"]]

  :test-refresh {:report       untangled-spec.reporters.terminal/untangled-report
                 :with-repl    true
                 :changes-only true}

  :source-paths ["src"]

  :resource-paths ["resources"]

  :clean-targets ^{:protect false} ["resources/public/js" "target"]

  :cljsbuild {:builds [{:id           "dev"
                        :source-paths ["src"]
                        :figwheel     true
                        :compiler     {:main                 cljs.user
                                       :asset-path           "js/compiled/dev"
                                       :output-to            "resources/public/js/compiled/app.js"
                                       :output-dir           "resources/public/js/compiled/dev"
                                       :optimizations        :none
                                       :parallel-build       false
                                       :verbose              false
                                       :recompile-dependents true
                                       :source-map-timestamp true}}]})
