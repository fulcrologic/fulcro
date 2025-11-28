(ns ^:no-doc com.fulcrologic.fulcro.inspect.inspect-client
  "Functions used by Fulcro to talk to Fulcro Inspect.

  If you're using Fulcro in CLJS, then inspect will be on in dev builds, and off in production.
  You can take control of this using:

  :closure-defines {\"com.fulcrologic.fulcro.inspect.inspect_client.INSPECT\" true}
  :closure-defines {\"com.fulcrologic.fulcro.inspect.inspect_client.INSPECT\" \"disabled\"}

  If you're running Fulcro in Clojure, then use the `com.fulcrologic.fulcro.inspect` JVM system property instead
  (with true or disabled values).
  "
  #?(:cljs (:require-macros com.fulcrologic.fulcro.inspect.inspect-client))
  (:require
    [com.fulcrologic.fulcro.inspect.devtool-api :as devtool]
    [com.fulcrologic.fulcro.inspect.diff :as diff]
    [com.fulcrologic.fulcro.inspect.tools :as fit]
    #?@(:cljs [[goog.object :as gobj]])))

#?(:clj  (defonce INSPECT (System/getProperty "com.fulcrologic.fulcro.inspect"))
   :cljs (goog-define INSPECT false))

(def app-uuid-key :com.fulcrologic.fulcro.application/id)

(defn cljs?
  "Returns true when env is a cljs macro &env"
  [env]
  (boolean (:ns env)))

(defn- isoget
  "Like get, but for js objects, and in CLJC. In clj, it is just `get`. In cljs it is
  `gobj/get`."
  ([obj k] (isoget obj k nil))
  ([obj k default]
   #?(:clj  (get obj k default)
      :cljs (or (gobj/get obj (some-> k (name))) default))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers so we don't have to include other nses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn app-state [app] (some-> app :com.fulcrologic.fulcro.application/state-atom deref))
(defn runtime-atom [app] (some-> app :com.fulcrologic.fulcro.application/runtime-atom))
(defn state-atom [app] (some-> app :com.fulcrologic.fulcro.application/state-atom))
(defn remotes [app] (some-> (runtime-atom app) deref :com.fulcrologic.fulcro.application/remotes (dissoc :devtools-remote)))
(defn fulcro-app-id [app] (:com.fulcrologic.fulcro.application/id app))
(defn app-id [app] (fulcro-app-id app))
(defn app-uuid [app] (fulcro-app-id app))
(defn get-component-name [component] (when component (some-> (isoget component :fulcro$options) :displayName str)))
(defn now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(def MAX_HISTORY_SIZE 100)

(defn current-history-id
  "Current time in the recorded history of states"
  [app]
  (or (-> app (runtime-atom) deref ::time) 1))

(defn record-history-entry!
  "Record a state change in this history. Returns the ID of the newly recorded entry."
  [app state]
  (let [now (inc (current-history-id app))]
    (swap! (runtime-atom app)
      (fn [runtime]
        (let [history        (::history runtime)
              pruned-history (cond
                               (nil? history) []
                               (> (count history) MAX_HISTORY_SIZE) (subvec history 1)
                               :else history)
              new-history    (conj pruned-history {:id    now
                                                   :value state})]
          (assoc runtime
            ::time now
            ::history new-history))))
    now))

(defn get-history-entry [app id]
  (let [history (-> app runtime-atom deref ::history)
        entry   (first (filter
                         (fn [{entry-id :id}] (= id entry-id))
                         (seq history)))]
    entry))

(defn earliest-history-step [app]
  (let [history (-> app runtime-atom deref ::history)]
    (some-> history (first) :id)))

;;================================================================================
;; These functions are called by Fulcro internals. They used to talk directly to
;; and inspect tool, but now are isolated through this tool notify API, which
;; makes tooling generally available and more extensible.
;;================================================================================
(defn db-changed!
  "Notify Inspect that the database changed"
  [app old-state new-state]
  (when-not (= old-state new-state)
    (let [old-version (current-history-id app)
          new-version (record-history-entry! app new-state)
          diff        (diff/diff old-state new-state)]
      (fit/notify! app `devtool/db-changed {:history/version  new-version
                                            :history/based-on old-version
                                            :history/diff     diff}))))

(defn- devtool-remote? [remote-name] (= remote-name :devtools-remote))

(defn send-started! [app remote-name send-node-id tx]
  (when-not (devtool-remote? remote-name)
    (let [start (now)]
      (fit/notify! app `devtool/send-started {:fulcro.inspect.ui.network/remote             remote-name
                                              :fulcro.inspect.ui.network/request-id         send-node-id
                                              :fulcro.inspect.ui.network/request-started-at start
                                              :fulcro.inspect.ui.network/request-edn        tx}))))
(defn send-finished! [app remote send-node-id body]
  (when-not (devtool-remote? remote)
    (let [finished (now)]
      (fit/notify! app `devtool/send-finished {:fulcro.inspect.ui.network/request-id          send-node-id
                                               :fulcro.inspect.ui.network/request-finished-at finished
                                               :fulcro.inspect.ui.network/response-edn        body}))))
(defn send-failed! [app remote-name tx-id error]
  (when-not (devtool-remote? remote-name)
    (let [finished (now)]
      (fit/notify! app `devtool/send-failed {:fulcro.inspect.ui.network/request-id          tx-id
                                             :fulcro.inspect.ui.network/request-finished-at finished
                                             :fulcro.inspect.ui.network/error               error}))))

(defn optimistic-action-finished!
  "Notify inspect that a transaction finished.

   app - The app
   env - The mutation env that completed."
  [app
   {:keys [component ref com.fulcrologic.fulcro.algorithms.tx-processing/options]}
   {:keys [tx-id tx state-id-before sends]}]
  (let [component-name (get-component-name component)
        current-id     (current-history-id app)
        tx             (cond-> {:fulcro.inspect.ui.transactions/tx-id                    tx-id
                                :fulcro.history/client-time                              (now)
                                :fulcro.history/network-sends                            sends
                                :fulcro.history/tx                                       tx
                                :fulcro.history/db-before-id                             state-id-before
                                :fulcro.history/db-after-id                              current-id
                                :com.fulcrologic.fulcro.algorithms.tx-processing/options (dissoc options :component)}
                         component-name (assoc :component component-name)
                         ref (assoc :ident-ref ref))]
    (fit/notify! app `devtool/optimistic-action tx)))

(defmacro ido
  "Wrap a block of code that will only run if Inspect is enabled.  Code in these blocks will also be removed via
  DCE in Closure.

  In CLJS: output code that should be completely removed by the Closure compiler if both
  goog.DEBUG and com.fulcrologic.fulcro.inspect.inspect-client/INSPECT are false.

  In CLJ: code runs if the system property com.fulcrologic.fulcro.inspect is set to \"true\".

  This allows you to enable inspect messages in production by adding the following to
  your compiler config:

  :closure-defines {\"com.fulcrologic.fulcro.inspect.inspect_client.INSPECT\" true}

  Or for CLJ, use the JVM option: -Dcom.fulcrologic.fulcro.inspect=true
  "
  [& body]
  (if (cljs? &env)
    `(when (and (or ~'goog.DEBUG INSPECT) (not= "disabled" INSPECT))
       (try
         ~@body
         (catch :default ~'e)))
    `(when (= "true" INSPECT)
       (try
         ~@body
         (catch Exception ~'_e)))))

(defmacro ilet
  "Like `clojure.core/let`, but elides the block if Inspect isn't enabled.

  In CLJS: output code that should be completely removed by the Closure compiler if both
  goog.DEBUG and com.fulcrologic.fulcro.inspect.inspect-client/INSPECT are false.

  In CLJ: code runs if the system property com.fulcrologic.fulcro.inspect is set to \"true\".

  This allows you to enable inspect messages in production by adding the following to
  your compiler config:

  :closure-defines {\"com.fulcrologic.fulcro.inspect.inspect_client.INSPECT\" true}

  Or for CLJ, use the JVM option: -Dcom.fulcrologic.fulcro.inspect=true
  "
  [bindings & body]
  `(ido
     (let ~bindings
       ~@body)))
