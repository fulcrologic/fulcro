(ns untangled.config.core
  (:require [com.stuartsierra.component :as component]
            [clojure.java.classpath :as cp]
            [clojure.java.io :as io]
            ))

(defn- get-system-prop [prop-name]
  (System/getProperty prop-name))

(defn- deep-merge [& xs]
  "Recursively merge maps.
   If the args are ever not all maps,
   the last value wins"
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))

(defn- find-file [^String file-path]
  (if (.startsWith file-path "/")
    file-path
    (-> file-path io/resource io/file str)))

(def ^:private fallback-props-path "/usr/local/etc/config.edn")
(defn- get-props [path]
  (-> path (or fallback-props-path)
      find-file slurp read-string))

(def ^:private fallback-defaults-path "defaults.edn")
(defn- get-defaults [path]
  (-> path (or fallback-defaults-path)
      find-file slurp read-string))

(defn load-config [{:keys [sys-prop props-path defaults-path] :as opts}]
  (let [cfg-file (get-system-prop (or sys-prop "config"))
        props (get-props (or cfg-file props-path))
        defaults (get-defaults defaults-path)]
    (deep-merge defaults props)))
