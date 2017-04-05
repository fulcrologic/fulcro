(ns untangled.client.cards
  #?(:cljs (:require-macros untangled.client.cards)) ; this enables implicit macro loading
  #?(:cljs (:require ; ensure the following things are loaded in the CLJS env
             untangled.client.core
             untangled.dom)))

; At the time of this writing, devcards is not server-rendering compatible, and dom-node is a cljs-only thing.
(defmacro untangled-app
  "Embed an untangled client application in a devcard. The `args` can be any args you'd
  normally pass to `new-untangled-client` except for `:initial-state` (which is taken from
  InitialAppState or the card's data in that preferred order)"
  [root-ui & args]
  `(devcards.core/dom-node
     (fn [state-atom# node#]
       (untangled.client.core/mount (untangled.client.core/new-untangled-client :initial-state state-atom# ~@args) ~root-ui node#)
       ; ensures shows app state immediately if you're using inspect data and InitialAppState:
       (js/setTimeout (fn [] (swap! state-atom# assoc :ui/react-key (untangled.dom/unique-key))) 1000))))
