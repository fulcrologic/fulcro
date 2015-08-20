(ns quiescent-model.state
  (:require [quiescent-model.events :as evt]))

(defn root-scope 
  "Creates a root scope for a top-level component"
  [app-state-atom]
  {
   :app-state-atom  app-state-atom
   :scope           []
   :event-listeners []
   }
  )

(defn find-first 
  "Helper function returning (first (filter pred coll))"
  [pred coll] 
  (first (filter pred coll)) )

(defn data-path
  "Converts a conceptual data path to an associative path usable by get-in.
  The abstract internal path may include vectors as data structures which
  are instructions to look up items in a list by a key."
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
                        (.log js/console "ERROR: NO ITEM FOUND AT DATA PATH ")
                        (cljs.pprint/pprint path-seq)
                        real-path
                        )
                      )
                    ))
                (conj real-path path-ele)
                )
              )
            [] path-seq)))

(defn context-data 
  "Get the data from the application state that is represented by an
  abstract context"
  [context]
  (let [state-atom (:app-state-atom context)
        path (data-path context)
        ]
    (get-in @state-atom path)))

(defn update-in-context 
  "Update the data represented by the context in the real application 
  state using the supplied operation (this operation is like swap!, but
  you don't have to know where the data is actually stored)."
  [context op]
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

(defn context-op-builder 
  "Creates an application-state operation builder based on the given 
  context. In other words: returns a function that takes
  a function that can evolve the context data. This function, when called,
  returns a function that can do that operation in the larger context
  of the application state."
  [context] 
  (fn [op & triggers] (path-operator context op triggers)))
