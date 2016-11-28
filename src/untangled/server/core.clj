(ns untangled.server.core
  (:require
    [com.stuartsierra.component :as component]
    [clojure.set :as set]
    [clojure.spec :as s]
    [om.next.server :as om]
    [untangled.server.impl.components.web-server :as web-server]
    [untangled.server.impl.components.handler :as handler]
    [untangled.server.impl.components.config :as config]))

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

(defn augment-response
  "Augments the Ring response that's returned from the handler.

  Use this function when you need to add information into the handler response, for
  example when you need to add cookies or session data. Example:

      (defmethod my-mutate 'user/sign-in [_ _ _]
        {:action
         (fn []
           (augment-response
             {:uid 42} ; your regular response
             #(assoc-in % [:session :user-id] 42) ; a function resp -> resp
             ))})

  If your parser has multiple responses with `augment-response`, they will be applied
  in order, the first one will receive an empty map as input. Only top level values
  of your response will be checked for augmented response."
  [core-response ring-response-fn]
  (assert (instance? clojure.lang.IObj core-response) "Scalar values can't be augmented.")
  (with-meta core-response {::augment-response ring-response-fn}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component Constructor Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-web-server
  "Builds a web server with an optional argument that
   specifies which component to get `:middleware` from,
   defaults to `:handler`."
  [& [handler]]
  (component/using
    (component/using
      (web-server/map->WebServer {})
      [:config])
    {:handler (or handler :handler)}))

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

;;==================== NEW (& IMPROVED) UNTANGLED SERVER SYSTEM ====================

(defprotocol Module
  (system-key [this]
    "Should return the key under which the module will be located in the system map.")
  (components [this]
    "Should return a map of components that this Module should bring in to work.
     Make sure the keys are unique, or things will get overridden without warning (for now).
     Can be nil or {} (empty)."))

(defprotocol APIHandler
  (api-read [this]
    "Returns an untangled read emitter for parsing queries, ie: (fn [env k params] ...).
     Can return nil, which tells the api-handler to try the next `:module`.")
  (api-mutate [this]
    "Returns an untangled mutate emitter for parsing mutations, ie: (fn [env k params] ...).
     Can return nil, which tells the api-handler to try the next `:module`."))

(defn- chain
  "INTERNAL use only, use `untangled-system` instead."
  [F api-fn module]
  (if-not (satisfies? APIHandler module) F
    (let [parser-fn (api-fn module)]
      (fn [env k p]
        (or (parser-fn (merge module env) k p)
            (F env k p))))))

(defn- comp-api-modules
  "INTERNAL use only, use `untangled-system` instead."
  [{:as this :keys [modules]}]
  (reduce
    (fn [r+m module-key]
      (let [module (get this module-key)]
        (-> r+m
          (update :read chain api-read module)
          (update :mutate chain api-mutate module))))
    {:read (constantly nil)
     :mutate (constantly nil)}
    (rseq modules)))

(defrecord ApiHandler [app-name modules]
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
              resp (h req))))
        :handler (fn [env query]
                   (make-response api-parser env query)))))
  (stop [this] (dissoc this :middleware)))

(defn- api-handler
  "INTERNAL use only, use `untangled-system` instead."
  [opts]
  (let [module-keys (mapv system-key (:modules opts))]
    (component/using
      (map->ApiHandler
        (assoc opts :modules module-keys))
      module-keys)))

(defn untangled-system
  "More powerful variant of `make-untangled-server` that allows for libraries to provide
   components and api methods (by implementing `components` and `APIHandler` respectively).
   However note that `untangled-system` does not include any components for you,
   so you'll have to include things like a web-server (eg: `make-web-server`), middleware,
   config, etc...

   Takes a map with keys:
   * `:api-handler-key` - OPTIONAL, Where to place the api-handler in the system-map, will have `:middleware`
                          and is a (fn [h] (fn [req] resp)) that handles /api requests.
                          Should only really be of use if you want to embed an untangled-server inside
                          some other application or language, eg: java servlet (or jvm hosted language).
                          Defaults to `::api-handler`.
   * `:app-name` - OPTIONAL, a string that will turn \"/api\" into \"/<app-name>/api\".
   * `:components` - A `com.stuartsierra.component/system-map`.
   * `:modules` - A vector of implementations of Module (& optionally APIHandler),
                  that will be composed in the order they were passed in.
                  Eg: [mod1 mod2 ...] => mod1 will be tried first, mod2 next, etc...
                  This should be used to compose libraries api methods with your own,
                  with full control over execution order.

   NOTE: Stores the key api-handler is located in the meta data under `::api-handler-key`.
         Currently used by protocol support to test your api methods without needing networking."
  [{:keys [api-handler-key modules] :as opts}]
  (-> (apply component/system-map
        (apply concat
          (merge (:components opts)
                 (into {}
                   (mapcat (juxt (juxt system-key identity) components))
                   modules)
                 {(or api-handler-key ::api-handler)
                  (api-handler opts)})))
    (vary-meta assoc ::api-handler-key (or api-handler-key ::api-handler))))
