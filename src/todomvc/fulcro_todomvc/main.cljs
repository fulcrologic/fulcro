(ns fulcro-todomvc.main
  (:require
    [fulcro-todomvc.ui :as ui]
    [com.fulcrologic.fulcro.rendering.keyframe-render2 :as kr2]
    [com.fulcrologic.fulcro.networking.http-remote :as http]
    [com.fulcrologic.fulcro.networking.websockets :as fws]
    [com.fulcrologic.fulcro.algorithms.timbre-support :refer [console-appender prefix-output-fn]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.algorithms.tx-processing.synchronous-tx-processing :as stx]
    [taoensso.timbre :as log]
    [fulcro-todomvc.custom-types :as custom-types]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.algorithms.transit :as transit]))

;; Have to be installed before we create websockets
(defonce prevent-again (custom-types/install!))

(defonce remote #_(fws/fulcro-websocket-remote {:auto-retry?        true
                                              :request-timeout-ms 10000}) (http/fulcro-http-remote {}))

(defonce app (stx/with-synchronous-transactions
               (app/fulcro-app {:remotes          {:remote remote}
                                :client-did-mount (fn [app]
                                                    (dr/initialize! app)
                                                    (df/load! app [:list/id 1] ui/TodoList)
                                                    (log/merge-config! {:output-fn prefix-output-fn
                                                                        :appenders {:console (console-appender)}}))})))

(defn ^:export start []
  (app/mount! app ui/Root "app")
  )

(comment
  (app/set-root! app ui/Root {:initialize-state? true})
  (app/mounted? app)
  (df/load! app [:list/id 1] ui/TodoList)
  (app/mount! app ui/Root "app" {:initialize-state? false})
  @(::app/state-atom app)
  (fws/stop! remote)
  )
