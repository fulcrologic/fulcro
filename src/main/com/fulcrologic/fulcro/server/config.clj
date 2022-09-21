(ns com.fulcrologic.fulcro.server.config
  "Utilities for managing server configuration via EDN files.

  The general design requirements of this support are that you should be able to:

  * Specify your configuration as EDN.
  * Specify a reasonable set of server config values as \"defaults\" so that specific environments can override
  just what matters.
  * Override the defaults by deep-merging an environment-specific config file over the defaults.
  * Specify individual overrides via environment variables.
  ** Support rich data types from environment variables, like maps, numerics, etc.

  So the basic operation is that you create a default EDN file and one or more environment files (e.g.
  `dev.edn`, `prod.edn`, `joes-test-env.edn`, etc. You can then use a combination of runtime parameters,
  JVM properties, and environment variables to end up with your runtime configuration.

  See `load-config!` for more detailed usage.
  "
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.walk :as walk]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONFIG
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-system-prop [prop-name]
  (System/getProperty prop-name))

(defn- load-edn!
  "If given a relative path, looks on classpath (via class loader) for the file, reads the content as EDN, and returns it.
  If the path is an absolute path, it reads it as EDN and returns that.
  If the resource is not found, returns nil.

  This function returns the EDN file without further interpretation (no merging or env evaluation).  Normally you want
  to use `load-config!` instead."
  [^String file-path]
  (let [?edn-file (io/file file-path)]
    (if-let [edn-file (and (.isAbsolute ?edn-file)
                        (.exists ?edn-file)
                        (io/file file-path))]
      (-> edn-file slurp edn/read-string)
      (some-> file-path io/resource slurp edn/read-string))))

(defn- load-edn-file!
  "Calls load-edn on `file-path`,
  and throws an ex-info if that failed."
  [file-path]
  (log/info "Reading configuration file at " file-path)
  (if-let [edn (some-> file-path load-edn!)]
    edn
    (do
      (log/error "Unable to read configuration file " file-path "See https://book.fulcrologic.com/#err-config-file-read-err")
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

(defn load-config!
  "Load a configuration file via the given options.

  options is a map with keys:

  * `:config-path` : The path to the file to load (in addition to the addl behavior described below).
  * `:defaults-path` : (optional) A relative or absolute path to the default options that should be the basis of configuration.
     Defaults to `config/defaults.edn`. When relative, will come from resources. When absolute, will come from disk.

  Reads the defaults from CLASSPATH (default config/defaults.edn), then deep merges the EDN content
  of an additional config file you specify into that and evaluates environment variable expansions.

  You may use a Java system property to specify (*override*) the `:config-path` option:

  ```
  java -Dconfig=/usr/local/etc/app.edn ...
  ```

  allowing you to affect a packaged JAR application.

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

  and a call to `(load-config! {:config-path \"/usr/local/etc/app.edn\"})` on a system with env variable `PORT=\"8080\"` would return:

  ```
  {:port 8080  ;; as an integer, not a string
   :service :A}
  ```

  If your EDN file includes a symbol (which must be namespaced) then it will try to require and resolve
  it dynamically as the configuration loads.
  "
  ([] (load-config! {}))
  ([{:keys [config-path defaults-path]}]
   (let [defaults-path (if (seq defaults-path)
                         defaults-path
                         "config/defaults.edn")
         defaults      (load-edn-file! defaults-path)
         config        (load-edn-file! (or (get-system-prop "config") config-path))]
     (->> (util/deep-merge defaults config)
       (walk/prewalk #(cond-> % (symbol? %) resolve-symbol
                        (and (keyword? %) (namespace %)
                          (re-find #"^env.*" (namespace %)))
                        (-> name get-system-env
                          (cond-> (= "env.edn" (namespace %))
                            (edn/read-string)))))))))

(def ^:deprecated load-config
  "Use load-config!"
  load-config!)

(def ^:deprecated open-config-file
  "Not meant for public consumption"
  load-edn-file!)

(def ^:deprecated load-edn
  "Not meant for public consumption"
  load-edn!)
