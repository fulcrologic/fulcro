(ns com.fulcrologic.fulcro.inspect.preload
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.mutations :as fm]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.inspect.diff :as diff]
    [com.fulcrologic.fulcro.inspect.transit :as encode]
    [goog.object :as gobj]
    [cljs.core.async :as async]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(defonce started?* (atom false))
(defonce tools-app* (atom nil))
(defonce apps* (atom {}))
(defonce send-ch (async/chan (async/dropping-buffer 1024)))

(def app-uuid-key :fulcro.inspect.core/app-uuid)

(defn post-message [type data]
  (async/put! send-ch [type data]))

(declare handle-devtool-message)

(defn event-data [event]
  (some-> event (gobj/getValueByKeys "data" "fulcro-inspect-devtool-message") encode/read))

(defn start-send-message-loop []
  (async/go-loop []
    (when-let [[type data] (async/<! send-ch)]
      (log/info "Posting message" type)
      (.postMessage js/window (clj->js {:fulcro-inspect-remote-message (encode/write {:type type :data data :timestamp (js/Date.)})}) "*")
      (recur))))

(defn listen-local-messages []
  (.addEventListener js/window "message"
    (fn [event]
      (cond
        (and (identical? (.-source event) js/window)
          (gobj/getValueByKeys event "data" "fulcro-inspect-devtool-message"))
        (handle-devtool-message (event-data event))

        (and (identical? (.-source event) js/window)
          (gobj/getValueByKeys event "data" "fulcro-inspect-start-consume"))
        (start-send-message-loop)))
    false))

(defn app-uuid [app] (get (app/current-state app) app-uuid-key))

(defn app-id [app]
  (or (some-> (app/current-state app) :fulcro.inspect.core/app-id)
    (some-> (app/app-root app) comp/component-options :displayName)))

(defn transact-inspector!
  ([tx]
   (post-message :fulcro.inspect.client/transact-inspector {:fulcro.inspect.client/tx tx}))
  ([ref tx]
   (post-message :fulcro.inspect.client/transact-inspector {:fulcro.inspect.client/tx-ref ref :fulcro.inspect.client/tx tx})))

(def MAX_HISTORY_SIZE 100)

