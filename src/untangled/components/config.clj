(ns untangled.components.config
  (:require [com.stuartsierra.component :as component]
            [clojure.java.classpath :as cp]
            [clojure.java.io :as io]
            [com.rpl.specter :refer [transform walker]])
  (:import (java.io File)))

(defn- get-system-prop [prop-name]
  {:post [(if % (.startsWith % "/") true)]}
  (System/getProperty prop-name))

(defn- deep-merge [& xs]
  "Recursively merge maps.
   If the args are ever not all maps,
   the last value wins"
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))

(defn load-edn
  "If given a relative path, looks on classpath (via class loader) for the file, reads the content as EDN, and returns it.
  If the path is an absolute path, it reads it as EDN and returns that.
  If the resource is not found, returns nil."
  [^String file-path]
  (if (.startsWith file-path "/")
    (when (-> file-path io/file .exists)
      (-> file-path slurp read-string))
    (some-> file-path io/resource .openStream slurp read-string)))

(defn- open-config-file
  "Calls load-edn on `file-path`,
   and throws an ex-info if that failed."
  [file-path]
  (or (some-> file-path load-edn)
      (throw (ex-info "please provide a valid file on your file-system"
                      {:file-path file-path}))))

(def  get-defaults open-config-file)
(def  get-config   open-config-file)

(defn- resolve-symbol [sym]
  {:pre  [(namespace sym)]
   :post [(not (nil? %))]}
  (or (resolve sym)
      (do (-> sym namespace symbol require)
          (resolve sym))))

(defn load-config
  "Entry point for config loading, pass it a map with k-v pairs indicating where
   it should look for configuration in case things are not found.
   Eg:
   - config-path is the location of the config file in case there was no system property
   "
  ([] (load-config {}))
  ([{:keys [config-path]}]
   (let [defaults (get-defaults "config/defaults.edn")
         config   (get-config   (or (get-system-prop "config") config-path))]
     (->> (deep-merge defaults config)
          (transform (walker symbol?) resolve-symbol)))))

(defrecord Config [value config-path]
  component/Lifecycle
  (start [this]
    (let [config (or value (load-config config-path))]
      (assoc this :value config)))
  (stop [this]
    (assoc this :value nil)))

(defn new-config
  "Create a new configuration component. It will load the application defaults from config/defaults.edn
   (using the classpath), then look for an override file in either:
   1) the file specified via the `config` system property
   2) the file at `config-path`
   and merge anything it finds there over top of the defaults.

   This function can override a number of the above defaults with the parameters:
   - `config-path`: The location of the disk-based configuration file.
   "
  [config-path]
  (map->Config {:config-path config-path}))

(defn raw-config
  "Creates a configuration component using the value passed in,
   it will NOT look for any config files."
  [value] (map->Config {:value value}))
