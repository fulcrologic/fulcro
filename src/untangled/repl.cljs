(ns untangled.repl
  (:require cljs.pprint
            [differ.core :as differ]
            ))

(defonce current-focus (atom []))
(defonce *ui-state* (atom nil))
(defonce *undo-history* (atom nil))

(defn follow-app-state!
  "Define the app state atom that you wish to debug/watch via the Untangled REPL tools. The argument should be
  the atom that holds your application state. If your app supports undo, then you can enable the history analysis tools
  by also passing the undo-atom."
  ([state-atom] (reset! *ui-state* state-atom))
  ([state-atom undo-atom]
   (reset! *ui-state* state-atom)
   (reset! *undo-history* undo-atom)))

(defn app-state
  "Returns the atom that is currently the focus of Untangled's REPL tools."
  [] @*ui-state*)

(defn undo-history
  "Returns the atom that is currently tracking the undo history for the application. Used to enable state diff support."
  [] @*undo-history*)

(defn check-focus
  "Verify that the current focus makes sense in the app state."
  []
  (if (nil? (get-in (app-state) @current-focus))
    (cljs.pprint/pprint "WARNING: No data exists at the current focus!")
    )
  )

(defn focus!
  "Set the focus to a specific app-state path (using a notation compatible with `get-in`)."
  [target]
  (reset! current-focus target)
  (check-focus)
  @current-focus
  )

(defn focus-in
  "Narrow the app-state focus. May be given a single key or a sequence. Will descend the app state from the current
   focus. In that sense focus-in is very much like a cd with a relative path. All other commands work against
   this focus. See fpp for printing the data at the current focus, vdiff for comparing two points in app state history, 
   etc. NOTE: History is not available if an undo atom is not in use."
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
  "Widen the current focus. With no arguments, this is similar to `cd ..`: move up to the parent of the current focus.
  Can also be given a number of levels to move up."
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
  "Pretty-print the currently-focused app-state item."
  []
  (cljs.pprint/pprint (get-in @(app-state) @current-focus))
  ""
  )

(defn diff
  "Shows the difference between the current app-state (at the focused component) and
  nsteps of history ago. This is in a minimal form (provided by the differ library). It is a terse list of changes,
  but can be a highly effective way to watch application state changes. See `auto-trigger!`"
  ([nsteps] (diff nsteps 0))
  ([nsteps nsteps-end]
   (if (nil? (undo-history))
     (println "No undo history available.")
     (if (< (count @(undo-history)) nsteps)
       (cljs.pprint/pprint "Not enough history")
       (let [old-state (get-in (nth @(undo-history) (dec nsteps)) @current-focus)
             end-state (if (= 0 nsteps-end)
                         (get-in @(app-state) @current-focus)
                         (get-in (nth @(undo-history) (dec nsteps-end)) @current-focus)
                         )
             ]
         (if (not= old-state end-state)
           (cljs.pprint/pprint (differ/diff old-state end-state)))
         )
       ))
   ""))

(defn vdiff
  "Show a verbose diff of states in the history (you must be using undo support). Specifically shows the 
   state nsteps ago vs the current state, or two old versions (both specified as the number of steps back in history)."
  ([nsteps] (vdiff nsteps 0))
  ([nsteps nsteps-end]
   (if (< (count @(undo-history)) nsteps)
     (cljs.pprint/pprint "Not enough history")
     (let [old-state (get-in (nth @(undo-history) (dec nsteps)) @current-focus)
           end-state (if (= 0 nsteps-end)
                       (get-in @(app-state) @current-focus)
                       (get-in (nth @(undo-history) (dec nsteps-end)) @current-focus)
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
     )
    ""
    ))

(defn evolution
  "Show the evolution of the app-state between a and b steps ago, in terse diff format. Similar to the output of 
  live `auto-trigger!`, but on history."
  [a b]
  (assert (> a b) "'a' must be more steps than 'b'")
  (doseq [n (range a b -1)]
    (cljs.pprint/pprint (str n " steps ago"))
    (diff n (dec n))
    )
  ""
  )

(defn auto-trigger!
  "Turn on/off auto-printing of changes when the focused state changes. When on, will show the app state change 
  that occurs, as it occurs (in the JavaScript Console of the browser). Useful for watching your application state
  evolve in response to interactions with the world. The output is that of `diff`."
  [turn-on]
  (if turn-on
    (add-watch (app-state) ::auto-trigger (fn [_ _ old-state new-state] (diff 1)))
    (remove-watch (app-state) ::auto-trigger)
    )
  turn-on
  )
