(defproject navis/untangled-websockets "0.2.1"
  :description "Tools for making untangled leverage websockets."
  :url ""
  :license {:name "NAVIS"
            :url  "http://www.thenavisway.com"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.8.51"]
                 [com.taoensso/timbre "4.3.1"]
                 [navis/untangled-client "0.5.0" :exclusions [cljsjs/react org.omcljs/om] :scope "provided"]
                 [navis/untangled-server "0.5.1" :scope "provided"]
                 [navis/untangled-spec "0.3.6" :scope "provided"]
                 [org.omcljs/om "1.0.0-alpha36" :scope "provided"]
                 [com.taoensso/sente "1.8.1"]]

  :plugins [[com.jakemccrary/lein-test-refresh "0.14.0"]
            [lein-cljsbuild "1.1.3"]]

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
