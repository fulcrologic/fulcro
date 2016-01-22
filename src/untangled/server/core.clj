(ns untangled.server.core
  (:require [datomic.api :as d]
            [untangled.server.impl.components.web-server :as web-server]
            [untangled.server.impl.components.logger :as logger]
            [untangled.server.impl.components.handler :as handler]
            [untangled.server.impl.components.database :as database]
            [untangled.server.impl.components.config :as config]
            [com.stuartsierra.component :as component]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutation Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn arg-assertion [mutation & args]
  "The function will throw an assertion error if any args are nil."
  (assert (every? (comp not nil?) args) (str "All parameters to " mutation " mutation must be provided.")))

(defn retract-datomic-entity [connection entity-id] @(d/transact connection [[:db.fn/retractEntity entity-id]]))

(defn transitive-join
  "Takes a map from a->b and a map from b->c and returns a map a->c."
  [a->b b->c]
  (reduce (fn [result k] (assoc result k (->> k (get a->b) (get b->c)))) {} (keys a->b)))

(defn resolve-ids [new-db omids->tempids tempids->realids]
  (reduce
    (fn [acc [cid dtmpid]]
      (assoc acc cid (d/resolve-tempid new-db tempids->realids dtmpid)))
    {}
    omids->tempids))

(defn replace-ref-types [dbc refs m]
  "@dbc   the database to query
   @refs  a set of keywords that ref datomic entities, which you want to access directly
          (rather than retrieving the entity id)
   @m     map returned from datomic pull containing the entity IDs you want to deref"
  (clojure.walk/postwalk
    (fn [arg]
      (if (and (coll? arg) (refs (first arg)))
        (update-in arg [1] (comp :db/ident (partial d/entity dbc) :db/id))
        arg))
    m))

(defn query-pull
  "Given a datomic-pull query and connection `conn`, returns the query response from all entities
   containing `db-attr` as an attribute. If `ref-set` is provided, query-pull will pull each entity-id
   in the query response that is joined to the attributes specified in `ref-set`. The entity id is then
   replaced with the pulled data.

   e.g. (query-pull {:thing {:ref [:foo/bar]}} conn :thing) -> {:thing {:ref {:foo/bar {:db/id 1234567}}}
          vs.
        (query-pull {:thing {:ref [:foo/bar]}} conn :thing #{:foo/bar}) -> {:thing {:ref {:foo/bar {:referenced :data}}}}

   @{required} query    a datomic query
   @{required} conn     connection to a datomic db
   @{required} db-attr  attribute used to collect the entities to which the query is applied
   @{optional} ref-set  attributes of type `ref` that you want to be dereferenced in the query response"

  ([query conn db-attr & {:keys [ref-set] :or {ref-set #{}}}]
   (let [db (d/db conn)
         initial-result (vec (flatten (d/q `[:find (~'pull ?e ~query) :where [?e ~db-attr]] db)))
         response (if (nil? ref-set) initial-result (replace-ref-types db ref-set initial-result))]

     {:value response})))

(defn datomicid->tempid [m x]
  (let [inverter (clojure.set/map-invert m)]
    (clojure.walk/postwalk
      #(if-let [tid (get inverter %)]
        tid %)
      x)))

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

(defn build-database
  "Build a database component. If you specify a config, then none will be injected. If you do not, then this component
  will expect there to be a `:config` component to inject."
  ([database-key config]
   (database/map->DatabaseComponent {:db-name database-key
                                     :config  {:value {:datomic config}}}))
  ([database-key]
   (component/using
     (database/map->DatabaseComponent {:db-name database-key})
     [:config :logger])))

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
