(defproject untangled "0.4.1-SNAPSHOT"
  :description "An opinionated data model for use with (and following) the sensibilities of Quiescent."
  :url ""
  :license {:name "NAVIS"
            :url  "http://www.thenavisway.com"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [differ "0.2.1"]
                 [lein-doo "0.1.6" :scope "test"]
                 [com.lucasbradstreet/cljs-uuid-utils "1.0.2"]
                 [untangled-spec "0.3.1-SNAPSHOT" :scope "test"]
                 [figwheel-sidecar "0.4.1" :scope "provided"]]

  :repositories [["releases" "https://artifacts.buehner-fry.com/artifactory/internal-release"]
                 ["third-party" "https://artifacts.buehner-fry.com/artifactory/internal-3rdparty"]]

  :deploy-repositories [["releases" {:url           "https://artifacts.buehner-fry.com/artifactory/internal-release"
                                     :sign-releases false}]
                        ["snapshots" {:url           "https://artifacts.buehner-fry.com/artifactory/internal-snapshots"
                                      :sign-releases false}]]

  :clean-targets ^{:protect false} ["resources/private/js" "resources/public/js/test" "resources/public/js/compiled" "target"]
  :source-paths ["src" "spec"]

  :plugins [[lein-cljsbuild "1.1.2"]
            [lein-doo "0.1.6"]
            [lein-figwheel "0.5.0-3"]]

  :doo {:build "automated-tests"
        :paths {:karma "node_modules/.bin/karma"}}

  :cljsbuild {:builds
              [{:id           "automated-tests"
                :source-paths ["spec" "src"]
                :compiler     {:output-to     "resources/private/js/unit-tests.js"
                               :main          untangled.all-tests
                               :output-dir           "resources/private/js/out"
                               :asset-path           "js/out"
                               :optimizations :none
                               }}
               {:id           "test"
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
