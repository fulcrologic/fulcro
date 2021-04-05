(ns fulcro-todomvc.app
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.networking.http-remote :as http]))

(defonce remote #_(fws/fulcro-websocket-remote {:auto-retry?        true
                                                :request-timeout-ms 10000}) (http/fulcro-http-remote {}))

(defonce app (app/fulcro-app {:remotes {:remote remote}}))

