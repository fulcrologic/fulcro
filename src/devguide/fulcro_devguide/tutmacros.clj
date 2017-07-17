(ns fulcro-devguide.tutmacros
  (:require [devcards.core :as dc]))

(defmacro fulcro-app [root-ui & args]
  (let [varname (gensym)]
    `(dc/dom-node
       (fn [state-atom# node#]
         (defonce ~varname (atom (fc/new-fulcro-client :initial-state state-atom# ~@args)))
         (reset! ~varname (fc/mount @~varname ~root-ui node#))
         ; ensures shows app state immediately if you're using inspect data and InitialAppState:
         (js/setTimeout (fn [] (swap! state-atom# assoc :ui/react-key 0)) 100)))))
