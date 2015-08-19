(ns cljs.user
  (:require todo.core
            cljs.pprint
            [differ.core :as differ]
            )
  )

(defonce current-focus (atom []))

(defn check-focus []
  (if (nil? (get-in @todo.core/app-state @current-focus))
    (cljs.pprint/pprint "WARNING: No data exists at the current focus!")
    )
  )

(defn focus!
  "Set the focus to a specific app-state path"
  [target]
  (reset! current-focus target)
  (check-focus)
  )

(defn focus-in
  "Narrow the app-state focus (see fpp for printing current focus)"
  [sub-component]
  (swap! current-focus
         #(if (sequential? sub-component)
           (vec (concat % sub-component))
           (conj % sub-component)
           ))
  (check-focus)
  @current-focus
  )

(defn focus-out
  "Widen the current focus"
  ([nlevels] (if (and (> nlevels 0) (not-empty @current-focus))
               (do
                 (swap! current-focus #(pop %))
                 (recur (- nlevels 1))
                 )
               @current-focus
               ))
  ([] (focus-out 1))
  )

(defn fpp
  "Pretty-print the currently-focused app-state item"
  [] (cljs.pprint/pprint (get-in @todo.core/app-state @current-focus)))

(defn diff
  "Shows the difference between the current app-state (at the focused component) and
  nsteps of history ago."
  ([nsteps] (diff nsteps 0))
  ([nsteps nsteps-end]
   (if (< (count @todo.core/undo-history) nsteps)
     (cljs.pprint/pprint "Not enough history")
     (let [old-state (get-in (nth @todo.core/undo-history (dec nsteps)) @current-focus)
           end-state (if (= 0 nsteps-end)
                       (get-in @todo.core/app-state @current-focus)
                       (get-in (nth @todo.core/undo-history (dec nsteps-end)) @current-focus)
                       )
           ]
       (if (not= old-state end-state)
         (cljs.pprint/pprint (differ/diff old-state end-state)))
       )
     ))
  )

(defn vdiff
  ([nsteps] (vdiff nsteps 0))
  ([nsteps nsteps-end]
   (if (< (count @todo.core/undo-history) nsteps)
     (cljs.pprint/pprint "Not enough history")
     (let [old-state (get-in (nth @todo.core/undo-history (dec nsteps)) @current-focus)
           end-state (if (= 0 nsteps-end)
                       (get-in @todo.core/app-state @current-focus)
                       (get-in (nth @todo.core/undo-history (dec nsteps-end)) @current-focus)
                       )
           ]
       (if (not= old-state end-state)
         (do
           (cljs.pprint/pprint (str "STATE " nsteps " STEPS AGO"))
           (cljs.pprint/pprint old-state)
           (cljs.pprint/pprint "STATE NOW")
           (cljs.pprint/pprint end-state)
           ))
       )
     )))

(defn evolution
  "Show the evolution of the app-state between a and b steps ago."
  [a b]
  (assert (> a b) "'a' must be more steps than 'b'")
  (doseq [n (range a b -1)]
    (cljs.pprint/pprint (str n " steps ago"))
    (diff n (dec n))
    )
  )

(defn auto-trigger!
  "Turn on/off auto-printing of changes if the focused state changes"
  [turn-on]
  (if turn-on
    (add-watch todo.core/app-state ::auto-trigger (fn [_ _ old-state new-state] (diff 1)))
    (remove-watch todo.core/app-state ::auto-trigger)
    ))

