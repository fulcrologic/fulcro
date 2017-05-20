(ns recipes.mutation-return-value-server
  (:require [cards.server-api :as api]))

(defmethod api/server-mutate 'rv/crank-it-up [e k {:keys [value]}]
  (Thread/sleep 200) ; simulate a bit of delay
  {:action (fn [] {:value (inc value)})})

