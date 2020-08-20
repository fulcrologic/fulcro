(ns com.fulcrologic.fulcro.offline.load-cache
  "Support for a loading system that supports keeping previously-seen query responses in a persistent
   cache. It supports the following features:

   * Make a failed load return the last cached value.
   ** Support for a default value for the load in case you are offline and nothing is in the cache.
   * Support for an eager/fast return from cache even when online.
   ** Indicating if the actual return should just go to cache, or both cache and app merge.

   This support replaces the default definition of the `internal-load` mutation in your Fulcro app, and hooks
   cache control into the normal load options (open map). If no caching options are given in a `load!`, then
   it uses Fulcro's built-in implementation of load.

   WARNING: Mixing this with durable mutations can be a problem. You should only use a load cache on
   items that *cannot* be modified by durable mutations unless you're willing to also update the
   load cache to reflect the mutation changes; otherwise the load cache can get out of sync with the
   durable mutations and cause all manner of havoc. This facility is primarily about making well-known
   data (e.g. inventory items, dropdown options, etc.) available even when the server is not responding.

   Installation: See `with-load-cache`

   Usage: Use the `load!` and `load-eagerly!` functions from this ns. Operations in `data-fetch` ns will continue to work
   in an unmodified fashion.
   "
  (:require
    [clojure.core.async :as async]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.fulcro.offline.durable-edn-store :as des]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as dnu]
    [com.fulcrologic.fulcro.algorithms.scheduling :as scheduling]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.algorithms.do-not-use]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.application :as app]))

