(ns untangled.components.config
  (:require [com.stuartsierra.component :as component]
            [clojure.java.classpath :as cp]
            [clojure.java.io :as io])
  (:import (java.io File)))

(defn- get-system-prop [prop-name]
  (System/getProperty prop-name))

(defn- deep-merge [& xs]
  "Recursively merge maps.
   If the args are ever not all maps,
   the last value wins"
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))

(defn- load-edn
  "If given a relative path, looks on classpath (via class loader) for the file, reads the content as EDN, and returns it.
  If the path is an absolute path, it reads it as EDN and returns that.
  
  If the resource is not found, returns nil.
  "
  [^String file-path]
  (if (.startsWith file-path "/")
    (when (-> file-path io/file .exists) (-> file-path slurp read-string))
    (some-> file-path io/resource .openStream slurp read-string)))

(def ^:private fallback-props-path "/usr/local/etc/config.edn")
(defn- get-props [path]
  (-> path (or fallback-props-path)
      load-edn))

(def ^:private fallback-defaults-path "defaults.edn")
(defn- get-defaults [path]
  (or (-> path (or fallback-defaults-path) load-edn)
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
                :props-path    props-path
                :sys-prop      sys-prop}))
