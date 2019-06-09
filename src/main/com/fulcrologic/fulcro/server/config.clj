(ns com.fulcrologic.fulcro.server.config
  "Utilities for managing server configuration via EDN files.  These functions expect a config/defaults.edn to exist
  on the classpath as a definition for server configuration default values.  When you call `load-config` it will
  deep merge the file you supply with the base defaults to return the 'complete' configuration.  When loading
  configurations a relative path is evaluated against CLASSPATH and an absolute path against the real filesystem.

  The values in the EDN files can be :env/VAR to pull a string from an env variable, and :env.edn/VAR to do a `read-string`
  against the value of an environment variable."
  (:require
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [clojure.walk :as walk]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.misc :as util]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONFIG
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-system-prop [prop-name]
  (System/getProperty prop-name))

(defn load-edn
  "If given a relative path, looks on classpath (via class loader) for the file, reads the content as EDN, and returns it.
  If the path is an absolute path, it reads it as EDN and returns that.
  If the resource is not found, returns nil.

  This function returns the EDN file without further interpretation (no merging or env evaluation).  Normally you want
  to use `load-config` instead."
  [^String file-path]
  (let [?edn-file (io/file file-path)]
    (if-let [edn-file (and (.isAbsolute ?edn-file)
                        (.exists ?edn-file)
                        (io/file file-path))]
      (-> edn-file slurp edn/read-string)
      (some-> file-path io/resource .openStream slurp edn/read-string))))

(defn open-config-file
  "Calls load-edn on `file-path`,
  and throws an ex-info if that failed."
  [file-path]
  (log/info "Reading configuration file at " file-path)
  (if-let [edn (some-> file-path load-edn)]
    edn
    (do
      (log/error "Unable to read configuration file " file-path)
      (throw (ex-info (str "Invalid config file at '" file-path "'")
               {:file-path file-path})))))

(defn- resolve-symbol [sym]
  {:pre  [(namespace sym)]
   :post [(not (nil? %))]}
  (or (resolve sym)
    (do (-> sym namespace symbol require)
        (resolve sym))))

(defn- get-system-env [var-name]
  (System/getenv var-name))

(defn load-config
  "Load a configuration file via the given options.

  options is a map with keys:

  - :config-path : The path to the file to load (in addition to the addl behavior described below).

  Reads (from classpath) `config/defaults.edn`, then deep merges the EDN content
  of the config file you specify into that and evaluates environment variable expansions.

  You may use a Java system property to specify (override) the config file used:

  ```
  java -Dconfig=/usr/local/etc/app.edn ...
  ```

  If no such property is used then config-path MUST be supplied (or this will throw an exception).

  Values in the EDN of the form :env/VAR mean to use the raw string value of an environment variable, and
  :env.edn/VAR mean to use the `read-string` value of the environment variable as that value.

  So the classpath resource config/defaults.edn might contain:

  ```
  {:port 3000
   :service :A}
  ```

  and `/usr/local/etc/app.edn` might contain:

  ```
  {:port :env.edn/PORT}
  ```

  and a call to `(load-config \"/usr/local/etc/app.edn\")` on a system with env variable `PORT=\"8080\"` would return:

  ```
  {:port 8080  ;; as an integer, not a string
   :service :A}
  ```

  If your EDN file includes a symbol (which must be namespaced) then it will try to require and resolve
  it dynamically as the configuration loads.
  "
  ([] (load-config {}))
  ([{:keys [config-path]}]
   (let [defaults (open-config-file "config/defaults.edn")
         config   (open-config-file (or (get-system-prop "config") config-path))]
     (->> (util/deep-merge defaults config)
       (walk/prewalk #(cond-> % (symbol? %) resolve-symbol
                        (and (keyword? %) (namespace %)
                          (re-find #"^env.*" (namespace %)))
                        (-> name get-system-env
                          (cond-> (= "env.edn" (namespace %))
                            (edn/read-string)))))))))
