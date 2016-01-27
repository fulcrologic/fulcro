(ns untangled.server.core
  (:require [datomic.api :as d]
            [untangled.server.protocol-support :as ps]
            [untangled.datomic.impl.seed :as seed]
            [untangled.datomic.protocols :as udb]
            [untangled.server.impl.components.web-server :as web-server]
            [untangled.server.impl.components.logger :as logger]
            [untangled.server.impl.components.handler :as handler]
            [untangled.server.impl.components.config :as config]
            [clojure.math.combinatorics :as combo]
            [com.stuartsierra.component :as component]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutation Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn arg-assertion [mutation & args]
  "The function will throw an assertion error if any args are nil."
  (assert (every? (comp not nil?) args) (str "All parameters to " mutation " mutation must be provided.")))

(defn transitive-join
  "Takes a map from a->b and a map from b->c and returns a map a->c."
  [a->b b->c]
  (reduce (fn [result k] (assoc result k (->> k (get a->b) (get b->c)))) {} (keys a->b)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component Constructor Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-web-server []
  (component/using
    (web-server/map->WebServer {})
    [:handler :config]))

(defn build-logger []
  (component/using
    (logger/map->Logger {})
    [:config]))

(defn raw-config
  "Creates a configuration component using the value passed in,
   it will NOT look for any config files."
  [value] (config/map->Config {:value value}))

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
  (config/map->Config {:config-path config-path}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server Construction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-untangled-server
  "Make a new untangled server.

  Parameters:
  *`config-path`        OPTIONAL, a string of the path to your configuration file on disk.
                        The system property -Dconfig=/path/to/conf can also be passed in from the jvm.

  *`components`         OPTIONAL, a map of Sierra component instances keyed by their desired names in the overall system component.
                        These additional components will merged with the untangled-server components to compose a new system component.

  *`parser`             REQUIRED, an om parser function for parsing requests made of the server

  *`parser-injections`  a vector of keywords which represent components which will be injected as the om parsing env.

  Returns a Sierra system component.
  "
  [& {:keys [config-path components parser parser-injections] :or {config-path "/usr/local/etc/untangled.edn"}}]
  {:pre [(some-> parser fn?)
         (or (nil? components) (map? components))
         (or (nil? parser-injections) (every? keyword? parser-injections))]}
  (let [handler (handler/build-handler parser parser-injections)
        built-in-components [:config (new-config config-path)
                             :logger (build-logger)
                             :handler handler
                             :server (make-web-server)]
        all-components (flatten (concat built-in-components components))]
    (apply component/system-map all-components)))

(defrecord Seeder [seed-data seed-result]
  component/Lifecycle
  (start [this]
    (let [dbs-to-seed (keys seed-data)
          tid-maps (reduce (fn [acc db-name]
                             (let [sd (ps/datomic-id->tempid (get seed-data db-name))
                                   db (get this db-name)
                                   conn (.get-connection db)
                                   tempid-map (seed/link-and-load-seed-data conn sd)]
                               (conj acc tempid-map)))
                     [] dbs-to-seed)
          pairwise-disjoint? (fn [maps]
                               (if (< (count maps) 2)
                                 true
                                 (let [all-keys (map (comp set keys) maps)
                                       pairs (combo/combinations all-keys 2)
                                       empty-pair? (fn [[ks1 ks2]]
                                                     (empty? (clojure.set/intersection ks1 ks2)))]
                                   (every? empty-pair? pairs))))]
      (assoc this :seed-result (and (pairwise-disjoint? tid-maps) (apply merge tid-maps)))))
  (stop [this]
    ;; You can't stop the seeder!
    this))

(defn make-seeder [seed-data]
  (component/using
    (map->Seeder {:seed-data seed-data})
    (vec (keys seed-data))))

(defn make-untangled-test-server
  [& {:keys [parser parser-injections components protocol-data]}]
  (let [handler (handler/build-handler parser parser-injections)
        seeder (make-seeder (:seed-data protocol-data))
        built-in-components [:config (new-config "config/test.edn")
                             :logger (build-logger)
                             :handler handler
                             :seeder seeder]
        all-components (flatten (concat built-in-components components))]
    (apply component/system-map all-components)))
