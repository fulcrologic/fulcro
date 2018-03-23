(ns fulcro.modular-server
  (:require
    [com.stuartsierra.component :as component]
    [fulcro.server :as server]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Module-based composable server : DEPRECATED
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol Module
  (system-key [this]
    "Should return the key under which the module will be located in the system map.
     Unique-ness is checked and will be asserted.")
  (components [this]
    "Should return a map of components that this Module wants to build. Note that this does *not* cause
    them to be injected into this module. You still need to do that by wrapping an instance of the module
    in component/using! Unique-ness of the keywords is checked and will be asserted, so it pays to namespace them."))

(defprotocol APIHandler
  (api-read [this]
    "Returns a Fulcro read emitter for parsing read queries, ie: `(fn [env k params] ...)`.
     The emitter can return an untruthy value (`nil` or `false`),
     which tells the fulcro api-handler to try the next `Module` in the `:modules` chain.")
  (api-mutate [this]
    "Returns a Fulcro mutate emitter for parsing mutations, ie: `(fn [env k params] ...)`.
     The emitter can return an untruthy value (`nil` or `false`),
     which tells the fulcro api-handler to try the next `Module` in the `:modules` chain."))

(defn- chain
  "INTERNAL use only, use `fulcro-system` instead."
  [F api-fn module]
  (if-not (satisfies? APIHandler module) F
                                         (let [parser-fn (api-fn module)]
                                           (fn [env k p]
                                             (or (parser-fn (merge module env) k p)
                                               (F env k p))))))

(defn- comp-api-modules
  "INTERNAL use only, use `fulcro-system` instead."
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

(defrecord FulcroApiHandler [app-name modules]
  component/Lifecycle
  (start [this]
    (let [api-url     (cond->> "/api" app-name (str "/" app-name))
          api-parser  (server/parser (comp-api-modules this))
          api-handler (partial server/handle-api-request api-parser)]
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
  "INTERNAL use only, use `fulcro-system` instead."
  [opts]
  (let [module-keys (mapv system-key (:modules opts))]
    (component/using
      (map->FulcroApiHandler
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

(defn fulcro-system
  "DEPRECATED. Do not use in new code. Library composition is better accomplished using the standard parser hooks.

  More powerful variant of `make-fulcro-server` that allows for libraries to provide
   components and api methods (by implementing `components` and `APIHandler` respectively).
   However note that `fulcro-system` does not include any components for you,
   so you'll have to include things like a web-server (eg: `make-web-server`), middleware,
   config, etc...

   Takes a map with keys:
   * `:api-handler-key` - OPTIONAL, Where to place the generated (composed from modules) api-handler in the system-map. The
                          generated component will be injectable, and will contain the key `:middleware` whose value
                          is a (fn [h] (fn [req] resp)) that handles `/api` requests.
                          Defaults to `:fulcro.server/api-handler`.
   * `:app-name` - OPTIONAL, a string that will turn \"/api\" into \"/<app-name>/api\".
   * `:components` - A `com.stuartsierra.component/system-map` of components that this module wants to ADD to the overall system. Libraries should namespace their components.
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
          (merge-with-no-duplicates! "fulcro-system"
            (:components opts)
            (apply merge-with-no-duplicates! "Module/system-key"
              (map (comp (partial apply hash-map) (juxt system-key identity)) modules))
            (apply merge-with-no-duplicates! "Module/components"
              (map components modules))
            {(or api-handler-key ::api-handler)
             (api-handler opts)})))
    (vary-meta assoc ::api-handler-key (or api-handler-key ::api-handler))))

