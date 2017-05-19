(ns recipes.background-loads-server
  (:require [cards.server-api :as api :refer [server-read server-mutate]]
            [taoensso.timbre :as timbre]))

(defmethod server-read :background.child/by-id [{:keys [parser query] :as env} dispatch-key params]
  (when (= query [:background/long-query])
    {:value (parser env query)}))

(defmethod server-read :background/long-query [{:keys [ast query] :as env} dispatch-key params]
  (timbre/info "Long query started")
  (Thread/sleep 5000)
  (timbre/info "Long query finished")
  {:value 42})