(defn- now-ms [] (inst-ms #?(:cljs (js/Date.) :clj (java.util.Date.))))

(defn current-load-store
  "Returns the EDN store that is currently being used to store cached loads."
  [app]
  (get-in app [:com.fulcrologic.fulcro.application/config ::load-cache]))

(defn load-store-options
  "Returns the options that were passed when configuring the load cache."
  [app]
  (get-in app [:com.fulcrologic.fulcro.application/config ::options]))

(defn- ast-of-load [params] (eql/query->ast (:query params)))
(defn- cached-load-request? [env] (boolean (-> env ::txn/options ::use-cache?)))
(defn- eager? [env] (boolean (-> env ::txn/options ::eager?)))
(defn- default-value [env] (-> env ::txn/options ::default-value))
(defn- merge-new-result? [env] (boolean (-> env ::txn/options ::merge-new-result?)))
(defn- cache-key "Get the cache key for the load request in env."
  [{:keys [query] :as params}]
  (str (hash query)))

(defn- cached-value [env {:keys [query] :as params}]
  (async/go
    (let [k      (cache-key params)
          store  (current-load-store (:app env))
          bucket (async/<! (des/-load-edn! store k))]
      (if (contains? bucket query)
        (get-in bucket [query :result])
        (default-value env)))))

(defn- merge-last-load!
  "Asynchronously retrieves the value last loaded for the load represented by env, and merges it with app state. If
   nothing is in the cache, it uses the default value of the options. Returns a channel."
  [{:keys [app] :as env} params]
  (async/go
    (let [data (async/<! (cached-value env params))
          env  (assoc env :result {:body        data
                                   :transaction (eql/ast->query (ast-of-load params))}
                          :transmitted-ast (ast-of-load params))]
      (when-let [mutation (some-> env ::txn/options ::on-cached-load m/mutation-symbol)]
        (comp/transact! app [(list mutation (select-keys params [:query :target :remote :marker]))]))
      (log/debug "Completing load with value from cache.")
      (df/finish-load! env params)
      ;; we have to defer the render because we're in async, and regular tx just submitted one...so, this
      ;; one would get lost
      (scheduling/defer #(app/schedule-render! app {:force-root? true}) 100)
      true)))

(defn- cached-load-action [{:keys [app] :as env} params]
  (if (eager? env)
    (async/go
      (try
        (merge-last-load! env params)
        (catch #?(:cljs :default :clj Exception) e))
      (async/>! (::lock-channel env) :complete))
    (df/set-load-marker! app (:marker params) :loading)))

(defn- save-load-result! [env params]
  (async/go
    (let [k     (cache-key params)
          query (:query params)
          store (current-load-store (:app env))]
      (if (async/<! (des/-exists? store k))
        (des/-update-edn! store k (fn [bucket]
                                    (assoc bucket query {:cache-time (now-ms)
                                                         :result     (-> env :result :body)})))
        (des/-save-edn! store k {query {:cache-time (now-ms)
                                        :result     (-> env :result :body)}})))))

(defn- finish-load! [env params]
  (save-load-result! env params)
  (when (or
          (not (eager? env))
          (merge-new-result? env))
    (df/finish-load! env params)))

(defn- cached-load-result-action [{:keys [result app ast] :as env} params]
  (let [remote-error? (ah/app-algorithm app :remote-error?)
        finish!       #(cond
                         (and (not (eager? env)) (remote-error? result)) (merge-last-load! env params)
                         (remote-error? result) (log/error "Load failed. Using cached value.")
                         (not (remote-error? result)) (finish-load! env params))]
    (if (eager? env)
      (async/go
        ;; wait until the eager load is done
        (async/<! (::lock-channel env))
        (finish!))
      (finish!))))

(declare internal-load!)
(defmethod m/mutate `internal-load! [{::txn/keys [options]
                                      :keys      [ast] :as env}]
  (let [params       (get ast :params)
        {:keys [remote query marker]} params
        remote-key   (or remote :remote)
        lock-channel (async/chan 1)]
    (cond-> {:action        (fn [{:keys [app] :as env}]
                              (let [env (assoc env ::lock-channel lock-channel)]
                                (if (cached-load-request? env)
                                  (cached-load-action env params)
                                  (df/set-load-marker! app marker :loading))))
             :result-action (fn [{:keys [result app] :as env}]
                              (let [env (assoc env ::lock-channel lock-channel)]
                                (if (cached-load-request? env)
                                  (cached-load-result-action env params)
                                  (let [remote-error? (ah/app-algorithm app :remote-error?)]
                                    (if (remote-error? result)
                                      (df/load-failed! env params)
                                      (df/finish-load! env params))))))
             remote-key     (fn [_] (ast-of-load params))})))

(defn with-load-cache
  "Installs support for load caching to the provided app. Returns a new app, so it must be used like so:

  ```
  (defonce app (-> (fulcro-app {})
                 (with-load-cache edn-store)))
  ```

  Currently `options` is unused.

  Then you can leverage the caching using transaction options, or simply by calling the `load!` and
  `load-eagerly!` defined in this namespace.

  The transaction options (under `::txn/options` key in load parameter map) that are supported are:

  * `::use-cache` - Boolean. Use this caching support. When false, just does standard Fulcro load.
  * `::default-value` - A map or vector representing the default value to use if the load and cache both fail.
  * `::on-cached-load` - A mutation to run whenever the cache is used instead of a real response.
  * `::eager?` - Boolean. Should the cache be used optimistically?
  * `::merge-new-result?` - Boolean. Only applies if `::eager?` was set to true: should the real network result
                            be merged when it arrives? Default is false.

  This and the `load-eagerly!` functions just make it easier (mostly unnecessary) to pass these additional options.
  "
  ([app edn-store options]
   (-> app
     (assoc-in [:com.fulcrologic.fulcro.application/config :load-mutation] `internal-load!)
     (assoc-in [:com.fulcrologic.fulcro.application/config ::load-cache] edn-store)
     (assoc-in [:com.fulcrologic.fulcro.application/config ::options] options)))
  ([app edn-store]
   (with-load-cache app edn-store {})))

(>defn load!
  "Load `data-fetch/load!`, but enables load caching. The default mode always tries to fetch the data as normal, and
   only resorts to the cache on failure.

   * `app-ish` - An app or component
   * `query-key` - A keyword or ident to root the server query
   * `query-component` - An optional (may be nil) component that will be used to derive the sub-graph query and normalization.
   * `default-value` is required, and one or many of `query-component` instances.
   * `options` is just like for `data-fetch/load!`. See that function for full details.

   NOTE: The normal `data-fetch/load!` actions (e.g. post mutations, targeting) will always happen, since this function
   will essentially guarantee that *some* result is available (if nothing more than the default value). Thus, it is
   impossible to see that a low-level network error even happened when using this function.

   As a result, you may supply a *mutation* via the options map as `::on-cached-load mutation`.  The mutation will be submitted
   if the load is satisfied by the cache, and will include the normalized load parameters (e.g. a map that includes
   `:query`).

   Example:

   ```
   ;; The top-level key of the server remote result will be :inventory/items, but the default-value is just the value(s)
   ;; under that key.
   (lc/load! SPA :inventory/items Item [{:item/id 1 :item/name \"boo\"}] {:target [:all-items]})

   ;; Loading by ident is fine, but the default value will then always be a map (the Item to default to in this case):
   (lc/load! SPA [:item/id 2] Item {:item/id 2 :item/name \"BAH\"})
   ```

   See also `load-eagerly!`."
  ([app-ish query-key query-component default-value]
   [any? (s/or :k keyword? :id eql/ident?) (? comp/component-class?) (s/or :one map? :many vector?) => uuid?]
   (load! app-ish query-key query-component default-value {}))
  ([app-ish query-key query-component default-value {::keys [on-cached-load] :as options}]
   [any? (s/or :k keyword? :id eql/ident?) (? comp/component-class?) (s/or :one map? :many vector?) map? => uuid?]
   (when (not= `internal-load! (-> app-ish comp/any->app ::app/config :load-mutation))
     (log/error "LOAD CACHE NOT INSTALLED! Did you remember to use `with-load-cache` on your app?"))
   (let [default-value {query-key default-value}]
     (df/load! app-ish query-key query-component (dnu/deep-merge options
                                                   {::txn/options (cond->
                                                                    {::use-cache?    true
                                                                     ::default-value default-value}
                                                                    on-cached-load (assoc ::on-cached-load on-cached-load))})))))

(>defn load-eagerly!
  "Just like `load!` from this same namespace, but this version will *optimistically* use a result from the cache if it exists
   (making startup very fast), and will *only* update the *cache* with the result returned from the remote when it arrives.

   You can optionally choose to have the new network result merged with app state by passing `::merge-new-result? true` in `options`.

   Normal `data-fetch/load!` options (e.g. post mutations, targeting, etc.). *are only triggered* whenever merging
   to app state happens. Thus, a post-mutation can be run on the optimistic eager load, but it will only run for the
   network response if you've chosen to merge that with app state when it arrives."
  ([app-ish query-key query-component default-value]
   [any? (s/or :k keyword? :id eql/ident?) (? comp/component-class?) (s/or :one map? :many vector?) => uuid?]
   (let [default-value {query-key default-value}]
     (load-eagerly! app-ish query-key query-component default-value {})))
  ([app-ish query-key query-component default-value options]
   [any? (s/or :k keyword? :id eql/ident?) (? comp/component-class?) (s/or :one map? :many vector?) map? => uuid?]
   (let [default-value {query-key default-value}]
     (load! app-ish query-key query-component default-value (dnu/deep-merge options
                                                              {::txn/options {::eager?            true
                                                                              ::merge-new-result? (boolean (::merge-new-result? options))}})))))
