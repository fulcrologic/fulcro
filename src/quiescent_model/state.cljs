(ns quiescent-model.state
  (:require [quiescent-model.events :as evt]))

(defn root-scope [app-state-atom]
  {
   :app-state-atom  app-state-atom
   :scope           []
   :event-listeners []
   }
  )

(defn find-first [pred coll] (first (filter pred coll)) )

(defn data-path
  "Corrects actual internal path to account for the inclusion of vectors as data structures."
  [context]
  (let [state @(:app-state-atom context)
        path-seq (:scope context)
        ]
    (reduce (fn [real-path path-ele]
              (if (sequential? path-ele)
                (do
                  (if (not= 3 (count path-ele)) (.log js/console "ERROR: VECTOR BASED DATA ACCESS MUST HAVE A 3-TUPLE KEY"))
                  (let [vector-key (first path-ele)
                        state-vector (get-in state (conj real-path vector-key))
                        lookup-function (second path-ele)
                        target-value (nth path-ele 2)
                        index (->> (map-indexed vector state-vector) (find-first #(= target-value (lookup-function (second %)))) (first))
                        ]
                    (if index
                      (conj real-path vector-key index)
                      (do
                        (cljs.pprint/pprint "ERROR: NO ITEM FOUND AT DATA PATH ")
                        (cljs.pprint/pprint path-seq)
                        real-path
                        )
                      )
                    ))
                (conj real-path path-ele)
                )
              )
            [] path-seq)))

(defn context-data [context]
  (let [state-atom (:app-state-atom context)
        path (data-path context)
        ]
    (get-in @state-atom path)))

(defn update-in-context [context op]
  (let [state-atom (:app-state-atom context)
        path (data-path context)
        ]
    (swap! state-atom #(update-in % path op))))

(defn new-scope [scope id handler-map]
  (cond-> (assoc scope :scope (conj (:scope scope) id))
          handler-map (assoc :event-listeners (concat (:event-listeners scope) handler-map))
          )
  )

(defn path-operator
  "Create a function that, when called, updates the app state at the given path using the given operation.
  This function is used to create event handlers.

  Parameters:
  `context`: The application state atom
  `operation`: A 1-arg function that takes the data type at the given path, and returns an updated value
  "
  [context operation triggers] (fn []
                                 (if triggers (evt/trigger context triggers))
                                 (update-in-context context operation))
  )

