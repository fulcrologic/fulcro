(ns clj.user
  (:require [figwheel-sidecar.repl-api :as ra]))

(def figwheel-config
  {:figwheel-options {
                      :server-port 3050
                      :css-dirs    ["resources/public/css"]
                      }
   :all-builds       (figwheel-sidecar.repl/get-project-cljs-builds)
   })

(defn start-figwheel
  "Start Figwheel on the given builds."
  ([] (start-figwheel []))
  ([build-ids]
   (let [build-ids (if (empty? build-ids) ["test"] build-ids)]
     (println "STARTING FIGWHEEL ON BUILDS: " build-ids)
     (ra/start-figwheel! (assoc figwheel-config :build-ids build-ids))
     (ra/cljs-repl))))
