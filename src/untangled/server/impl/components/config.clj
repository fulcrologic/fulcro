(ns untangled.server.impl.components.config
  (:require [com.stuartsierra.component :as component]
            [clojure.java.classpath :as cp]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [taoensso.timbre :as log]
            [clojure.walk :as walk])
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

(defn load-edn
  "If given a relative path, looks on classpath (via class loader) for the file, reads the content as EDN, and returns it.
  If the path is an absolute path, it reads it as EDN and returns that.
  If the resource is not found, returns nil."
  [^String file-path]
  (let [?edn-file (io/file file-path)]
    (if-let [edn-file (and (.isAbsolute ?edn-file)
                           (.exists ?edn-file)
                           (io/file file-path))]
      (-> edn-file slurp edn/read-string)
      (some-> file-path io/resource .openStream slurp edn/read-string))))

(defn- open-config-file
  "Calls load-edn on `file-path`,
  and throws an ex-info if that failed."
  [file-path]
  (or (some-> file-path load-edn)
      (throw (ex-info (str "Invalid config file at '" file-path "'")
               {:file-path file-path}))))

(def get-defaults open-config-file)
(def get-config   open-config-file)

(defn- resolve-symbol [sym]
  {:pre  [(namespace sym)]
   :post [(not (nil? %))]}
  (or (resolve sym)
      (do (-> sym namespace symbol require)
          (resolve sym))))

(defn- get-system-env [var-name]
  (System/getenv var-name))

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
       (walk/prewalk #(cond-> % (symbol? %) resolve-symbol
                        (and (keyword? %) (namespace %)
                             (re-find #"^env.*" (namespace %)))
                        (-> name get-system-env
                          (cond-> (= "env.edn" (namespace %))
                            (edn/read-string)))))))))

(defrecord Config [value config-path]
  component/Lifecycle
  (start [this]
    (let [config (or value (load-config {:config-path config-path}))]
      (assoc this :value config)))
  (stop [this] this))
