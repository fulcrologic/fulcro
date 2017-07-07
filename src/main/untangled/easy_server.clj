(ns untangled.easy-server
  (:require
    [com.stuartsierra.component :as component]
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.java.io :as io]
    [om.next.server :as om]
    [taoensso.timbre :as timbre]
    [untangled.server :as server]
    [bidi.bidi :as bidi]
    [org.httpkit.server :refer [run-server]]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
    [ring.middleware.gzip :refer [wrap-gzip]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.middleware.resource :refer [wrap-resource]]
    [ring.util.response :as rsp :refer [response file-response resource-response]] )
  (:gen-class))

(defn index [req]
  (assoc (resource-response (str "index.html") {:root "public"})
    :headers {"Content-Type" "text/html"}))

(defn api
  "The /api Request handler. The incoming request will have a database connection, parser, and error handler
  already injected. This function should be fairly static, in that it calls the parser, and if the parser
  does not throw and exception it wraps the return value in a transit response. If the parser throws
  an exception, then it calls the injected error handler with the request and the exception. Thus,
  you can define the handling of all API requests via system injection at startup."
  [{:keys [transit-params parser env] :as req}]
  (server/handle-api-request parser env transit-params))

(def default-api-key "/api")

(defn app-namify-api [default-routes app-name]
  (if-not app-name default-routes
    (update default-routes 1
      (fn [m]
        (let [api-val (get m default-api-key)]
          (-> m
            (dissoc default-api-key)
            (assoc (str "/" app-name default-api-key) api-val)))))))

(def default-routes
  ["" {"/"             :index
       default-api-key {:get  :api
                        :post :api}}])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handler Code
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn route-handler [req]
  (let [routes (app-namify-api default-routes (:app-name req))
        match  (bidi/match-route routes (:uri req)
                 :request-method (:request-method req))]
    (case (:handler match)
      ;; explicit handling of / as index.html. wrap-resources does the rest
      :index (index req)
      :api (api req)
      nil)))

(defn wrap-connection
  "Ring middleware function that invokes the general handler with the parser and parsing environment on the request."
  [handler route-handler api-parser om-parsing-env app-name]
  (fn [req]
    (or (route-handler (assoc req
                         :parser api-parser
                         :env (assoc om-parsing-env :request req)
                         :app-name app-name))
        (handler req))))

(defn wrap-extra-routes [dflt-handler {:as extra-routes :keys [routes handlers]} om-parsing-env]
  (if-not extra-routes dflt-handler
    (do (assert (and routes handlers) extra-routes)
      (fn [req]
        (let [match (bidi/match-route routes (:uri req) :request-method (:request-method req))]
          (if-let [bidi-handler (get handlers (:handler match))]
            (bidi-handler (assoc om-parsing-env :request req) match)
            (dflt-handler req)))))))

(defn not-found-handler []
  (fn [req]
    {:status  404
     :headers {"Content-Type" "text/html"}
     :body    (io/file (io/resource "public/not-found.html"))}))

(defn handler
  "Create a web request handler that sends all requests through an Om parser. The om-parsing-env of the parses
  will include any components that were injected into the handler.

  Returns a function that handles requests."
  [api-parser om-parsing-env extra-routes app-name pre-hook fallback-hook]
  ;; NOTE: ALL resources served via wrap-resources (from the public subdirectory). The BIDI route maps / -> index.html
  (-> (not-found-handler)
    (fallback-hook)
    (wrap-connection route-handler api-parser om-parsing-env app-name)
    (server/wrap-transit-params)
    (server/wrap-transit-response)
    (wrap-resource "public")
    (wrap-extra-routes extra-routes om-parsing-env)
    (pre-hook)
    (wrap-content-type)
    (wrap-not-modified)
    (wrap-gzip)))

(defprotocol IHandler
  (set-pre-hook! [this pre-hook]
    "Sets the handler before any important handlers are run.")
  (get-pre-hook [this]
    "Gets the current pre-hook handler.")
  (set-fallback-hook! [this fallback-hook]
    "Sets the fallback handler in case nothing else returned.")
  (get-fallback-hook [this]
    "Gets the current fallback-hook handler."))

