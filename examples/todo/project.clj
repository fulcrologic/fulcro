(defproject todo-demo "0.1.0-SNAPSHOT"
  :description "A demo of a TODO app"
  :url ""
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "0.0-3297"]
                 [com.lucasbradstreet/cljs-uuid-utils "1.0.2"]
                 [quiescent "0.2.0-RC1"]
                 [quiescent-model "0.1.0-SNAPSHOT"]
                 [differ "0.2.1"]
                 ]

  :plugins [[lein-cljsbuild "1.0.5"]
            [lein-figwheel "0.3.5"]]

  :source-paths ["src"]

  :clean-targets ^{:protect false} ["resources/public/js/compiled" "target"]

  :cljsbuild {
    :builds [
             {:id "dev"
              :source-paths ["src" "dev"]
              :figwheel { :on-jsload "todo.core/on-js-reload" }
              :compiler {:main cljs.user ;todo.core
                         :asset-path "js/compiled/out"
                         :output-to "resources/public/js/compiled/todo.js"
                         :output-dir "resources/public/js/compiled/out"
                         :source-map-timestamp true }
              }
             {:id "test"
              :source-paths ["src" "specs"]
              :figwheel { :on-jsload "todo.spec-runner/runner" }
              :compiler {:main todo.spec-runner
                         :output-to "resources/public/js/specs/specs.js"
                         :output-dir "resources/public/js/specs/out"
                         :asset-path "js/specs/out"
                         :optimizations :none
                         }
              }
             ]}

  :figwheel {
             :css-dirs ["resources/public/css"] 
             :open-file-command "figwheel_edit"
             :nrepl-port 7888
             })
