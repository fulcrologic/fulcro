(defproject untangled "0.3.0"
  :description "An opinionated data model for use with (and following) the sensibilities of Quiescent."
  :url ""
  :license {:name "NAVIS"
            :url  "http://www.thenavisway.com"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.122"]
                 [untangled-spec "0.1.1"]
                 [differ "0.2.1"]
                 [org.omcljs/om "1.0.0-alpha22" :exclusions [cljsjs/react]]
                 [com.lucasbradstreet/cljs-uuid-utils "1.0.2"]
                 [figwheel-sidecar "0.4.1" :scope "provided"]
                 [figwheel-sidecar "0.4.1" :scope "provided"]
                 [cljsjs/react-with-addons "0.14.0-1"]]

  :source-paths ["src" "spec"]

  :plugins [[lein-cljsbuild "1.1.0"]
            [lein-figwheel "0.4.1"]
            ]

  :cljsbuild {:builds
              [{:id           "test"
                :source-paths ["src" "dev" "spec"]
                :figwheel     {:on-jsload "cljs.user/on-load"}
                :compiler     {:main                 cljs.user
                               :output-to            "resources/public/js/test/test.js"
                               :output-dir           "resources/public/js/test/out"
                               :recompile-dependents true
                               :asset-path           "js/test/out"
                               :optimizations        :none}}]}

  :profiles {
             :dev {
                   :source-paths ["src" "test" "dev"]
                   :repl-options {
                                  :init-ns clj.user
                                  :port    7001
                                  }
                   :env          {:dev true}
                   }
             }

  :figwheel {
             :server-port 3450
             :css-dirs    ["resources/public/css"]})
