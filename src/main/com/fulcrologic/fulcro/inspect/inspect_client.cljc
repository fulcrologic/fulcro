(ns ^:no-doc com.fulcrologic.fulcro.inspect.inspect-client
  "Functions used by Fulcro to talk to Fulcro Inspect."
  #?(:cljs (:require-macros com.fulcrologic.fulcro.inspect.inspect-client))
  (:require
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    #?@(:cljs [[goog.object :as gobj]
               [com.fulcrologic.fulcro.inspect.diff :as diff]
               [com.fulcrologic.fulcro.inspect.transit :as encode]
               [cljs.core.async :as async]])
    [clojure.data :as data]
    [taoensso.encore :as encore]
    [taoensso.timbre :as log]
    [taoensso.encore :as enc]))

#?(:cljs (goog-define INSPECT false))

;; This is here so that you can include the element picker without killing React Native
(defonce run-picker (atom nil))

(declare handle-devtool-message)
(defonce started?* (atom false))
(defonce tools-app* (atom nil))
(defonce apps* (atom {}))
(def app-uuid-key :fulcro.inspect.core/app-uuid)

(defonce send-ch #?(:clj nil :cljs (async/chan (async/dropping-buffer 50000))))
(defn post-message [type data]
  #?(:cljs (try
             (async/put! send-ch [type data])
             (catch :default e
               (log/error "Cannot send to inspect. Channel closed.")))))

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

(defn current-history-id
  "Current time in the recorded history of states"
  [app]
  (or (-> app (runtime-atom) deref ::time) 1))

(defn record-history-entry!
  "Record a state change in this history. Returns the ID of the newly recorded entry."
  [app state]
  (let [now (current-history-id app)]
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
            ::time (inc now)
            ::history new-history))))
    now))

(defn get-history-entry [app id]
  (let [history (-> app runtime-atom deref ::history)
        entry   (first (filter
                         (fn [{entry-id :id}] (= id entry-id))
                         (seq history)))]
    entry))


