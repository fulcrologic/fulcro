(ns fulcro-todomvc.main
  (:require
    [com.fulcrologic.fulcro.networking.http-remote :as fhr]
    [com.fulcrologic.fulcro.networking.mock-server-remote :as mock-remote]
    [com.fulcrologic.fulcro.rendering.keyframe-render :as keyframe]
    ;[com.fulcrologic.fulcro.networking.websockets :as fws]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.algorithms.timbre-support :as ts]
    [fulcro-todomvc.ui :as ui]
    [fulcro-todomvc.server :as sapi]
    [taoensso.timbre :as log]))

(log/set-level! :debug)
(log/merge-config! {:output-fn ts/prefix-output-fn
                    :appenders {:console (ts/console-appender)}})
(goog-define MOCK false)
; (goog-define WEBSOCKETS false)

(defonce app (app/fulcro-app {:shared    {:STATIC 1}
                              :shared-fn (fn [root-props]
                                           (log/info "Calc shared" root-props)
                                           {:derived 1})
                              ;:optimized-render! keyframe/render!
                              :remotes   {:remote
                                          (if MOCK
                                            (mock-remote/mock-http-server {:parser (fn [req]
                                                                                     (sapi/parser {} req))})
                                            (fhr/fulcro-http-remote {:url "/api"})
                                            #_(if WEBSOCKETS
                                                (fws/fulcro-websocket-remote {})
                                                (fhr/fulcro-http-remote {:url "/api"})))}}))

(defn ^:export start []
  (log/info "mount")
  (app/mount! app ui/Root "app")
  (log/info "submit")
  (df/load! app [:list/id 1] ui/TodoList))

(defn ^:dev/after-load refresh []
  (js/console.log "refresh UI")
  (app/mount! app ui/Root "app"))

(comment
  (comp/class->registry-key ui/Root)
  (comp/get-query ui/Root {})
  (-> app ::app/runtime-atom deref)
  (-> app ::app/state-atom deref)
  (comp/get-query ui/Root
    (-> app ::app/state-atom deref)))