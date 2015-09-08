(ns untangled.history)

(defrecord PointInTime [app-state undoable can-collapse? reason])
(defrecord History [entries limit])

(defn new-point-in-time
  "Create a new point in time for history with the given state. New points in time, by default, are undoable and 
  non-collapsable."
  ([state] (new-point-in-time state true false))
  ([state undoable can-collapse?]
   (->PointInTime state (boolean undoable) can-collapse? nil))
  )

(defn empty-history
  "Create a new application state history with the specified length limit."
  [limit]
  (->History (list) limit))

(defn set-reason
  "Associate a reason with a specific point in time in history. Pass in the existing history entry; returns
  a new entry with the reason recorded."
  [point-in-time reason] (assoc point-in-time :reason reason))

(defn collapse-history
  "Take a history and find adjacent points in time that are able to be collapsed. Such adjacent points in time will be
  collapsed down to the most recent. Finally, remove history that would cause the overall history length to be beyond 
  the overall history limit."
  [h]
  (let [collapse-entry (fn [acc entry]
                         (cond
                           (and (:can-collapse? entry)
                                (:can-collapse? (first acc))) (cons entry (rest acc))
                           :else (cons entry acc)
                           )
                         )]
    (->> (:entries h)
         (reverse)
         (reduce collapse-entry (list))
         (take (:limit h))
         (assoc h :entries))))

(defn record [history point-in-time]
  (->> (:entries history)
       (cons point-in-time)
       (assoc history :entries)
       (collapse-history)
       )
  )
