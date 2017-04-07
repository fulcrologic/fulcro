(ns clj.user
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.repl :refer [doc source]]
    [clojure.set :as set]
    [clojure.spec :as s]
    [clojure.spec.gen :as sg]
    [clojure.tools.namespace.repl :refer [refresh]]
    [figwheel-sidecar.system :as fig]
    [com.stuartsierra.component :as component]
    [untangled-spec.selectors :as sel]
    [untangled-spec.suite :as suite]))

(suite/def-test-suite server-test-server
  {:config {:port 8888}
           :test-paths ["spec"]
           :source-paths ["src"]}
  {:available #{:focused}
   :default   #{::sel/none}})

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
