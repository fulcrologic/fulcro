(ns recipes.background-loads-server
  (:require [fulcro.server :refer [defquery-root defquery-entity defmutation]]
            [taoensso.timbre :as timbre]))

(defquery-entity :background.child/by-id
  (value [{:keys [parser query] :as env} id params]
    (when (= query [:background/long-query])
      (parser env query))))

(defquery-root :background/long-query
  (value [{:keys [ast query] :as env} params]
    (timbre/info "Long query started")
    (Thread/sleep 5000)
    (timbre/info "Long query finished")
    42))