(defn fixed-size-assoc [size db key value]
  (let [{:fulcro.inspect.client/keys [history] :as db'}
        (-> db
          (assoc key value)
          (update :fulcro.inspect.client/history (fnil conj []) key))]
    (if (> (count history) size)
      (-> db'
        (dissoc (first history))
        (update :fulcro.inspect.client/history #(vec (next %))))
      db')))

(defn update-state-history [app state]
  (swap! (::app/runtime-atom app) update :fulcro.inspect.client/state-history
    #(fixed-size-assoc MAX_HISTORY_SIZE % (hash state) state)))

(defn db-update [app app-uuid old-state new-state]
  (update-state-history app new-state)
  (let [diff (diff/diff old-state new-state)]
    (post-message :fulcro.inspect.client/db-update {app-uuid-key                           app-uuid
                                                    :fulcro.inspect.client/prev-state-hash (hash old-state)
                                                    :fulcro.inspect.client/state-hash      (hash new-state)
                                                    :fulcro.inspect.client/state-delta     diff
                                                    ;:fulcro.inspect.client/state           new-state
                                                    })))

(defn dispose-app [app-uuid]
  (swap! apps* dissoc app-uuid)
  (post-message :fulcro.inspect.client/dispose-app {app-uuid-key app-uuid}))

(defn set-active-app [app-uuid]
  (post-message :fulcro.inspect.client/set-active-app {app-uuid-key app-uuid}))

(defn inspect-app [app]
  (let [networking (some-> app ::app/runtime-atom deref ::app/remotes)
        state*     (::app/state-atom app)
        app-uuid   (random-uuid)]

    (swap! apps* assoc app-uuid app)
    (update-state-history app @state*)

    (post-message :fulcro.inspect.client/init-app {app-uuid-key                         app-uuid
                                                   :fulcro.inspect.core/app-id          (app-id app)
                                                   :fulcro.inspect.client/remotes       (sort-by (juxt #(not= :remote %) str) (keys networking))
                                                   :fulcro.inspect.client/initial-state @state*
                                                   :fulcro.inspect.client/state-hash    (hash @state*)})

    (add-watch state* app-uuid
      #(db-update app app-uuid %3 %4))

    (swap! state* assoc app-uuid-key app-uuid)

    app))

(defn inspect-tx
  "Called as-if it were tx!"
  [app tx {:keys [optimistic? component ref] :as options}]
  (let [current-state  (app/current-state app)
        component-name (some-> (comp/component-options component) :displayName)
        tx             {:fulcro.inspect.ui.transactions/tx-id (::txn/id options)
                        :fulcro.history/client-time           (js/Date.)
                        :fulcro.history/tx                    tx
                        :fulcro.history/db-before-hash        (hash current-state)
                        :fulcro.history/db-after-hash         (hash current-state)
                        :fulcro.history/network-sends         []
                        :ident-ref                            ref
                        :component                            component-name}
        app-uuid       (app-uuid app)]
    ; ensure app is initialized
    (when app-uuid
      (post-message :fulcro.inspect.client/new-client-transaction {app-uuid-key              app-uuid
                                                                   :fulcro.inspect.client/tx tx}))))

(defn inspect-network
  [app remote {real-transmit! :transmit! :as network}]
  (let [start    (fn [id query]
                   (let [start    (js/Date.)
                         app-uuid (app-uuid app)]
                     (transact-inspector! [:fulcro.inspect.ui.network/history-id [app-uuid-key app-uuid]]
                       [`(fulcro.inspect.ui.network/request-start ~{:fulcro.inspect.ui.network/remote             remote
                                                                    :fulcro.inspect.ui.network/request-id         id
                                                                    :fulcro.inspect.ui.network/request-started-at start
                                                                    :fulcro.inspect.ui.network/request-edn        query})])))
        finished (fn [id response]
                   (let [finished (js/Date.)
                         app-uuid (app-uuid app)]
                     (transact-inspector! [:fulcro.inspect.ui.network/history-id [app-uuid-key app-uuid]]
                       [`(fulcro.inspect.ui.network/request-finish ~{:fulcro.inspect.ui.network/request-id          id
                                                                     :fulcro.inspect.ui.network/request-finished-at finished
                                                                     :fulcro.inspect.ui.network/response-edn        response})])))
        failed   (fn [id error]
                   (let [finished (js/Date.)
                         app-uuid (app-uuid app)]
                     (transact-inspector! [:fulcro.inspect.ui.network/history-id [app-uuid-key app-uuid]]
                       [`(fulcro.inspect.ui.network/request-finish ~{:fulcro.inspect.ui.network/request-id          id
                                                                     :fulcro.inspect.ui.network/request-finished-at finished
                                                                     :fulcro.inspect.ui.network/error               error})])))]
    (assoc network
      :transmit! (fn [real-remote {::txn/keys [id ast result-handler] :as send-node}]
                   (let [query                  (eql/ast->query ast)
                         wrapped-result-handler (fn [{:keys [status-code body] :as result}]
                                                  (try
                                                    (log/info "Telling tool send fininshed")
                                                    (if (= 200 status-code)
                                                      (finished id body)
                                                      (failed id body))
                                                    (catch :default e
                                                      (js/console.error e)))
                                                  (result-handler result))]
                     (log/info "Running real transmit")
                     (real-transmit! real-remote (assoc send-node ::txn/result-handler wrapped-result-handler))
                     (try
                       (log/info "Telling tool send started")
                       (start id query)
                       (catch :default e
                         (js/console.error e))))))))

(defn handle-devtool-message [{:keys [type data]}]
  (case type
    :fulcro.inspect.client/request-page-apps
    (doseq [app (vals @apps*)]
      (let [state        (app/current-state app)
            remote-names (-> app ::app/runtime-atom ::app/remotes keys)]
        (post-message :fulcro.inspect.client/init-app {app-uuid-key                         (app-uuid app)
                                                       :fulcro.inspect.core/app-id          (app-id app)
                                                       :fulcro.inspect.client/remotes       (sort-by (juxt #(not= :remote %) str) remote-names)
                                                       :fulcro.inspect.client/initial-state state
                                                       :fulcro.inspect.client/state-hash    (hash state)})))

    :fulcro.inspect.client/reset-app-state
    (let [{:keys                     [target-state]
           :fulcro.inspect.core/keys [app-uuid]} data]
      (if-let [app (get @apps* app-uuid)]
        (do
          (if target-state
            (let [target-state (assoc target-state app-uuid-key app-uuid)]
              (reset! (::app/state-atom app) target-state)))
          (app/force-root-render! app))
        (js/console.log "Reset app on invalid uuid" app-uuid)))

    :fulcro.inspect.client/transact
    (let [{:keys                     [tx tx-ref]
           :fulcro.inspect.core/keys [app-uuid]} data]
      (if-let [app (get @apps* app-uuid)]
        (if tx-ref
          (comp/transact! app tx-ref tx)
          (comp/transact! app tx))
        (js/console.log "Transact on invalid uuid" app-uuid)))

    :fulcro.inspect.client/pick-element
    (js/console.error "Pick Element Not implemented for Inspect v3")

    :fulcro.inspect.client/show-dom-preview
    (js/console.error "DOM Preview not implemented")

    :fulcro.inspect.client/hide-dom-preview
    (js/console.log "")

    :fulcro.inspect.client/network-request
    (let [{:keys                          [query]
           :fulcro.inspect.client/keys    [remote]
           :fulcro.inspect.ui-parser/keys [msg-id]
           :fulcro.inspect.core/keys      [app-uuid]} data
          tx-node (txn/tx-node query)]
      (when-let [app (get @apps* app-uuid)]
        (let [remote    (-> app ::app/runtime-atom deref ::app/remotes remote)
              transmit! (-> remote :transmit!)
              ast       (eql/query->ast query)]
          (transmit! remote {::txn/id             (random-uuid)
                             ::txn/ast            ast
                             ::txn/idx            0
                             ::txn/options        {}
                             ::txn/update-handler identity
                             ::txn/result-handler (fn [{:keys [status-code body]}]
                                                    (post-message :fulcro.inspect.client/message-response {:fulcro.inspect.ui-parser/msg-id       msg-id
                                                                                                           :fulcro.inspect.ui-parser/msg-response body}))}))))

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

    (js/console.log "Unknown message" type)))

(defn install [_]
  (js/document.documentElement.setAttribute "__fulcro-inspect-remote-installed__" true)

  (when-not @started?*
    (js/console.log "Installing Fulcro 3.x Inspect" {})

    (reset! started?* true)

    (app/register-tool!
      {::app/tool-id         ::fulcro-inspect-remote
       ::app/app-started     inspect-app
       ::app/network-wrapper (fn [app networks] (into {} (map (fn [[k v]] [k (inspect-network app k v)])) networks))
       ::app/tx-listen       inspect-tx})

    (listen-local-messages)))

(install {})
