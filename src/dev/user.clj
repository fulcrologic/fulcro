(ns user
  (:require
    [cards.server :as svr]
    [clojure.pprint :refer [pprint]]
    [clojure.repl :refer [doc source]]
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as sg]
    [clojure.tools.namespace.repl :as tools-ns :refer [disable-reload! refresh clear set-refresh-dirs]]
    [figwheel-sidecar.system :as fig]
    [com.stuartsierra.component :as component]
    [untangled-spec.selectors :as sel]
    [untangled-spec.suite :as suite]))

(suite/def-test-suite server-test-server
  {:config {:port 8888}
           :test-paths ["src/test"]
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
         default-build-ids (-> figwheel-config :data :build-ids)
         build-ids         (if (empty? build-ids) default-build-ids build-ids)
         preferred-config  (assoc-in figwheel-config [:data :build-ids] build-ids)]
     (reset! figwheel (component/system-map
                        :figwheel-system (fig/figwheel-system preferred-config)
                        :css-watcher (fig/css-watcher {:watch-paths ["resources/public/css"]})))
     (println "STARTING FIGWHEEL ON BUILDS: " build-ids)
     (swap! figwheel component/start)
     (fig/cljs-repl (:figwheel-system @figwheel)))))

(set-refresh-dirs "src/demos" "src/main" "src/test")

(defonce demo-server (atom nil))

(defn- init
  "Create a web server from configurations. Use `start` to start it."
  []
  (reset! demo-server (svr/make-system)))

(defn- start-demo-server "Start (an already initialized) web server." [] (swap! demo-server component/start))

(defn stop-demo-server "Stop the running web server." []
  (when @demo-server
    (swap! demo-server component/stop)
    (reset! demo-server nil)))

(defn run-demo-server "Load the overall web server system and start it." []
  (init)
  (start-demo-server))

(defn restart-demo-server
  "Stop the web server, refresh all namespace source code from disk, then restart the web server."
  []
  (stop-demo-server)
  (refresh :after 'user/run-demo-server))
