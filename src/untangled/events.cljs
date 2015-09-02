(ns untangled.events
  (:require
    cljs.pprint
    ))

;; Support for inter-component communication...

;; TODO: This isn't quite right, as the handler maps WILL have dupes, which will cause things to fail
(defn trigger [context events]
  (doseq [evt events
          listener-map (:event-listeners context)]
    (if-let [listener (get listener-map evt)] (listener))
    )
  )
