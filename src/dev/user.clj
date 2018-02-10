(ns user
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.repl :refer [doc source]]
    [clojure.tools.namespace.repl :as tools-ns :refer [disable-reload! refresh clear set-refresh-dirs]]
    [figwheel-sidecar.system :as fig]
    [solutions.putting-together :as pt]
    [com.stuartsierra.component :as component]
    [fulcro-spec.selectors :as sel]
    [fulcro.democards.upload-server :as upload]
    [clojure.spec.test.alpha :as st]
    [fulcro-spec.suite :as suite]))

;(st/instrument)

(suite/def-test-suite server-test-server
  {:config       {:port 8888}
   :test-paths   ["src/test"]
   :source-paths ["src/main"]}
  {:available #{:focused}
   :default   #{::sel/none :focused}})

(def figwheel (atom nil))

(defn start-figwheel
  "Start Figwheel on the given builds, or defaults to build-ids in `figwheel-config`."
  ([]
   (let [figwheel-config (fig/fetch-config)
         props           (System/getProperties)
         all-builds      (->> figwheel-config :data :all-builds (mapv :id))]
     (start-figwheel (keys (select-keys props all-builds)))))
  ([build-ids]
   (let [figwheel-config   (fig/fetch-config)
         port              (some-> (System/getProperty "figwheel.port") Integer/parseInt)
         default-build-ids (-> figwheel-config :data :build-ids)
         build-ids         (if (empty? build-ids) default-build-ids build-ids)
         preferred-config  (cond-> (assoc-in figwheel-config [:data :build-ids] build-ids)
                             (and port (pos? port)) (assoc-in [:data :figwheel-options :server-port] port))]
     (reset! figwheel (component/system-map
                        :figwheel-system (fig/figwheel-system preferred-config)
                        :css-watcher (fig/css-watcher {:watch-paths ["resources/public/css"]})))
     (println "STARTING FIGWHEEL ON BUILDS: " build-ids)
     (swap! figwheel component/start)
     (fig/cljs-repl (:figwheel-system @figwheel)))))

(set-refresh-dirs "src/main" "src/test" "src/dev" "src/tutorial")

(defn run-upload-server
  "Load and start the server that can handle the file upload form examples."
  []
  (upload/go))

(def stop-upload-server upload/stop)

(def restart-upload-server upload/restart)

;; SOLUTIONS: Putting it Together Setting Up: The start/restart functions for the server
(comment
  (defn ex-start
    "Start the server for the tutorial server exercises."
    []
    (reset! pt/system (pt/make-server))
    (swap! pt/system component/start))

  (defn ex-stop
    "Stop the server for the tutorial server exercises."
    []
    (when @pt/system
      (component/stop @pt/system)
      (reset! pt/system nil)))

  (defn ex-restart
    "Stop the server for the tutorial server exercises, refresh code from disk, and start it again."
    []
    (ex-stop)
    (refresh :after 'user/ex-start)))
