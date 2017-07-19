(ns fulcro.client.cards
  #?(:cljs (:require-macros fulcro.client.cards))           ; this enables implicit macro loading
  #?(:cljs (:require                                        ; ensure the following things are loaded in the CLJS env
             fulcro.client.core
             fulcro.client.util)))

; At the time of this writing, devcards is not server-rendering compatible, and dom-node is a cljs-only thing.
(defmacro fulcro-app
  "Embed an fulcro client application in a devcard. The `args` can be any args you'd
  normally pass to `new-fulcro-client` except for `:initial-state` (which is taken from
  InitialAppState or the card's data). The card's data (which must be a normalized db) will override InitialAppState if it is *not* empty."
  [root-ui & args]
  `(devcards.core/dom-node
     (fn [state-atom# node#]
       (let [use-untangled-initial-state?# (-> state-atom# deref empty?)]
         (if (and use-untangled-initial-state?#
               (fulcro.client.core/iinitial-app-state? ~root-ui))
           (reset! state-atom# (om.next/tree->db ~root-ui (fulcro.client.core/get-initial-state ~root-ui nil) true))
           state-atom#)
         (fulcro.client.core/mount (fulcro.client.core/new-fulcro-client :initial-state state-atom# ~@args) ~root-ui node#))
       ; ensures shows app state immediately if you're using inspect data and InitialAppState:
       (js/setTimeout (fn [] (swap! state-atom# assoc :ui/react-key (fulcro.client.util/unique-key))) 1000))))