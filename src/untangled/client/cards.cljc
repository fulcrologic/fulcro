(ns untangled.client.cards
  #?(:cljs (:require
             untangled.client.core
             untangled.dom
             [devcards.core :as dc])))

(defmacro untangled-app [root-ui & args]
  `(dc/dom-node
     (fn [state-atom# node#]
       (untangled.client.core/mount (untangled.client.core/new-untangled-client :initial-state state-atom# ~@args) ~root-ui node#)
       ; ensures shows app state immediately if you're using inspect data and InitialAppState:
       (js/setTimeout (fn [] (swap! state-atom# assoc :ui/react-key (untangled.dom/unique-key))) 1000))))
