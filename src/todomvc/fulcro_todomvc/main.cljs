(ns fulcro-todomvc.main
  (:require
    [com.fulcrologic.fulcro.networking.websockets :as fws]
    [com.fulcrologic.fulcro.application :as app]))

(defonce app (app/fulcro-app {:remotes {:remote (fws/fulcro-websocket-remote {})}}))