(defrecord Handler [stack api-parser injected-keys extra-routes app-name pre-hook fallback-hook]
  component/Lifecycle
  (start [component]
    (assert (every? (set (keys component)) injected-keys)
      (str "You asked to inject " injected-keys
        " but " (set/difference injected-keys (set (keys component)))
        " do not exist."))
    (timbre/info "Creating web server handler.")
    (let [om-parsing-env (select-keys component injected-keys)
          req-handler    (handler api-parser om-parsing-env extra-routes app-name
                           @pre-hook @fallback-hook)]
      (reset! stack req-handler)
      (assoc component :env om-parsing-env
                       :middleware (fn [req] (@stack req)))))
  (stop [component]
    (timbre/info "Tearing down web server handler.")
    (assoc component :middleware nil :stack nil :pre-hook nil :fallback-hook nil))

  IHandler
  (set-pre-hook! [this new-pre-hook]
    (reset! pre-hook new-pre-hook)
    (reset! stack
      (handler api-parser (select-keys this injected-keys)
        extra-routes app-name @pre-hook @fallback-hook))
    this)
  (get-pre-hook [this]
    @pre-hook)
  (set-fallback-hook! [this new-fallback-hook]
    (reset! fallback-hook new-fallback-hook)
    (reset! stack
      (handler api-parser (select-keys this injected-keys)
        extra-routes app-name @pre-hook @fallback-hook))
    this)
  (get-fallback-hook [this]
    @fallback-hook))

(defn build-handler
  "Build a web request handler.

   Parameters:
   - `api-parser`: An Om AST Parser that can interpret incoming API queries, and return the proper response. Return is the response when no exception is thrown.
   - `injections`: A vector of keywords to identify component dependencies.  Components injected here can be made available to your parser.
   - `extra-routes`: See `make-untangled-server`
   - `app-name`: See `make-untangled-server`
   "
  [api-parser injections & {:keys [extra-routes app-name]}]
  (component/using
    (map->Handler {:api-parser    api-parser
                   :injected-keys injections
                   :stack         (atom nil)
                   :pre-hook      (atom identity)
                   :fallback-hook (atom identity)
                   :extra-routes  extra-routes
                   :app-name      app-name})
    (vec (into #{:config} injections))))

(def http-kit-opts
  [:ip :port :thread :worker-name-prefix
   :queue-size :max-body :max-line])

(defrecord WebServer [port handler server]
  component/Lifecycle
  (start [this]
    (try
      (let [server-opts    (select-keys (-> this :config :value) http-kit-opts)
            port           (:port server-opts)
            started-server (run-server (:middleware handler) server-opts)]
        (timbre/info (str "Web server (http://localhost:" port ")") "started successfully. Config of http-kit options:" server-opts)
        (assoc this :port port :server started-server))
      (catch Exception e
        (timbre/fatal "Failed to start web server " e)
        (throw e))))
  (stop [this]
    (if-not server this
      (do (server)
        (timbre/info "web server stopped.")
        (assoc this :server nil)))))

(defn make-web-server
  "Builds a web server with an optional argument that
   specifies which component to get `:middleware` from,
   defaults to `:handler`."
  [& [handler]]
  (component/using
    (component/using
      (map->WebServer {})
      [:config])
    {:handler (or handler :handler)}))

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
      :or   {config-path "/usr/local/etc/untangled.edn"}
      :as   params}]
  {:pre [(some-> parser fn?)
         (or (nil? components) (map? components))
         (or (nil? extra-routes)
           (and (map? extra-routes)
             (:routes extra-routes)
             (map? (:handlers extra-routes))))
         (or (nil? parser-injections)
           (and (set? parser-injections)
             (every? keyword? parser-injections)))]}
  (let [handler             (build-handler parser parser-injections
                              :extra-routes extra-routes
                              :app-name app-name)
        built-in-components [:config (server/new-config config-path)
                             :handler handler
                             :server (make-web-server)]
        all-components      (flatten (concat built-in-components components))]
    (apply component/system-map all-components)))

(defn make-untangled-test-server
  "Make sure to inject a :seeder component in the group of components that you pass in!"
  [& {:keys [parser parser-injections components]}]
  (let [handler             (build-handler parser parser-injections)
        built-in-components [:config (server/new-config "test.edn")
                             :handler handler]
        all-components      (flatten (concat built-in-components components))]
    (apply component/system-map all-components)))

(defrecord WrapDefaults [handler defaults-config]
  component/Lifecycle
  (start [this]
    (let [pre-hook (get-pre-hook handler)]
      ;; We want wrap-defaults to take precedence.
      (set-pre-hook! handler (comp #(wrap-defaults % defaults-config) pre-hook))
      this))
  (stop [this] this))

(defn make-wrap-defaults
  "Create a component that adds `ring.middleware.defaults/wrap-defaults` to the middleware in the prehook.

  - `defaults-config` - (Optional) The configuration passed to `wrap-defaults`.
  The 0 arity will use `ring.middleware.defaults/site-defaults`."
  ([]
   (make-wrap-defaults site-defaults))
  ([defaults-config]
   (component/using
     (map->WrapDefaults {:defaults-config defaults-config})
     [:handler])))
