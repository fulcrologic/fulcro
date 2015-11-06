(ns untangled.components.config
  (:require [com.stuartsierra.component :as component]
            [clojure.java.classpath :as cp]
            [clojure.java.io :as io]))

(defn- get-system-prop [prop-name]
  (System/getProperty prop-name))

(defn- deep-merge [& xs]
  "Recursively merge maps.
   If the args are ever not all maps,
   the last value wins"
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))

(defn- find-file
  "Finds a file in the resources (via classpath) and returns the path to that file on disk."
  [^String file-path]
  (if (.startsWith file-path "/")
    file-path
    (some-> file-path
            io/resource io/file .getAbsolutePath)))

(def ^:private fallback-props-path "/usr/local/etc/config.edn")
(defn- get-props [path]
  (-> path (or fallback-props-path)
      find-file slurp read-string))

(def ^:private fallback-defaults-path "defaults.edn")
(defn- get-defaults [path]
  (or (some-> path (or fallback-defaults-path)
              find-file slurp read-string)
      (throw (ex-info "please provide a defaults.edn file in your resources folder"
                      {}))))

(defn load-config [{:keys [sys-prop props-path defaults-path] :as opts}]
  (let [cfg-file (get-system-prop (or sys-prop "config"))
        props (get-props (or cfg-file props-path))
        defaults (get-defaults defaults-path)]
    (deep-merge defaults props)))

(defrecord Config [config defaults-path props-path sys-prop]
  component/Lifecycle
  (start [this]
    (let [config (load-config this)]
      (assoc this :value config)))
  (stop [this]
    (assoc this :value nil)))

(defn new-config [& [props-path defaults-path sys-prop]]
  (map->Config {:defaults-path defaults-path
                :props-path props-path
                :sys-prop sys-prop}))
