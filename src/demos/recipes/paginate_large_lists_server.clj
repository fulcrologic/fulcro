(ns recipes.paginate-large-lists-server
  (:require
    [fulcro.server :refer [defquery-root defquery-entity defmutation server-mutate]]))

; a simple implementation that can generate any number of items whose ids just match their index
(defquery-root :paginate/items
  (value [env {:keys [start end]}]
    (Thread/sleep 100)
    (when (> 1000 (- end start))
      (vec (for [id (range start end)]
             {:item/id id})))))
