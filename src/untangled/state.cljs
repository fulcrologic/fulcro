(ns untangled.state
  (:require [untangled.events :as evt]))

(defn root-scope 
  "Create a root context for a top-level component render. The argument must be an atom holding a map."
  [app-state-atom]
  {
   :app-state-atom  app-state-atom
   :scope           []
   :event-listeners []
   }
  )

(defn find-first [pred coll] (first (filter pred coll)))

(defn data-path
  "Used by internal context tracking to correct the internal path to account for the inclusion of vectors as data structures 
  pointing to items in vectors."
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
  "Extract the data for the component indicated by the given context."
  [context]
  (let [state-atom (:app-state-atom context)
        path (data-path context)
        ]
    (get-in @state-atom path)))

(defn update-in-context 
  "Update the application state by applying the given operation to the state of the component implied by
  the given context. Think of this as a 'targeted' `swap!` where you don't have to know where the data is 
  stored."
  [context operation]
  (let [state-atom (:app-state-atom context)
        path (data-path context)
        ]
    (swap! state-atom #(update-in % path operation))))

(defn new-scope 
  "Create a new context (scope) which represents a child's context. Also installs the given handler map as a list of
  event handlers the child can trigger on the parent."
  [context id handler-map]
  (cond-> (assoc context :scope (conj (:scope context) id))
          handler-map (assoc :event-listeners (concat (:event-listeners context) handler-map))
          )
  )

(defn path-operator
  "Create a function that, when called, updates the app state localized to the given context.
  
  Parameters:
  `context`: The application state atom
  `operation`: A 1-arg function that takes the data type at the given path, and returns an updated value
  
  Think of this as a constructor for targeted `swap!` functions.
  
  The triggers parameter indicates that the resulting function will trigger those abstract events on the parent.
  "
  [context operation triggers] (fn []
                                 (if triggers (evt/trigger context triggers))
                                 (update-in-context context operation))
  )

(defn op-builder 
  "Build a localized operation on the data in the given context.  This function returns a function of the form:
  
       (fn [operation & events-to-trigger] ...)
  
  The returned function can be used to build yet another function that can evolve the application state by
  applying the provided operation to the local component.
  
  The `operation` should be a referentially transparent function that can take your component's state and
  return a new version of that state (e.g. via `assoc`).
  
  The optional extra parameters indicate abstract (user-defined) events that you wish to trigger on the parent. 
  The common pattern in your components is:
  
       (defscomponent Calendar
          [data context]
          (let [op (state/op-builder context) ; make an op-builder for the calendar's context
                goto-today (op (partial set-date (js/Date.)))] ; make a function that can set the calendar to today
                (d/button {:onClick goto-today } Today)
                ))
  "
  [context] 
  (fn [operation & rest] (path-operator context operation rest)))
