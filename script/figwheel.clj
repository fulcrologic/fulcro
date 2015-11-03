(require '[figwheel-sidecar.repl :as r]
         '[figwheel-sidecar.repl-api :as ra])

(ra/start-figwheel!
  {:figwheel-options {
                      :server-port 3450
                      :css-dirs    ["resources/public/css"]
                      }
   :build-ids        ["test"]
   :all-builds       [{:id           "test"
                       :source-paths ["src" "dev" "spec"]
                       :figwheel     {:on-jsload "cljs.user/on-load"}
                       :compiler     {:main                 'cljs.user
                                      :output-to            "resources/public/js/test/test.js"
                                      :output-dir           "resources/public/js/test/out"
                                      :recompile-dependents true
                                      :asset-path           "js/test/out"
                                      :optimizations        :none}}]
   })

(ra/cljs-repl)
