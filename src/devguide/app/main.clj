(ns app.main
  (:require
    [com.stuartsierra.component :as component]
    [app.system :as sys]
    [fulcro.server :refer [load-config]])
  (:gen-class))

; Production entry point.

(defn -main
  "Main entry point for the server"
  [& args]
  (let [system (sys/make-system)]
    (component/start system)))