(defn db-changed!
  "Notify Inspect that the database changed"
  [app old-state new-state]
  #?(:cljs
     (let [app-uuid (app-uuid app)
           state-id (record-history-entry! app new-state)]
       (post-message :fulcro.inspect.client/db-changed! {app-uuid-key                    app-uuid
                                                         :fulcro.inspect.client/state-id state-id}))))

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
       (fn [^js event]
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

;; LANDMARK: Incoming message handler for Inspect
(defn handle-devtool-message [{:keys [type data] :as message}]
  (log/debug "Devtools Message received" message)
  #?(:cljs
     (case type
       :fulcro.inspect.client/request-page-apps
       (do
         (doseq [app (vals @apps*)]
           (let [state        (app-state app)
                 state-id     (record-history-entry! app state)
                 remote-names (remotes app)]
             (post-message :fulcro.inspect.client/init-app
               {app-uuid-key                                (app-uuid app)
                :fulcro.inspect.core/app-id                 (app-id app)
                :fulcro.inspect.client/remotes              (sort-by (juxt #(not= :remote %) str)
                                                              (keys remote-names))
                :fulcro.inspect.client/initial-history-step {:id    state-id
                                                             :value state}}))))

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

       ;; Remote tool has asked for the history step at id, and can accept a diff from the given closest entry
       :fulcro.inspect.client/fetch-history-step
       (let [{:keys                     [id based-on]
              :fulcro.inspect.core/keys [app-uuid]} data]
         (enc/when-let [app (get @apps* app-uuid)
                        {:keys [value]} (get-history-entry app id)]
           (let [prior-state (get-history-entry app based-on)
                 diff        (when prior-state (diff/diff prior-state value))]
             (post-message :fulcro.inspect.client/history-entry
               (cond-> {app-uuid-key                  app-uuid
                        :fulcro.inspect.core/state-id id}
                 diff (assoc :fulcro.inspect.client/diff diff
                             :based-on based-on)
                 (not diff) (assoc :fulcro.inspect.client/state value))))))


       :fulcro.inspect.client/transact
       (let [{:keys                     [tx tx-ref]
              :fulcro.inspect.core/keys [app-uuid]} data]
         (if-let [app (get @apps* app-uuid)]
           (if tx-ref
             (comp-transact! app tx {:ref tx-ref})
             (comp-transact! app tx {}))
           (log/error "Transact on invalid uuid" app-uuid)))

       :fulcro.inspect.client/pick-element
       (if @run-picker
         (@run-picker data)
         (try
           (js/alert "Element picker not installed. Add it to your preload.")
           (catch :default _e
             (log/error "Element picker not installed in app. You must add it to you preloads."))))

       :fulcro.inspect.client/show-dom-preview
       (encore/if-let [{:fulcro.inspect.core/keys [app-uuid]} data
                       app              (some-> @apps* (get app-uuid))
                       historical-state (get-history-entry app (:fulcro.inspect.client/state-id data))
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
       (let [{:keys                          [query mutation]
              remote-name                    :fulcro.inspect.client/remote
              :fulcro.inspect.ui-parser/keys [msg-id]
              :fulcro.inspect.core/keys      [app-uuid]} data]
         (encore/when-let [app       (get @apps* app-uuid)
                           remote    (get (remotes app) remote-name)
                           transmit! (-> remote :transmit!)
                           ast       (eql/query->ast (or query mutation))
                           tx-id     (random-uuid)]
           (send-started! app remote-name tx-id (or query mutation))
           (transmit! remote {:com.fulcrologic.fulcro.algorithms.tx-processing/id             tx-id
                              :com.fulcrologic.fulcro.algorithms.tx-processing/ast            ast
                              :com.fulcrologic.fulcro.algorithms.tx-processing/idx            0
                              :com.fulcrologic.fulcro.algorithms.tx-processing/options        {}
                              :com.fulcrologic.fulcro.algorithms.tx-processing/update-handler identity
                              :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler (fn [{:keys [body] :as result}]
                                                                                                (let [error? (ah/app-algorithm app :remote-error?)]
                                                                                                  (if (error? result)
                                                                                                    (send-failed! app tx-id result)
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
       (post-message :fulcro.inspect.client/client-version {:version "3.0.0"})

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
     (when (and (or goog.DEBUG INSPECT) (not= "disabled" INSPECT))
       (let [networking (remotes app)
             state*     (state-atom app)
             app-uuid   (fulcro-app-id app)]
         (swap! apps* assoc app-uuid app)
         (record-history-entry! app @state*)
         (swap! state* assoc app-uuid-key app-uuid)
         (post-message :fulcro.inspect.client/init-app {app-uuid-key                         app-uuid
                                                        :fulcro.inspect.core/app-id          (app-id app)
                                                        :fulcro.inspect.client/remotes       (sort-by (juxt #(not= :remote %) str) (keys networking))
                                                        :fulcro.inspect.client/initial-state @state*})
         (add-watch state* app-uuid #(db-changed! app %3 %4))))))

(defn optimistic-action-finished!
  "Notify inspect that a transaction finished.

   app - The app
   env - The mutation env that completed."
  [app
   {:keys [component ref state com.fulcrologic.fulcro.algorithms.tx-processing/options]}
   {:keys [tx-id tx state-id-before db-before db-after]}]
  #?(:cljs
     (let [component-name (get-component-name component)
           current-id     (current-history-id app)
           tx             (cond-> {:fulcro.inspect.ui.transactions/tx-id                    tx-id
                                   :fulcro.history/client-time                              (js/Date.)
                                   :fulcro.history/tx                                       tx
                                   :fulcro.history/db-before-id                             state-id-before
                                   :fulcro.history/db-after-id                              current-id
                                   :fulcro.history/diff                                     (vec (take 2 (data/diff db-after db-before)))
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
    `(when (and (or ~'goog.DEBUG INSPECT) (not= "disabled" INSPECT))
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
