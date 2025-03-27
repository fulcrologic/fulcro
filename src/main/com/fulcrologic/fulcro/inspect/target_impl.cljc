(ns ^:no-doc com.fulcrologic.fulcro.inspect.target-impl
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.devtools.common.built-in-mutations :as bi]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.common.protocols :as dp]
    [com.fulcrologic.devtools.common.resolvers :refer [defmutation defresolver]]
    [com.fulcrologic.devtools.common.utils :refer [strip-lambdas]]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.inspect.devtool-api :as devtool]
    [com.fulcrologic.fulcro.inspect.diff :as diff]
    [com.fulcrologic.fulcro.inspect.inspect-client
     :refer [app-state app-uuid app-uuid-key get-history-entry
             record-history-entry! remotes runtime-atom send-failed! send-finished! send-started! state-atom]]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.wsscode.pathom.connect :as pc]
    [edn-query-language.core :as eql]
    [fulcro.inspect.api.target-api :as target]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(defonce apps* (atom {}))

(defmulti handle-inspect-event (fn [tconn app event] (:type event)))

(defmethod handle-inspect-event :default [tconn app event] (log/warn "Not implemented" (:type event)))

(defmethod handle-inspect-event `devtool/db-changed [tconn app event]
  (dp/transmit! tconn (app-uuid app) [(devtool/db-changed event)]))

(defmethod handle-inspect-event `devtool/send-started [tconn app event]
  (let [app-uuid (app-uuid app)]
    (dp/transmit! tconn app-uuid
      [(devtool/send-started (assoc event :fulcro.inspect.client/tx-ref [:network-history/id [app-uuid-key app-uuid]]))])))

(defmethod handle-inspect-event `devtool/send-finished [tconn app event]
  (let [app-uuid (app-uuid app)]
    (dp/transmit! tconn app-uuid
      [(devtool/send-finished (assoc event :fulcro.inspect.client/tx-ref [:network-history/id [app-uuid-key app-uuid]]))])))

(defmethod handle-inspect-event `devtool/send-failed [tconn app event]
  (let [app-uuid (app-uuid app)]
    (dp/transmit! tconn app-uuid
      [(devtool/send-failed (assoc event :fulcro.inspect.client/tx-ref [:network-history/id [app-uuid-key app-uuid]]))])))

(defmethod handle-inspect-event `devtool/optimistic-action [tconn app event]
  (let [app-uuid (app-uuid app)]
    (dp/transmit! tconn app-uuid
      [(devtool/optimistic-action (assoc event :fulcro.inspect.client/tx-ref [:network-history/id [app-uuid-key app-uuid]]))])))

(defmutation connected [{:devtool/keys [connection]
                         :fulcro/keys  [app]} {::mk/keys [target-id connected?]}]
  {::pc/sym `bi/devtool-connected}
  (let [networking (remotes app)
        state*     (state-atom app)
        id         (app-uuid app)]
    (dp/transmit! connection id
      [(devtool/app-started
         {:com.fulcrologic.fulcro.application/id      id
          :fulcro.inspect.client/remotes              (sort-by (juxt #(not= :remote %) str) (keys networking))
          :fulcro.inspect.client/initial-history-step {:com.fulcrologic.fulcro.application/id id
                                                       :history/version                       (record-history-entry! app @state*)
                                                       :history/value                         @state*}})])))

(defmutation reset-app [{:fulcro/keys [app]} {:history/keys [version]}]
  {::pc/sym `target/reset-app}
  (let [render! (ah/app-algorithm app :schedule-render!)]
    (enc/if-let [value (and version (:value (get-history-entry app version)))]
      (do
        (reset! (state-atom app) value)
        (render! app {:force-root? true}))
      (log/error "Reset failed. No target state ID supplied"))))

;; TODO: Test if this works, since we might want to avoid the diff system. Or at least test diff with huge app state and see the overhead
(defresolver history-resolver [env {:history/keys [id]}]
  {::pc/input  #{:history/id}
   ::pc/output [app-uuid-key
                :history/based-on
                :history/diff
                :history/value
                :history/version]}
  (let [[app-uuid desired-version] id
        params (:query-params env)
        {:keys [based-on]} params]
    (enc/if-let [app   (get @apps* app-uuid)
                 value (:value (get-history-entry app desired-version))]
      (let [{prior-state :value} (get-history-entry app based-on)
            diff  (when prior-state (diff/diff prior-state value))
            diff? (and based-on diff)]
        (cond-> {app-uuid-key     app-uuid
                 :history/version id}
          diff? (assoc :history/diff diff
                       :history/based-on based-on)
          (not diff?) (assoc :history/value value)))
      (log/error "Failed to resolve history step."))))

;; FIXME: Did we used to run EQL transactions from inspect AS transactions? If so, fix.
(defmutation run-transaction [env params]
  {::pc/sym `target/run-transaction}
  (let [{:keys [tx tx-ref]} params
        app-uuid (app-uuid params)]
    (if-let [app (get @apps* app-uuid)]
      (if tx-ref
        (rc/transact! app tx {:ref tx-ref})
        (rc/transact! app tx {}))
      (log/error "Transact on invalid uuid" app-uuid "See https://book.fulcrologic.com/#err-inspect-invalid-app-uuid"))
    nil))

(defn run-network-request* [params]
  (let [{remote-name :remote
         :keys       [eql]} params
        app-uuid       (mk/target-id params)
        result-channel (async/chan)]
    (enc/if-let [app   (get @apps* app-uuid)
                 {:keys [transmit!] :as remote} (rapp/get-remote app remote-name)
                 ast   (eql/query->ast eql)
                 tx-id (random-uuid)]
      (do
        (send-started! app remote-name tx-id eql)
        (transmit! remote {:com.fulcrologic.fulcro.algorithms.tx-processing/id             tx-id
                           :com.fulcrologic.fulcro.algorithms.tx-processing/ast            ast
                           :com.fulcrologic.fulcro.algorithms.tx-processing/idx            0
                           :com.fulcrologic.fulcro.algorithms.tx-processing/options        {}
                           :com.fulcrologic.fulcro.algorithms.tx-processing/update-handler identity
                           :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler (fn [{:keys [body] :as result}]
                                                                                             (enc/catching
                                                                                               (let [error? (ah/app-algorithm app :remote-error?)]
                                                                                                 (if (error? result)
                                                                                                   (send-failed! app remote-name tx-id result)
                                                                                                   (send-finished! app remote-name tx-id body))))
                                                                                             (async/go
                                                                                               (async/>! result-channel body)))}))
      (do
        (log/trace "Request not attempted.")
        (async/go (async/>! result-channel {:error "Unable to run network request"}))))
    result-channel))

(defmutation run-network-request [env params]
  {::pc/sym `target/run-network-request}
  (run-network-request* params))

(defresolver pathom-connect-index-resolver [env input]
  {::pc/output [{:pathom/indexes [::pc/idents ::pc/index-io ::pc/autocomplete-ignore]}]}
  (async/go
    (let [params (:query-params env)
          {remote-name :remote} params
          {::pc/keys [indexes]
           :as       resp} (async/<! (run-network-request* {mk/target-id (mk/target-id params)
                                                            :remote      remote-name
                                                            :eql         [{::pc/indexes [::pc/idents ::pc/index-io ::pc/autocomplete-ignore]}]}))]
      {:pathom/indexes indexes})))
