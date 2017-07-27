(ns recipes.mutation-return-value-server
  (:require
    [fulcro.server :refer [server-mutate]]))

(defmethod server-mutate 'rv/crank-it-up [e k {:keys [value]}]
  (Thread/sleep 200)                                        ; simulate a bit of delay
  {:action (fn [] {:value (inc value)})})

