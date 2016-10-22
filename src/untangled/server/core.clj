(ns untangled.server.core
  (:require
    [com.stuartsierra.component :as component]
    [clojure.data.json :as json]
    [clojure.set :as set]
    [om.next.server :as om]
    [untangled.server.impl.components.web-server :as web-server]
    [untangled.server.impl.components.handler :as handler]
    [untangled.server.impl.components.config :as config]
    [untangled.server.impl.components.access-token-handler :as access-token-handler]
    [untangled.server.impl.components.openid-mock-server :as openid-mock-server]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutation Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn arg-assertion [mutation & args]
  "The function will throw an assertion error if any args are nil."
  (assert (every? (comp not nil?) args) (str "All parameters to " mutation " mutation must be provided.")))

(defn assert-user [req]
  "Throws and AssertionError if the user credentials are missing from the request."
  (assert (:user req) "Request has no user credentials!"))

(defn transitive-join
  "Takes a map from a->b and a map from b->c and returns a map a->c."
  [a->b b->c]
  (reduce (fn [result k] (assoc result k (->> k (get a->b) (get b->c)))) {} (keys a->b)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OpenID helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn openid-location [{:keys [config] :as env} match]
  "A helper endpoint that can be injected via untangled server's :extra-routes.
  This allows untangled clients to access the configuration they require to begin the OpenID auth process."
  (let [openid-config (-> config :value :openid)
        url (str (:authority openid-config) "/connect/authorize")]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (json/write-str {:authUrl  url
                               :scope    (:scope openid-config)
                               :clientId (:client-id openid-config)})}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component Constructor Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-web-server []
  (component/using
    (web-server/map->WebServer {})
    [:handler :config]))

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

(defn build-access-token-handler [& [openid-config]]
  (component/using
    (access-token-handler/map->AccessTokenHandler {})
    [(or openid-config :openid-config)]))

(defrecord VirtualOpenIdConfig [config]
  component/Lifecycle
  (start [this]
    (component/start
      (if (-> config :value :openid-mock)
        (openid-mock-server/map->MockOpenIdConfig this)
        (access-token-handler/map->OpenIdConfig this))))
  (stop [this]
    (component/stop this)))

(defn build-openid-config []
  (component/using
    (map->VirtualOpenIdConfig {})
    [:config]))

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

  *`parser`             REQUIRED, an om parser function for parsing requests made of the server. To report errors, the
                        parser must throw an ExceptionInfo with a map with keys `:status`, `:headers`, and `:body`.
                        This map will be converted into the response sent to the client.

  *`parser-injections`  a vector of keywords which represent components which will be injected as the om parsing env.

  *`extra-routes`       OPTIONAL, a map containing `:routes` and `:handlers`,
                        where routes is a bidi routing data structure,
                        and handlers are map from handler name to a function of type :: Env -> BidiMatch -> Res
                        see `handler/wrap-extra-routes` & handler-spec for more.

  *`app-name`           OPTIONAL, a string that will turn \"\\api\" into \"<app-name>\\api\"

  Returns a Sierra system component.
  "
  [& {:keys [app-name parser parser-injections config-path components extra-routes]
      :or {config-path "/usr/local/etc/untangled.edn"}
      :as params}]
  {:pre [(some-> parser fn?)
         (or (nil? components) (map? components))
         (or (nil? extra-routes)
             (and (map? extra-routes)
               (:routes extra-routes)
               (map? (:handlers extra-routes))))
         (or (nil? parser-injections)
             (and (set? parser-injections)
               (every? keyword? parser-injections)))]}
  (let [handler (handler/build-handler parser parser-injections
                  :extra-routes extra-routes
                  :app-name app-name)
        built-in-components [:config (new-config config-path)
                             :handler handler
                             :server (make-web-server)]
        all-components (flatten (concat built-in-components components))]
    (apply component/system-map all-components)))

(defn make-untangled-test-server
  "Make sure to inject a :seeder component in the group of components that you pass in!"
  [& {:keys [parser parser-injections components]}]
  (let [handler (handler/build-handler parser parser-injections)
        built-in-components [:config (new-config "test.edn")
                             :handler handler]
        all-components (flatten (concat built-in-components components))]
    (apply component/system-map all-components)))

(defprotocol Module
  (system-key [this])
  (components [this]))

(defprotocol APIHandler
  (api-read [this R] "(~fn~ [env k params] ...)")
  (api-mutate [this M] "(~fn~ [env k params] ...)"))

(defn comp-api-modules [{:as this :keys [modules]}]
  (reduce
    (fn [r+m module-key]
      (let [module (get this module-key)]
        (-> r+m
          (update :read
            #(if-not (satisfies? APIHandler module) %
               (.api-read module %)))
          (update :mutate
            #(if-not (satisfies? APIHandler module) %
               (.api-mutate module %))))))
    {:read (constantly nil)
     :mutate (constantly nil)}
    (reverse modules)))
(defrecord ApiHandler [app-name transit-opts modules]
  component/Lifecycle
  (start [this]
    (let [api-url (cond->> "/api" app-name (str "/" app-name))
          api-parser (om/parser (comp-api-modules this))
          make-response
          (fn [parser env query]
            (handler/generate-response
              (let [parse-result (try (handler/raise-response
                                        (parser env query))
                                   (catch Exception e e))]
                (if (handler/valid-response? parse-result)
                  {:status 200 :body parse-result}
                  (handler/process-errors parse-result)))))]
      (assoc this :middleware
        (fn [h]
          (fn [req]
            (if-let [resp (and (= (:uri req) api-url)
                            (make-response api-parser
                              {:request req} (:transit-params req)))]
              resp (h req)))))))
  (stop [this] (dissoc this :middleware)))
(defn api-handler [opts]
  (component/using
    (map->ApiHandler
      (update (select-keys opts
        [:app-name :transit-opts :modules])
        :modules #(mapv system-key %)))
    (mapv system-key (:modules opts))))

(defn untangled-system
  [{:keys [api-handler-key modules] :as opts}]
  (apply component/system-map
    (apply concat
      (merge (:components opts)
             (into {}
               (mapcat (juxt (juxt system-key identity) components))
               modules)
             {(or api-handler-key ::api-handler)
              (api-handler
                (select-keys opts
                  [:modules :app-name :transit-opts]))}))))
