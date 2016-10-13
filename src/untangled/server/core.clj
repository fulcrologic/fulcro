(ns untangled.server.core
  (:require
    [com.stuartsierra.component :as component]
    [clojure.data.json :as json]
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

(defn build-access-token-handler [& {:keys [dependencies]}]
  (component/using
    (access-token-handler/map->AccessTokenHandler {})
    (into [] (cond-> [:config :handler :server :openid-mock]
               dependencies (concat dependencies)))))

(defn build-mock-openid-server []
  (component/using
    (openid-mock-server/map->MockOpenIdServer {})
    [:config :handler]))

(defn build-test-mock-openid-server []
  (component/using
    (openid-mock-server/map->TestMockOpenIdServer {})
    [:config]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server Construction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn install-parser
  [{:keys [reads mutates]} read+mutate]
  (let [wrap-with
        (fn [new-fns]
          (fn [old-fn]
            (assert old-fn
              (str "Cannot wrap a non-existing method with: " new-fns))
            (fn [env k params]
              (let [F (or (get new-fns k) old-fn)]
                (F env k params)))))]
    (cond-> read+mutate
      reads (update :read (wrap-with reads))
      mutates (update :mutate (wrap-with mutates)))))

(defn assoc-in-or-fail-if-found [m k-ks v & {:keys [on-fail-msg on-fail-info]}]
  (update-in m (cond-> k-ks (not (vector? k-ks)) vector)
    (fn [old]
      (when-not (nil? old)
        (throw (ex-info (str "failed to install component for library, because: " on-fail-msg)
                 (merge on-fail-info
                   {:found old, :path k-ks, :tried v}))))
      v)))

(defn with-libraries [{:keys [extra-routes] :as params} libraries]
  (let [params' (assoc params :extra-routes [])
        step (fn [params {:as lib :keys [parser parser-injections components extra-routes]}]
               (cond-> params
                 parser (update :parser (partial install-parser parser))
                 parser-injections (update :parser-injections #(into % parser-injections))
                 components (update :components
                              #(reduce (fn [params [k v]]
                                         (assoc-in-or-fail-if-found params k v
                                           :on-fail-msg "conflicting component"
                                           :on-fail-info {:failing-library lib}))
                                       % components))
                 extra-routes (update :extra-routes conj extra-routes)))]
    (-> (reduce step params' libraries)
      (update :extra-routes #(if-not extra-routes % (conj % extra-routes)))
      (update :parser #(cond-> % (map? %) om/parser)))))

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
  [& {:keys [app-name parser parser-injections config-path
             components extra-routes middleware libraries]
      :or {config-path "/usr/local/etc/untangled.edn"}
      :as params}]
  {:pre [(if-not libraries true
           (and (vector? libraries)
             (every? map? libraries)))
         (some-> parser ((if libraries map? fn?)))
         (or (nil? components) (map? components))
         (or (nil? extra-routes)
             (and (map? extra-routes)
               (:routes extra-routes)
               (map? (:handlers extra-routes))))
         (or (nil? parser-injections)
             (and (set? parser-injections)
               (every? keyword? parser-injections)))]}
  (let [{:keys [parser parser-injections components extra-routes]} (with-libraries params libraries)
        handler (handler/build-handler parser parser-injections
                  :middleware middleware
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
