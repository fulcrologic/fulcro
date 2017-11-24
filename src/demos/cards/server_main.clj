(ns cards.server-main
  (:require
    [com.stuartsierra.component :as component]
    [fulcro.server :as c]
    [taoensso.timbre :as timbre]
    [cards.server :refer [make-system]])
  (:gen-class))

;; This is a separate file, so we can control the server in dev mode from user.clj
(defn -main [& args]
  (let [system (make-system)]
    (component/start system)))
