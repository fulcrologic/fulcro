(ns untangled.server
  (:require
    [com.stuartsierra.component :as component]
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [clojure.walk :as walk]
    [cognitect.transit :as transit]
    [om.next.server :as om]
    om.util
    [ring.util.response :as resp]
    [taoensso.timbre :as log]
    [untangled.client.util :as util])
  (:import (clojure.lang ExceptionInfo)
           [java.io ByteArrayOutputStream File]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONFIG
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- get-system-prop [prop-name]
  (System/getProperty prop-name))

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
(def get-config open-config-file)

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
         config   (get-config (or (get-system-prop "config") config-path))]
     (->> (util/deep-merge defaults config)
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

(defn raw-config
  "Creates a configuration component using the value passed in,
   it will NOT look for any config files."
  [value] (map->Config {:value value}))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TRANSIT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- write [x t opts]
  (let [baos (ByteArrayOutputStream.)
        w    (om/writer baos opts)
        _    (transit/write w x)
        ret  (.toString baos)]
    (.reset baos)
    ret))

(defn- transit-request? [request]
  (if-let [type (:content-type request)]
    (let [mtch (re-find #"^application/transit\+(json|msgpack)" type)]
      [(not (empty? mtch)) (keyword (second mtch))])))

(defn- read-transit [request {:keys [opts]}]
  (let [[res _] (transit-request? request)]
    (if res
      (if-let [body (:body request)]
        (let [rdr (om/reader body opts)]
          (try
            [true (transit/read rdr)]
            (catch Exception ex
              [false nil])))))))

(def ^{:doc "The default response to return when a Transit request is malformed."}
default-malformed-response
  {:status  400
   :headers {"Content-Type" "text/plain"}
   :body    "Malformed Transit in request body."})

(defn wrap-transit-body
  "Middleware that parses the body of Transit request maps, and replaces the :body
  key with the parsed data structure. Requests without a Transit content type are
  unaffected.
  Accepts the following options:
  :keywords?          - true if the keys of maps should be turned into keywords
  :opts               - a map of options to be passed to the transit reader
  :malformed-response - a response map to return when the JSON is malformed"
  {:arglists '([handler] [handler options])}
  [handler & [{:keys [malformed-response]
               :or   {malformed-response default-malformed-response}
               :as   options}]]
  (fn [request]
    (if-let [[valid? transit] (read-transit request options)]
      (if valid?
        (handler (assoc request :body transit))
        malformed-response)
      (handler request))))

(defn- assoc-transit-params [request transit]
  (let [request (assoc request :transit-params transit)]
    (if (map? transit)
      (update-in request [:params] merge transit)
      request)))

(defn wrap-transit-params
  "Middleware that parses the body of Transit requests into a map of parameters,
  which are added to the request map on the :transit-params and :params keys.
  Accepts the following options:
  :malformed-response - a response map to return when the JSON is malformed
  :opts               - a map of options to be passed to the transit reader
  Use the standard Ring middleware, ring.middleware.keyword-params, to
  convert the parameters into keywords."
  {:arglists '([handler] [handler options])}
  [handler & [{:keys [malformed-response]
               :or   {malformed-response default-malformed-response}
               :as   options}]]
  (fn [request]
    (if-let [[valid? transit] (read-transit request options)]
      (if valid?
        (handler (assoc-transit-params request transit))
        malformed-response)
      (handler request))))

(defn wrap-transit-response
  "Middleware that converts responses with a map or a vector for a body into a
  Transit response.
  Accepts the following options:
  :encoding - one of #{:json :json-verbose :msgpack}
  :opts     - a map of options to be passed to the transit writer"
  {:arglists '([handler] [handler options])}
  [handler & [{:as options}]]
  (let [{:keys [encoding opts] :or {encoding :json}} options]
    (assert (#{:json :json-verbose :msgpack} encoding) "The encoding must be one of #{:json :json-verbose :msgpack}.")
    (fn [request]
      (let [response (handler request)]
        (if (coll? (:body response))
          (let [transit-response (update-in response [:body] write encoding opts)]
            (if (contains? (:headers response) "Content-Type")
              transit-response
              (resp/content-type transit-response (format "application/transit+%s; charset=utf-8" (name encoding)))))
          response)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(defn serialize-exception
  "Convert exception data to string form for network transit."
  [ex]
  {:pre [(instance? Exception ex)]}
  (let [message         (.getMessage ex)
        type            (str (type ex))
        serialized-data {:type type :message message}]
    (if (instance? ExceptionInfo ex)
      (assoc serialized-data :data (ex-data ex))
      serialized-data)))

(defn unknow-error->response [error]
  (let [serialized-data (serialize-exception error)]
    {:status 500
     :body   serialized-data}))

(defn parser-read-error->response
  "Determines if ex-data from ExceptionInfo has headers matching the Untangled Server API.
   Returns ex-map if the ex-data matches the API, otherwise returns the whole exception."
  [ex]
  (let [valid-response-keys #{:status :headers :body}
        ex-map              (ex-data ex)]
    (if (every? valid-response-keys (keys ex-map))
      ex-map
      (unknow-error->response ex))))

(defn parser-mutate-error->response
  [mutation-result]
  (let [raise-error-data (fn [item]
                           (if (and (map? item) (contains? item :om.next/error))
                             (let [exception-data (serialize-exception (get-in item [:om.next/error]))]
                               (assoc item :om.next/error exception-data))
                             item))
        mutation-errors  (clojure.walk/prewalk raise-error-data mutation-result)]

    {:status 400 :body mutation-errors}))

(defn process-errors [error]
  (let [error-response (cond
                         (instance? ExceptionInfo error) (parser-read-error->response error)
                         (instance? Exception error) (unknow-error->response error)
                         :else (parser-mutate-error->response error))]
    (log/error error "Parser error:\n" (with-out-str (clojure.pprint/pprint error-response)))
    error-response))

(defn valid-response? [result]
  (and
    (not (instance? Exception result))
    (not (some (fn [[_ {:keys [om.next/error]}]] (some? error)) result))))

(defn raise-response
  "For om mutations, converts {'my/mutation {:result {...}}} to {'my/mutation {...}}"
  [resp]
  (reduce (fn [acc [k v]]
            (if (and (symbol? k) (not (nil? (:result v))))
              (assoc acc k (:result v))
              (assoc acc k v)))
    {} resp))

(defn augment-map
  "Parses response the top level values processing the augmented response. This function
  expects the parser mutation results to be raised (use the raise-response function)."
  [response]
  (->> (keep #(some-> (second %) meta :untangled.server/augment-response) response)
    (reduce (fn [response f] (f response)) {})))

(defn generate-response
  "Generate a response containing status code, headers, and body.
  The content type will always be 'application/transit+json',
  and this function will assert if otherwise."
  [{:keys [status body headers] :or {status 200} :as input}]
  {:pre [(not (contains? headers "Content-Type"))
         (and (>= status 100) (< status 600))]}
  (-> (assoc input :status status :body body)
    (update :headers assoc "Content-Type" "application/transit+json")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Module-based composable server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol Module
  (system-key [this]
    "Should return the key under which the module will be located in the system map.
     Unique-ness is checked and will be asserted.")
  (components [this]
    "Should return a map of components that this Module needs to bring in to work.
     Unique-ness is checked and will be asserted."))

(defprotocol APIHandler
  (api-read [this]
    "Returns an untangled read emitter for parsing read queries, ie: `(fn [env k params] ...)`.
     The emitter can return an untruthy value (`nil` or `false`),
     which tells the untangled api-handler to try the next `Module` in the `:modules` chain.")
  (api-mutate [this]
    "Returns an untangled mutate emitter for parsing mutations, ie: `(fn [env k params] ...)`.
     The emitter can return an untruthy value (`nil` or `false`),
     which tells the untangled api-handler to try the next `Module` in the `:modules` chain."))

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
    {:read   (constantly nil)
     :mutate (constantly nil)}
    (rseq modules)))

(defn handle-api-request [parser env query]
  (generate-response
    (let [parse-result (try (raise-response (parser env query)) (catch Exception e e))]
      (if (valid-response? parse-result)
        (merge {:status 200 :body parse-result} (augment-map parse-result))
        (process-errors parse-result)))))

(defrecord UntangledApiHandler [app-name modules]
  component/Lifecycle
  (start [this]
    (let [api-url     (cond->> "/api" app-name (str "/" app-name))
          api-parser  (om/parser (comp-api-modules this))
          api-handler (partial handle-api-request api-parser)]
      (assoc this
        :handler api-handler
        :middleware
        (fn [h]
          (fn [req]
            (if-let [resp (and (= (:uri req) api-url)
                            (api-handler {:request req} (:transit-params req)))]
              resp (h req)))))))
  (stop [this] (dissoc this :middleware)))

(defn- api-handler
  "INTERNAL use only, use `untangled-system` instead."
  [opts]
  (let [module-keys (mapv system-key (:modules opts))]
    (component/using
      (map->UntangledApiHandler
        (assoc opts :modules module-keys))
      module-keys)))

(defn- merge-with-no-duplicates!
  "INTERNAL use only, for checking that a merge doesn't override anything silently."
  [ctx & maps]
  (when (some identity maps)
    (let [merge-entry
          (fn [m e]
            (let [[k v] ((juxt key val) e)]
              (if-not (contains? m k) (assoc m k v)
                                      (throw (ex-info (str "Duplicate entries for key <" k "> found for " ctx ", see ex-data.")
                                               {:key k :prev-value (get m k) :new-value v})))))]
      (reduce (fn [m1 m2] (reduce merge-entry (or m1 {}) (seq m2))) maps))))

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
          (merge-with-no-duplicates! "untangled-system"
            (:components opts)
            (apply merge-with-no-duplicates! "Module/system-key"
              (map (comp (partial apply hash-map) (juxt system-key identity)) modules))
            (apply merge-with-no-duplicates! "Module/components"
              (map components modules))
            {(or api-handler-key ::api-handler)
             (api-handler opts)})))
    (vary-meta assoc ::api-handler-key (or api-handler-key ::api-handler))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pre-built parser support
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti server-mutate om/dispatch)


(s/def ::mutation-args (s/cat
                         :sym symbol?
                         :doc (s/? string?)
                         :arglist vector?
                         :action (s/? #(and (list? %) (= 'action (first %))))))

(defmacro ^{:doc      "Define a server-side Untangled mutation.

                       The given symbol will be prefixed with the namespace of the current namespace UNLESS
                       it is fully qualified already. If you're using cljc files in order to get server-side rendering,
                       you can also include your server mutations in the same namespace as your client ones using
                       alternate namespace aliasing:

                       ```
                       #?(:clj (server/defmutation boo ...server...)
                          :cljs (m/defmutation boo ...client...))
                       ```

                       The client-side mutation is never needed for server-side rendering, so it is ok that the mutation
                       isn't installed when rendering on the server.

                       This macro expands just like the client defmutation (though it uses server multimethod and only
                       support `action`).

                       NOTE: It will only work if you're using the `untangled-parser` as your server's Om parser.
                       "
            :arglists '([sym docstring? arglist action])} defmutation
  [& args]
  (let [{:keys [sym doc arglist action remote]} (util/conform! ::mutation-args args)
        fqsym      (symbol (name (ns-name *ns*)) (name sym))
        {:keys [action-args action-body]} (if action
                                            (util/conform! ::action action)
                                            {:action-args ['env] :action-body []})
        env-symbol (gensym "env")]
    `(defmethod untangled.server/server-mutate '~fqsym [~env-symbol ~'_ ~(first arglist)]
       (let [~(first action-args) ~env-symbol]
         {:action (fn [] ~@action-body)}))))

(defmulti read-entity
  "The multimethod for Untangled's built-in support for reading an entity."
  (fn [env entity-type id params] entity-type))
(defmulti read-root
  "The multimethod for Untangled's built-in support for querying with a keyword "
  (fn [env keyword params] keyword))

(declare server-read)

(defn untangled-parser
  "Builds and returns an Om parser that uses Untangled's query and mutation handling. See `defquery-entity`, `defquery-root`,
  and `defmutation` in the `untangled.server` namespace."
  []
  (om/parser {:read server-read :mutate server-mutate}))

(s/def ::action (s/cat
                  :action-name (fn [sym] (= sym 'action))
                  :action-args (fn [a] (and (vector? a) (= 1 (count a))))
                  :action-body (s/+ (constantly true))))

(s/def ::value (s/cat
                 :value-name (fn [sym] (= sym 'value))
                 :value-args (fn [a] (and (vector? a) (= 3 (count a))))
                 :value-body (s/+ (constantly true))))

(s/def ::query-entity-args (s/cat
                             :kw keyword?
                             :doc (s/? string?)
                             :value #(and (list? %) (= 'value (first %)) (vector? (second %)))))

(defmacro ^{:doc      "Define a server-side query handler for a given entity type.

(defentity-query :person/by-id
  \"Optional doc string\"
  (value [env id params] {:db/id id :person/name \"Joe\"}))

  The `env` argument will be the server parser environment, which will include all of your component injections, the AST for
  this query, along with the subquery (see Om docs). `id` will be the ID of the entity to load. `params` will be any additional
  params that were sent via the client `load` (i.e. `(load this [:person/by-id 1] Person {:params {:auth 1223555}})`).
"
            :arglists '([entity-type docstring? value])} defquery-entity
  [& args]
  (let [{:keys [kw doc value]} (util/conform! ::query-entity-args args)
        {:keys [value-args value-body]} (util/conform! ::value value)
        env-sym    (first value-args)
        id-sym     (second value-args)
        params-sym (nth value-args 2)]
    `(defmethod untangled.server/read-entity ~kw [~env-sym ~'_ ~id-sym ~params-sym]
       (let [v# (do ~@value-body)]
         {:value v#}))))

(s/def ::root-value (s/cat
                      :value-name (fn [sym] (= sym 'value))
                      :value-args (fn [a] (and (vector? a) (= 2 (count a))))
                      :value-body (s/+ (constantly true))))

(s/def ::query-root-args (s/cat
                           :kw keyword?
                           :doc (s/? string?)
                           :value #(and (list? %) (= 'value (first %)) (vector? (second %)))))

(defmacro ^{:doc      "Define a server-side query handler for queries joined at the root.

The `value` method you define will receive the full Om parser environment (server-side, with your
component injections) as `env` and any params the server sent with the specific top-level query. Note that the subquery and
AST for the query will be available in `env`.

For example: `(load app :questions Question {:params {:list 1}})` on the client would result in params being set to `{:list 1}`
on the server.

The return value of `value` will be sent to the client.

(defquery-root :questions
  \"Optional doc string\"
  (value [{:keys [ast query] :as env} {:keys [list]}]
    [{:db/id 1 :question/value \"How are you?\"}
    {:db/id 2 :question/value \"What time is it?\"}
    {:db/id 3 :question/value \"How old are you?\"}]))
"
            :arglists '([entity-type docstring? value])} defquery-root
  [& args]
  (let [{:keys [kw doc value]} (util/conform! ::query-root-args args)
        {:keys [value-args value-body]} (util/conform! ::root-value value)
        env-sym    (first value-args)
        params-sym (second value-args)]
    `(defmethod untangled.server/read-root ~kw [~env-sym ~'_ ~params-sym]
       (let [v# (do ~@value-body)]
         {:value v#}))))

(defn server-read
  "A built-in read method for Untangled's built-in server Om parser."
  [env k params]
  (let [k (-> env :ast :key)]
    (if (om.util/ident? k)
      (read-entity env (first k) (second k) params)
      (read-root env k params))))

