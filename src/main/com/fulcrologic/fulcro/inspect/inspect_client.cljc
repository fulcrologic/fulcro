(ns com.fulcrologic.fulcro.inspect.inspect-client
  "Functions used by Fulcro to talk to Fulcro Inspect."
  #?(:cljs (:require-macros com.fulcrologic.fulcro.inspect.inspect-client))
  (:require
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    #?@(:cljs [[goog.object :as gobj]
               [com.fulcrologic.fulcro.inspect.diff :as diff]
               [com.fulcrologic.fulcro.inspect.transit :as encode]
               [cljs.core.async :as async]])
    [taoensso.encore :as encore]
    [taoensso.timbre :as log]))

#?(:cljs (goog-define INSPECT false))

(declare handle-devtool-message)
(defonce started?* (atom false))
(defonce tools-app* (atom nil))
(defonce apps* (atom {}))
(def app-uuid-key :fulcro.inspect.core/app-uuid)

(defonce send-ch #?(:clj nil :cljs (async/chan (async/dropping-buffer 50000))))
(defn post-message [type data]
  #?(:cljs (async/put! send-ch [type data])))

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
(defn app-uuid [app] (some-> app :com.fulcrologic.fulcro.application/state-atom deref (get app-uuid-key)))
(defn remotes [app] (some-> (runtime-atom app) deref :com.fulcrologic.fulcro.application/remotes))
(defn app-id [app] (some-> (app-state app) :fulcro.inspect.core/app-id))
(defn fulcro-app-id [app] (:com.fulcrologic.fulcro.application/id app))
(defn get-component-name [component] (when component (some-> (isoget component :fulcro$options) :displayName)))
(defn comp-transact! [app tx options]
  (let [tx! (ah/app-algorithm app :tx!)]
    (tx! app tx options)))


(def MAX_HISTORY_SIZE 100)

(defn- fixed-size-assoc [size db key value]
  (let [{:fulcro.inspect.client/keys [history] :as db'}
        (-> db
          (assoc key value)
          (update :fulcro.inspect.client/history (fnil conj []) key))]
    (if (> (count history) size)
      (-> db'
        (dissoc (first history))
        (update :fulcro.inspect.client/history #(vec (next %))))
      db')))

(defn- update-state-history
  "Record a snapshot of history on the app itself for inspect to reference via events to do things like preview
   history."
  [app state]
  (swap! (runtime-atom app) update :fulcro.inspect.client/state-history
    #(fixed-size-assoc MAX_HISTORY_SIZE % (hash state) state)))

(defn db-from-history [app state-hash]
  (some-> (runtime-atom app) deref :fulcro.inspect.client/state-history (get state-hash)))

(defn db-changed!
  "Notify Inspect that the database changed"
  [app old-state new-state]
  #?(:cljs
     (let [app-uuid (app-uuid app)]
       (update-state-history app new-state)
       (let [diff (diff/diff old-state new-state)]
         (post-message :fulcro.inspect.client/db-update {app-uuid-key                           app-uuid
                                                         :fulcro.inspect.client/prev-state-hash (hash old-state)
                                                         :fulcro.inspect.client/state-hash      (hash new-state)
                                                         :fulcro.inspect.client/state-delta     diff})))))

(defn event-data [event]
  #?(:cljs (some-> event (gobj/getValueByKeys "data" "fulcro-inspect-devtool-message") encode/read)))

(defn start-send-message-loop []
  #?(:cljs
     (async/go-loop []
       (when-let [[type data] (async/<! send-ch)]
         (.postMessage js/window (clj->js {:fulcro-inspect-remote-message (encode/write {:type type :data data :timestamp (js/Date.)})}) "*")
         (recur)))))

(defn listen-local-messages []
  #?(:cljs
     (.addEventListener js/window "message"
       (fn [event]
         (cond
           (and (identical? (.-source event) js/window)
             (gobj/getValueByKeys event "data" "fulcro-inspect-devtool-message"))
           (handle-devtool-message (event-data event))

           (and (identical? (.-source event) js/window)
             (gobj/getValueByKeys event "data" "fulcro-inspect-start-consume"))
           (start-send-message-loop)))
       false)))



(defn transact-inspector!
  ([tx]
   (post-message :fulcro.inspect.client/transact-inspector {:fulcro.inspect.client/tx tx}))
  ([ref tx]
   (post-message :fulcro.inspect.client/transact-inspector {:fulcro.inspect.client/tx-ref ref :fulcro.inspect.client/tx tx})))

(defn dispose-app [app-uuid]
  (swap! apps* dissoc app-uuid)
  (post-message :fulcro.inspect.client/dispose-app {app-uuid-key app-uuid}))

(defn set-active-app [app-uuid]
  (post-message :fulcro.inspect.client/set-active-app {app-uuid-key app-uuid}))

#_(defn inspect-app [app]
    #?(:cljs
       (let [networking (remotes app)
             state*     (state-atom app)
             app-uuid   (random-uuid)]

         (swap! apps* assoc app-uuid app)
         #_(update-state-history app @state*)

         (post-message :fulcro.inspect.client/init-app {app-uuid-key                         app-uuid
                                                        :fulcro.inspect.core/app-id          (app-id app)
                                                        :fulcro.inspect.client/remotes       (sort-by (juxt #(not= :remote %) str) (keys networking))
                                                        :fulcro.inspect.client/initial-state @state*
                                                        :fulcro.inspect.client/state-hash    (hash @state*)})

         (add-watch state* app-uuid #(db-update app app-uuid %3 %4))

         (swap! state* assoc app-uuid-key app-uuid)

         app)))

(defn send-started! [app remote tx-id txn]
  #?(:cljs
     (let [start    (js/Date.)
           app-uuid (app-uuid app)]
       (transact-inspector! [:fulcro.inspect.ui.network/history-id [app-uuid-key app-uuid]]
         [`(fulcro.inspect.ui.network/request-start ~{:fulcro.inspect.ui.network/remote             remote
                                                      :fulcro.inspect.ui.network/request-id         tx-id
                                                      :fulcro.inspect.ui.network/request-started-at start
                                                      :fulcro.inspect.ui.network/request-edn        txn})]))))

(defn send-finished! [app remote tx-id response]
  #?(:cljs
     (let [finished (js/Date.)
           app-uuid (app-uuid app)]
       (transact-inspector! [:fulcro.inspect.ui.network/history-id [app-uuid-key app-uuid]]
         [`(fulcro.inspect.ui.network/request-finish ~{:fulcro.inspect.ui.network/request-id          tx-id
                                                       :fulcro.inspect.ui.network/request-finished-at finished
                                                       :fulcro.inspect.ui.network/response-edn        response})]))))

(defn send-failed! [app tx-id error]
  #?(:cljs
     (let [finished (js/Date.)
           app-uuid (app-uuid app)]
       (transact-inspector! [:fulcro.inspect.ui.network/history-id [app-uuid-key app-uuid]]
         [`(fulcro.inspect.ui.network/request-finish ~{:fulcro.inspect.ui.network/request-id          tx-id
                                                       :fulcro.inspect.ui.network/request-finished-at finished
                                                       :fulcro.inspect.ui.network/error               error})]))))
(defn handle-devtool-message [{:keys [type data]}]
  #?(:cljs
     (case type
       :fulcro.inspect.client/request-page-apps
       (do
         (doseq [app (vals @apps*)]
           (let [state        (app-state app)
                 remote-names (remotes app)]
             (post-message :fulcro.inspect.client/init-app {app-uuid-key                         (app-uuid app)
                                                            :fulcro.inspect.core/app-id          (app-id app)
                                                            :fulcro.inspect.client/remotes       (sort-by (juxt #(not= :remote %) str) remote-names)
                                                            :fulcro.inspect.client/initial-state state
                                                            :fulcro.inspect.client/state-hash    (hash state)}))))

       :fulcro.inspect.client/reset-app-state
       (let [{:keys                     [target-state]
              :fulcro.inspect.core/keys [app-uuid]} data]
         (if-let [app (get @apps* app-uuid)]
           (let [render! (ah/app-algorithm app :schedule-render!)]
             (if target-state
               (let [target-state (assoc target-state app-uuid-key app-uuid)]
                 (reset! (state-atom app) target-state)))
             (render! app {:force-root? true}))
           (log/info "Reset app on invalid uuid" app-uuid)))

       :fulcro.inspect.client/transact
       (let [{:keys                     [tx tx-ref]
              :fulcro.inspect.core/keys [app-uuid]} data]
         (if-let [app (get @apps* app-uuid)]
           (if tx-ref
             (comp-transact! app tx {:ref tx-ref})
             (comp-transact! app tx {}))
           (log/error "Transact on invalid uuid" app-uuid)))

       :fulcro.inspect.client/pick-element
       (log/error "Pick Element Not implemented for Inspect v3")

       :fulcro.inspect.client/show-dom-preview
       (encore/if-let [{:fulcro.inspect.core/keys [app-uuid]} data
                       app              (some-> @apps* (get app-uuid))
                       historical-state (db-from-history app (:fulcro.inspect.client/state-hash data))
                       historical-app   (assoc app :com.fulcrologic.fulcro.application/state-atom (atom historical-state))
                       render!          (ah/app-algorithm app :render!)]
         (do
           (render! historical-app {:force-root? true}))
         (log/error "Unable to find app/state for preview."))

       :fulcro.inspect.client/hide-dom-preview
       (encore/when-let [{:fulcro.inspect.core/keys [app-uuid]} data
                         app     (some-> @apps* (get app-uuid))
                         render! (ah/app-algorithm app :render!)]
         (render! app {:force-root? true}))

       :fulcro.inspect.client/network-request
       (let [{:keys                          [query]
              remote-name                    :fulcro.inspect.client/remote
              :fulcro.inspect.ui-parser/keys [msg-id]
              :fulcro.inspect.core/keys      [app-uuid]} data]
         (encore/when-let [app       (get @apps* app-uuid)
                           remote    (get (remotes app) remote-name)
                           transmit! (-> remote :transmit!)
                           ast       (eql/query->ast query)
                           tx-id     (random-uuid)]
           (send-started! app remote-name tx-id query)
           (transmit! remote {:com.fulcrologic.fulcro.algorithms.tx-processing/id             tx-id
                              :com.fulcrologic.fulcro.algorithms.tx-processing/ast            ast
                              :com.fulcrologic.fulcro.algorithms.tx-processing/idx            0
                              :com.fulcrologic.fulcro.algorithms.tx-processing/options        {}
                              :com.fulcrologic.fulcro.algorithms.tx-processing/update-handler identity
                              :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler (fn [{:keys [body] :as result}]
                                                                                                (let [error? (ah/app-algorithm app :remote-error?)]
                                                                                                  (if (error? result)
                                                                                                    (send-failed! app remote-name result)
                                                                                                    (send-finished! app remote-name tx-id body)))
                                                                                                (post-message :fulcro.inspect.client/message-response
                                                                                                  {:fulcro.inspect.ui-parser/msg-id       msg-id
                                                                                                   :fulcro.inspect.ui-parser/msg-response body}))})))

       :fulcro.inspect.client/console-log
       (let [{:keys [log log-js warn error]} data]
         (cond
           log
           (js/console.log log)

           log-js
           (js/console.log (clj->js log-js))

           warn
           (js/console.warn warn)

           error
           (js/console.error error)))

       :fulcro.inspect.client/check-client-version
       (post-message :fulcro.inspect.client/client-version {:version "2.2.5"})

       (log/debug "Unknown message" type))))


(defn install [_]
  #?(:cljs
     (do
       (js/document.documentElement.setAttribute "__fulcro-inspect-remote-installed__" true)

       (when-not @started?*
         (log/info "Installing Fulcro 3.x Inspect" {})

         (reset! started?* true)

         (listen-local-messages)))))

(defn app-started!
  "Register the application with Inspect, if it is available."
  [app]
  #?(:cljs
     (let [networking (remotes app)
           state*     (state-atom app)
           app-uuid   (fulcro-app-id app)]
       (swap! apps* assoc app-uuid app)
       (update-state-history app @state*)
       (swap! state* assoc app-uuid-key app-uuid)
       (post-message :fulcro.inspect.client/init-app {app-uuid-key                         app-uuid
                                                      :fulcro.inspect.core/app-id          (app-id app)
                                                      :fulcro.inspect.client/remotes       (sort-by (juxt #(not= :remote %) str) (keys networking))
                                                      :fulcro.inspect.client/initial-state @state*
                                                      :fulcro.inspect.client/state-hash    (hash @state*)})
       (add-watch state* app-uuid #(db-changed! app %3 %4)))))

(defn optimistic-action-finished!
  "Notify inspect that a transaction finished.

   app - The app
   env - The mutation env that completed."
  [app
   {:keys [component ref state com.fulcrologic.fulcro.algorithms.tx-processing/options]}
   {:keys [tx-id tx state-before]}]
  #?(:cljs
     (let [component-name (get-component-name component)
           tx             (cond-> {:fulcro.inspect.ui.transactions/tx-id                    tx-id
                                   :fulcro.history/client-time                              (js/Date.)
                                   :fulcro.history/tx                                       tx
                                   :fulcro.history/db-before-hash                           (hash state-before)
                                   :fulcro.history/db-after-hash                            (hash @state)
                                   :fulcro.history/network-sends                            []
                                   :com.fulcrologic.fulcro.algorithms.tx-processing/options options}
                            component-name (assoc :component component-name)
                            ref (assoc :ident-ref ref))
           app-uuid       (app-uuid app)]
       (post-message :fulcro.inspect.client/new-client-transaction {app-uuid-key              app-uuid
                                                                    :fulcro.inspect.client/tx tx}))))

(defmacro ido
  "Wrap a block of code that will only run if Inspect is enabled.  Code in these blocks will also be removed via
  DCE in Closure.

  This macro emits nothing when run in clj, and will output code that
  should be completely removed by the Closure compiler if both
  goog.DEBUG and com.fulcrologic.fulcro.inspect.inspect-client/INSPECT are false.

  This allows you to enable inspect messages in production by adding the following to
  your compiler config:

  :closure-defines {\"com.fulcrologic.fulcro.inspect.inspect_client.INSPECT\" true}
  "
  [& body]
  (when (cljs? &env)
    `(when (or ~'goog.DEBUG INSPECT)
       (try
         ~@body
         (catch :default ~'e)))))

(defmacro ilet
  "Like `clojure.core/let`, but elides the block if Inspect isn't enabled.

  This macro emits nothing when run in clj, and will output code that
  should be completely removed by the Closure compiler if both
  goog.DEBUG and com.fulcrologic.fulcro.inspect.inspect-client/INSPECT are false.

  This allows you to enable inspect messages in production by adding the following to
  your compiler config:

  :closure-defines {\"com.fulcrologic.fulcro.inspect.inspect_client.INSPECT\" true}
  "
  [bindings & body]
  (when (cljs? &env)
    `(ido
       (let ~bindings
         ~@body))))
