(ns clj.user
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.repl :refer [doc source]]
    [clojure.set :as set]
    [clojure.spec :as s]
    [clojure.spec.gen :as sg]
    [clojure.tools.namespace.repl :refer [refresh]]
    [figwheel-sidecar.repl-api :as ra]))

(defn conform! [spec x]
  (let [rt (s/conform spec x)]
    (when (s/invalid? rt)
      (throw (ex-info (s/explain-str spec x)
               (s/explain-data spec x))))
    rt))

(def figwheel-config
  {:figwheel-options {:server-port 3050
                      :css-dirs    ["resources/public/css"]}
   :default-build-ids ["test" "cards"]
   :all-builds        (figwheel-sidecar.repl/get-project-cljs-builds)})

(defn start-figwheel
  "Start Figwheel on the given builds, or defaults to `default-build-ids` in `figwheel-config`."
  ([] (let [props (System/getProperties)
            all-builds (->> figwheel-config :all-builds (mapv :id))]
        (start-figwheel (keys (select-keys props all-builds)))))
  ([build-ids]
   (let [build-ids (or (seq build-ids) (:default-build-ids figwheel-config))]
     (assert (set/subset? (set build-ids)
                          (set (map :id (:all-builds figwheel-config))))
             (str "\nInvalid build ids: " (set/difference (set build-ids)
                                                          (set (map :id (:all-builds figwheel-config))))
                  "\nValid ids are: " (set (map :id (:all-builds figwheel-config)))))
     (println "STARTING FIGWHEEL ON BUILDS: " build-ids)
     (ra/start-figwheel! (assoc figwheel-config :build-ids build-ids))
     (ra/cljs-repl))))
