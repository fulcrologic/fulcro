(ns fulcro-todomvc.main
  (:require
    [fulcro-todomvc.ui :as ui]
    [com.fulcrologic.fulcro.rendering.keyframe-render2 :as kr2]
    [com.fulcrologic.fulcro.networking.http-remote :as http]
    [com.fulcrologic.fulcro.networking.websockets :as fws]
    [com.fulcrologic.fulcro.algorithms.timbre-support :refer [console-appender prefix-output-fn]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [taoensso.timbre :as log]))

(defonce remote #_(fws/fulcro-websocket-remote {:auto-retry?        true
                                                :request-timeout-ms 10000}) (http/fulcro-http-remote {}))

(defonce app (app/fulcro-app {:remotes          {:remote remote}
                              :client-did-mount (fn [_]
                                                  (log/merge-config! {:output-fn prefix-output-fn
                                                                      :appenders {:console (console-appender)}}))
                              #_#_:optimized-render! kr2/render!}))

(defn start []
  (app/mount! app ui/Root "app")
  ;(df/load! app :com.wsscode.pathom/trace nil)
  (df/load! app [:list/id 1] ui/TodoList))

(comment
  (app/set-root! app ui/Root {:initialize-state? true})
  (app/mounted? app)
  (df/load! app [:list/id 1] ui/TodoList)
  (app/mount! app ui/Root "app" {:initialize-state? false})
  @(::app/state-atom app)
  (fws/stop! remote)
  )
