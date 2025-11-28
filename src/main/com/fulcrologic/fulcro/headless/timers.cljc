(ns com.fulcrologic.fulcro.headless.timers
  "Timer control for deterministic timeout testing.

   This namespace provides utilities for mocking and controlling timers
   in Fulcro tests. This is particularly useful for testing:

   - UI State Machine timeouts
   - Dynamic routing delay/error timers
   - Debounced operations
   - Any code that uses `com.fulcrologic.fulcro.algorithms.scheduling/defer`

   Example:
   ```clojure
   (with-mock-timers
     (uism/begin! app LoginMachine ::login {:actor/form LoginForm} {})
     (uism/trigger! app ::login :submit {:username \"test\"})

     ;; Advance time to trigger timeout
     (advance-time! 5001)

     ;; Timer callbacks have now fired
     (is (= :error (uism/get-active-state app ::login))))
   ```"
  (:require
    [com.fulcrologic.fulcro.algorithms.scheduling :as sched]))

(def ^:dynamic *mock-time*
  "When bound, holds the current mock time in milliseconds."
  nil)

(def ^:dynamic *pending-timers*
  "When bound, holds an atom containing pending timer entries.
   Each entry: {:id unique-id :fire-at ms :callback fn :active volatile}"
  nil)

(def ^:dynamic *timer-id-counter*
  "Counter for generating unique timer IDs."
  nil)

(defn- next-timer-id []
  (swap! *timer-id-counter* inc))

(defn mock-time
  "Get the current mock time, or real time if not mocking."
  []
  (if *mock-time*
    @*mock-time*
    #?(:clj  (System/currentTimeMillis)
       :cljs (.now js/Date))))

(defn pending-timers
  "Get all pending timers when in mock mode.
   Returns nil if not in mock mode."
  []
  (when *pending-timers*
    @*pending-timers*))

(defn pending-timer-count
  "Get the count of pending timers."
  []
  (count (pending-timers)))

(defn- fire-timer!
  "Fire a single timer entry, removing it from pending."
  [{:keys [id callback active]}]
  (when @active
    (swap! *pending-timers* (fn [timers] (filterv #(not= id (:id %)) timers)))
    (try
      (callback)
      (catch Exception e
        (throw (ex-info "Timer callback threw exception"
                 {:timer-id id}
                 e))))))

(defn- timers-ready-to-fire
  "Get all timers that should fire at or before the current mock time."
  []
  (let [current @*mock-time*]
    (filter #(<= (:fire-at %) current) @*pending-timers*)))

(defn- fire-ready-timers!
  "Fire all timers that are ready (fire-at <= current time)."
  []
  (loop []
    (when-let [ready (seq (timers-ready-to-fire))]
      (doseq [timer ready]
        (fire-timer! timer))
      ;; Timers might schedule more timers, check again
      (recur))))

(defn advance-time!
  "Advance the mock time by the given number of milliseconds.
   Fires any timers whose time has come.

   Returns the new mock time."
  [ms]
  (when-not *mock-time*
    (throw (IllegalStateException. "advance-time! called outside of with-mock-timers")))
  (swap! *mock-time* + ms)
  (fire-ready-timers!)
  @*mock-time*)

(defn set-time!
  "Set the mock time to an absolute value.
   Fires any timers whose time has come.

   Returns the new mock time."
  [ms]
  (when-not *mock-time*
    (throw (IllegalStateException. "set-time! called outside of with-mock-timers")))
  (reset! *mock-time* ms)
  (fire-ready-timers!)
  @*mock-time*)

(defn fire-all-timers!
  "Fire all pending timers immediately, regardless of their scheduled time.
   Timers are fired in order of their scheduled time.

   Returns the number of timers fired."
  []
  (when-not *pending-timers*
    (throw (IllegalStateException. "fire-all-timers! called outside of with-mock-timers")))
  (let [timers (sort-by :fire-at @*pending-timers*)
        count  (count timers)]
    (doseq [timer timers]
      (fire-timer! timer))
    count))

(defn clear-timers!
  "Cancel and remove all pending timers without firing them.

   Returns the number of timers cleared."
  []
  (when-not *pending-timers*
    (throw (IllegalStateException. "clear-timers! called outside of with-mock-timers")))
  (let [timers @*pending-timers*
        count  (count timers)]
    (doseq [{:keys [active]} timers]
      (vreset! active false))
    (reset! *pending-timers* [])
    count))

(defn next-timer-at
  "Get the fire-at time of the next pending timer, or nil if none."
  []
  (when-let [timers (seq (pending-timers))]
    (:fire-at (first (sort-by :fire-at timers)))))

(defn advance-to-next-timer!
  "Advance time to the next pending timer and fire it.
   Returns the new mock time, or nil if no timers pending."
  []
  (when-let [next-time (next-timer-at)]
    (set-time! next-time)))

(defn ^:no-doc mock-defer
  "Mock implementation of sched/defer that queues timers for manual control.
   Internal use - not part of public API."
  [f tm]
  (let [active   (volatile! true)
        timer-id (next-timer-id)
        fire-at  (+ @*mock-time* tm)
        cancel   (fn []
                   (vreset! active false)
                   (swap! *pending-timers*
                     (fn [timers] (filterv #(not= timer-id (:id %)) timers))))]
    (swap! *pending-timers* conj {:id       timer-id
                                  :fire-at  fire-at
                                  :callback f
                                  :active   active
                                  :delay-ms tm})
    cancel))

(defmacro with-mock-timers
  "Execute body with mocked timers.

   Within this block:
   - All calls to `sched/defer` are captured instead of actually scheduling
   - Use `advance-time!` to move time forward and trigger timers
   - Use `fire-all-timers!` to fire all pending timers immediately
   - Use `pending-timers` to inspect what's scheduled

   Example:
   ```clojure
   (with-mock-timers
     ;; Start something that sets a timeout
     (uism/trigger! app ::login :submit {})

     ;; Check that a timer was scheduled
     (is (= 1 (pending-timer-count)))

     ;; Advance time to fire the timer
     (advance-time! 5000)

     ;; Timer callback has now executed
     (is (= :timeout-state (uism/get-active-state app ::login))))
   ```"
  [& body]
  `(binding [*mock-time*        (atom 0)
             *pending-timers*   (atom [])
             *timer-id-counter* (atom 0)]
     (with-redefs [sched/defer mock-defer]
       ~@body)))

(defmacro with-mock-timers-from
  "Like `with-mock-timers` but starts at a specific time.

   Example:
   ```clojure
   (with-mock-timers-from 1000
     (advance-time! 500)
     (is (= 1500 (mock-time))))
   ```"
  [start-time & body]
  `(binding [*mock-time*        (atom ~start-time)
             *pending-timers*   (atom [])
             *timer-id-counter* (atom 0)]
     (with-redefs [sched/defer mock-defer]
       ~@body)))

(defn timer-info
  "Get information about pending timers.
   Returns a sequence of maps with :delay-ms and :fire-at keys."
  []
  (map #(select-keys % [:delay-ms :fire-at]) (pending-timers)))

(defn has-timer-with-delay?
  "Check if there's a pending timer with approximately the given delay.
   Useful for verifying the correct timeout was scheduled."
  [delay-ms & {:keys [tolerance] :or {tolerance 10}}]
  (some #(<= (abs (- delay-ms (:delay-ms %))) tolerance)
    (pending-timers)))
