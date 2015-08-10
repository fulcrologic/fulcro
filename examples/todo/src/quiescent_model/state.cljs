(ns quiescent-model.state
  (:require [quiescent-model.events :as evt]))

(defn root-scope [app-state-atom]
  {
   :app-state-atom    app-state-atom
   :scope             '()
   :current-component nil
   :event-listeners   '()
   }
  )

(defn data-path [context]
  (let [scope (:scope context)
        path-seq (reverse scope)
        ]
    path-seq
    )
  )

(defn context-data [context]
  (let [state-atom (:app-state-atom context)
        path (data-path context)
        ]
    (cljs.pprint/pprint "CONTEXT DATA")
    (cljs.pprint/pprint context)
    (cljs.pprint/pprint path)
    (get-in @state-atom path)))

(defn update-in-context [context op]
  (let [state-atom (:app-state-atom context)
        path (data-path context)
        ]
    (swap! state-atom #(update-in % path op))))

(defn new-scope [scope id handler-map]
  (cond-> (assoc scope
            :scope (if (sequential? id) (concat (reverse id) (:scope scope)) (conj (:scope scope) id))
            :current-component nil)
          handler-map (assoc :event-listeners (concat (:event-listeners scope) handler-map))
          )
  )

(defn in-context "create a context for the given component in a scope"
  [scope id handler-map]
  (cond-> (assoc scope :current-component id)
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

