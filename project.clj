(defproject untangled "0.1.0-SNAPSHOT"
  :description "An opinionated data model for use with (and following) the sensibilities of Quiescent."
  :url ""
  :license {:name "NAVIS"
            :url  "http://www.thenavisway.com"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [quiescent "0.2.0-RC1"]
                 [org.clojure/clojurescript "1.7.48"]
                 [differ "0.2.1"]
                 ]

  :source-paths ["src"]
  
  :plugins [[lein-cljsbuild "1.0.5"]
            [lein-figwheel "0.3.9"]]

  :cljsbuild {:builds
              [{:id           "test"
                :source-paths ["src" "dev" "test"]
                :figwheel     {:on-jsload "cljs.user/on-load"}
                :compiler     {:main                 cljs.user
                               :output-to            "resources/public/js/test/test.js"
                               :output-dir           "resources/public/js/test/out"
                               :recompile-dependents true
                               :asset-path           "js/test/out"
                               :optimizations        :none}}]}

  :figwheel {:nrepl-port 7888})
