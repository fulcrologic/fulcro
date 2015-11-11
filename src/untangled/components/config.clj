(ns untangled.components.config
  (:require [com.stuartsierra.component :as component]
            [clojure.java.classpath :as cp]
            [clojure.java.io :as io]
            [com.rpl.specter :refer [transform walker]])
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
  If the resource is not found, returns nil."
  [^String file-path]
  (if (.startsWith file-path "/")
    (when (-> file-path io/file .exists)
      (-> file-path slurp read-string))
    (some-> file-path io/resource .openStream slurp read-string)))

(defn- get-with-fallback
  "Builds a fn with a fallback in case the fn is called with a nil argument.
   Calls load-edn on the path or the fallback,
   and throws an ex-info instead of returning nil"
  [fallback]
  (fn [path]
    (or (-> path (or fallback) load-edn)
        (throw (ex-info "please provide a valid file on your file-system"
                        {:path path
                         :fallback fallback})))))

(def fallback-config-path "/usr/local/etc/config.edn")
(def ^:private get-config
  (get-with-fallback fallback-config-path))

(def fallback-defaults-path "config/defaults.edn")
(def ^:private get-defaults
  (get-with-fallback fallback-defaults-path))

(defn- resolve-symbol [sym]
  {:pre [(namespace sym)]
   :post [(not (nil? %))]}
  (or (resolve sym)
      (do (-> sym namespace symbol require)
          (resolve sym))))

(defn load-config
  "Entry point for config loading, pass it a map with k-v pairs indicating where
   it should look for configuration in case things are not found.
   Eg:
   - sys-prop indicates the name of the system property that will contain the path to the config file, eg: '-Dconfig=...'
   defaults to 'config'
   - config-path is the default location of the config file in case there was no system property passed in,
   defaults to `fallback-config-path`
   - defaults-path is the location of the defaults config file, it is overriden by the config file,
   defaults to `fallback-defaults-path`
   "
  ([] (load-config {}))
  ([{:keys [sys-prop config-path defaults-path]}]
   (let [cfg-file (get-system-prop (or sys-prop "config"))
         defaults (get-defaults defaults-path)
         config (get-config (or cfg-file config-path))]
     (->> (deep-merge defaults config)
          (transform (walker symbol?) resolve-symbol)))))

(defrecord Config [value defaults-path config-path sys-prop]
  component/Lifecycle
  (start [this]
    (let [config (load-config this)]
      (assoc this :value config)))
  (stop [this]
    (assoc this :value nil)))

(defn new-config [& [config-path defaults-path sys-prop]]
  (map->Config {:defaults-path defaults-path
                :config-path   config-path
                :sys-prop      sys-prop}))
