(ns leiningen.i18n-spec-fixtures)

(def dev-build
  {:source-paths ["src"],
   :id "dev",
   :compiler
                 {:output-dir "resources/public/js/compiled/prod",
                  :optimizations :advanced,
                  :output-to "resources/public/js/compiled/survey.js",
                  :asset-path "js/compiled/prod",
                  :main 'survey.main}})

(def prod-build
  {:source-paths ["src"],
   :id "production",
   :compiler
                 {:output-dir "resources/public/js/compiled/prod",
                  :optimizations :advanced,
                  :output-to "resources/public/js/compiled/survey.js",
                  :asset-path "js/compiled/prod",
                  :main 'survey.main}})

(def cljs-builds
  '[{:source-paths
              ["src"
               "dev"
               "checkouts/untangled/src"
               "checkouts/smooth-spec/src"],
    :figwheel {:on-jsload "cljs.user/reload"},
    :id "dev",
    :compiler
              {:output-dir "resources/public/js/compiled/dev",
               :optimizations :none,
               :recompile-dependents true,
               :output-to "resources/public/js/compiled/survey.js",
               :source-map-timestamp true,
               :asset-path "js/compiled/dev",
               :main cljs.user}}
   {:source-paths
              ["specs"
               "src"
               "checkouts/untangled/src"
               "checkouts/smooth-spec/src"],
    :figwheel {:on-jsload "survey.user/run-tests"},
    :id "test",
    :compiler
              {:output-dir "resources/public/js/compiled/specs",
               :optimizations :none,
               :recompile-dependents true,
               :output-to "resources/public/js/specs/specs.js",
               :asset-path "js/compiled/specs",
               :main survey.user}}
   {:source-paths
              ["src" "checkouts/untangled/src" "checkouts/smooth-spec/src"],
    :figwheel true,
    :id "support",
    :compiler
              {:output-dir "resources/public/js/compiled/viewer",
               :optimizations :none,
               :recompile-dependents true,
               :output-to "resources/public/js/viewer.js",
               :asset-path "js/compiled/viewer",
               :main survey.viewer}}
   {:source-paths ["src"],
    :id "production",
    :compiler
                  {:output-dir "resources/public/js/compiled/prod",
                   :optimizations :advanced,
                   :output-to "resources/public/js/compiled/survey.js",
                   :asset-path "js/compiled/prod",
                   :main survey.main}}
   {:source-paths ["src"],
    :id "i18n",
    :compiler
                  {:output-dir "i18n/out",
                   :optimizations :whitespace,
                   :output-to "i18n/survey.js",
                   :asset-path "js/compiled/out",
                   :main survey.main}}])

