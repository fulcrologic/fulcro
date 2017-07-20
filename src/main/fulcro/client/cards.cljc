(ns fulcro.client.cards
  #?(:cljs (:require-macros fulcro.client.cards))           ; this enables implicit macro loading
  #?(:cljs (:require                                        ; ensure the following things are loaded in the CLJS env
             #?(:clj devcards.core)
             #?(:clj devcards.util.utils)
             fulcro.client.core
             fulcro.client.util)))

; At the time of this writing, devcards is not server-rendering compatible, and dom-node is a cljs-only thing.
(defmacro fulcro-app
  "Embed an fulcro client application in a devcard. The `args` can be any args you'd
  normally pass to `new-fulcro-client` except for `:initial-state` (which is taken from
  InitialAppState or the card's data). The card's data (which must be a normalized db) will override InitialAppState if it is *not* empty."
  [root-ui & args]
  (let [app-sym (symbol (str (name root-ui) "-app"))]
    `(devcards.core/dom-node
       (fn [state-atom# node#]
         (defonce ~app-sym (atom (fulcro.client.core/new-fulcro-client :initial-state state-atom# ~@args)))
         (if (-> ~app-sym deref :mounted? not)
           (let [use-untangled-initial-state?# (-> state-atom# deref empty?)]
             (if (and use-untangled-initial-state?#
                   (fulcro.client.core/iinitial-app-state? ~root-ui))
               (reset! state-atom# (om.next/tree->db ~root-ui (fulcro.client.core/get-initial-state ~root-ui nil) true))
               state-atom#)
             (reset! ~app-sym (fulcro.client.core/mount (deref ~app-sym) ~root-ui node#)))
           (fulcro.client.core/mount (deref ~app-sym) ~root-ui node#))
         ; ensures shows app state immediately if you're using inspect data true...otherwise you don't see it until the first interaction.
         (js/setTimeout (fn [] (swap! state-atom# assoc :ui/react-key (fulcro.client.util/unique-key))) 100)))))

(defmacro fulcro-application
  "Embed an fulcro client application in a devcard. The `args` can be any args you'd
  normally pass to `new-fulcro-client` except for `:initial-state` (which is taken from
  InitialAppState or the card's data). The card's data (which must be a normalized db) will override InitialAppState if it is *not* empty."
  [app-sym root-ui & args]
  `(devcards.core/dom-node
     (fn [state-atom# node#]
       (defonce ~app-sym (atom (fulcro.client.core/new-fulcro-client :initial-state state-atom# ~@args)))
       (if (-> ~app-sym deref :mounted? not)
         (let [use-untangled-initial-state?# (-> state-atom# deref empty?)]
           (if (and use-untangled-initial-state?#
                 (fulcro.client.core/iinitial-app-state? ~root-ui))
             (reset! state-atom# (om.next/tree->db ~root-ui (fulcro.client.core/get-initial-state ~root-ui nil) true))
             state-atom#)
           (reset! ~app-sym (fulcro.client.core/mount (deref ~app-sym) ~root-ui node#)))
         (fulcro.client.core/mount (deref ~app-sym) ~root-ui node#))
       ; ensures shows app state immediately if you're using inspect data true...otherwise you don't see it until the first interaction.
       (js/setTimeout (fn [] (swap! state-atom# assoc :ui/react-key (fulcro.client.util/unique-key))) 100))))

#?(:clj
   (defmacro defcard-fulcro [& exprs]
     (when (devcards.util.utils/devcards-active?)
       (let [[vname docu root-component initial-data options] (devcards.core/parse-card-args exprs 'fulcro-root-card)
             app-sym        (symbol (str (name vname) "-fulcro-app"))
             extra-keys     (remove #{:frame :heading :padding :inspect-data :watch-atom :history :static-state} (keys options))
             fulcro-kvpairs (seq (select-keys options extra-keys))
             fulcro-options (reduce concat fulcro-kvpairs)]
         (.println System/out (pr-str fulcro-options))
         (devcards.core/card vname docu `(fulcro-application ~app-sym ~root-component ~@fulcro-options) initial-data options)))))

#_(defmacro defcard-fulcro
    "Embed an fulcro client application in a devcard. The `args` can be any args you'd
    normally pass to `new-fulcro-client` except for `:initial-state` (which is taken from
    InitialAppState or the card's data). The card's data (which must be a normalized db) will override InitialAppState if it is *not* empty."
    [app-sym root-ui & args]
    `(devcards.core/dom-node
       (fn [state-atom# node#]
         (defonce ~app-sym (atom (fulcro.client.core/new-fulcro-client :initial-state state-atom# ~@args)))
         (if (-> ~app-sym deref :mounted? not)
           (let [use-untangled-initial-state?# (-> state-atom# deref empty?)]
             (if (and use-untangled-initial-state?#
                   (fulcro.client.core/iinitial-app-state? ~root-ui))
               (reset! state-atom# (om.next/tree->db ~root-ui (fulcro.client.core/get-initial-state ~root-ui nil) true))
               state-atom#)
             (reset! ~app-sym (fulcro.client.core/mount (deref ~app-sym) ~root-ui node#)))
           (fulcro.client.core/mount (deref ~app-sym) ~root-ui node#))
         ; ensures shows app state immediately if you're using inspect data true...otherwise you don't see it until the first interaction.
         (js/setTimeout (fn [] (swap! state-atom# assoc :ui/react-key (fulcro.client.util/unique-key))) 100))))
