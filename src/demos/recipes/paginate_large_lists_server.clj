(ns recipes.paginate-large-lists-server
  (:require [cards.server-api :as api]))

; a simple implementation that can generate any number of items whose ids just match their index
(defmethod api/server-read :paginate/items [env k {:keys [start end]}]
  (Thread/sleep 100)
  (when (> 1000 (- end start))
    {:value (vec (for [id (range start end)]
                   {:item/id id}))}))
