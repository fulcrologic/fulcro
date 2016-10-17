(ns untangled.client.cards
  #?(:cljs (:require
             [untangled.client.core :as uc]
             [devcards.core :as dc])))

(defmacro untangled-app [root-ui & args]
  (let [varname (gensym)]
    `(dc/dom-node
       (fn [state-atom# node#]
         (defonce ~varname (atom (uc/new-untangled-client :initial-state state-atom# ~@args)))
         (reset! ~varname (uc/mount @~varname ~root-ui node#))
         ; ensures shows app state immediately if you're using inspect data and InitialAppState:
         (js/setTimeout (fn [] (swap! state-atom# assoc :ui/react-key 0)) 100)
         node#))))
