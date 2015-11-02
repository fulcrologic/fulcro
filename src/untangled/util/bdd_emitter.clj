(ns untangled.util.bdd-emitter
  (:require [midje.emission.plugins.util :as util]
            [midje.data.fact :as fact]
            [midje.emission.plugins.default :as default]
            [midje.emission.state :as state]
            [midje.emission.colorize :as color]
            )
  )

(def nesting-level (atom 0))

(defn current-indent []
  (str (clojure.string/join "" (repeat @nesting-level "  "))
       (if (> @nesting-level 0) "- " ""))
  )

(defn top-level-start [fact]
  (reset! nesting-level 0)
  (util/emit-one-line ""))

(defn fact-started [fact]
  (let [md (meta fact)
        name (:midje/name md)
        ]
    (util/emit-one-line (color/note (current-indent) name (if (= 0 @nesting-level) " ===========================" "")))
    (swap! nesting-level inc))
  )

(defn fact-stopped [fact]
  (swap! nesting-level dec)
  )

(state/install-emission-map
  (assoc default/emission-map
    :starting-to-check-top-level-fact top-level-start
    :starting-to-check-fact fact-started
    :finishing-fact fact-stopped
    )
  )
