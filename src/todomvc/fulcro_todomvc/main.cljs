(ns fulcro-todomvc.main
  (:require
    [com.fulcrologic.devtools.common.target :refer [ido]]
    [com.fulcrologic.fulcro.algorithms.timbre-support :refer [console-appender prefix-output-fn]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.networking.websockets :as fws]
    [fulcro-todomvc.app :refer [app]]
    [fulcro-todomvc.custom-types :as custom-types]
    [fulcro-todomvc.ui :as ui]
    [fulcro.inspect.tool :as it]
    [taoensso.timbre :as log]))

;; Have to be installed before we create websockets
(defonce prevent-again (custom-types/install!))

(defn refresh! [] (app/mount! app ui/Root "app"))

(defn ^:export start []
  (app/mount! app ui/Root "app")
  (ido
    (it/add-fulcro-inspect! app))
  (df/load! app [:list/id 1] ui/TodoList)
  (log/merge-config! {:output-fn prefix-output-fn
                      :appenders {:console (console-appender)}}))

(comment
  (app/set-root! app ui/Root {:initialize-state? true})
  (app/mounted? app)
  (df/load! app [:list/id 1] ui/TodoList)
  (app/mount! app ui/Root "app" {:initialize-state? false})
  @(::app/state-atom app)
  (fws/stop! remote)
  )
