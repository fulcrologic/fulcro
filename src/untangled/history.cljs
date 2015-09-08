(ns untangled.history)

(defrecord PointInTime [app-state undoable can-collapse? reason])
(defrecord History [entries limit])

(defn new-point-in-time [state undoable can-collapse?]
  (->PointInTime state (boolean undoable) can-collapse? nil)
  )

(defn empty-history [limit] (->History (list) limit))
(defn set-reason [point-in-time reason] (assoc point-in-time :reason reason))

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
