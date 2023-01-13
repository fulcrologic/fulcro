(ns fulcro-todomvc.app
  (:require
    [com.fulcrologic.fulcro.algorithms.tx-processing.batched-processing :as btxn]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.react.version18 :as rv]
    [com.fulcrologic.fulcro.algorithms.tx-processing.batched-processing :as batch]
    [com.fulcrologic.fulcro.algorithms.tx-processing.synchronous-tx-processing :as sync]
    [com.fulcrologic.fulcro.networking.http-remote :as http]))

(defonce remote #_(fws/fulcro-websocket-remote {:auto-retry?        true
                                                :request-timeout-ms 10000}) (http/fulcro-http-remote {}))

#_(defonce app (sync/with-synchronous-transactions
                 (app/fulcro-app {:remotes {:remote remote}})
                 #{:remote}))

(defonce app (rv/with-react18
               (btxn/with-batched-reads
                 (app/fulcro-app {:remotes {:remote remote}})
                 #{:remote})))

#_(defonce app (app/fulcro-app {:remotes {:remote remote}}))

