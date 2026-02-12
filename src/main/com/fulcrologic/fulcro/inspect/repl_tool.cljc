(ns com.fulcrologic.fulcro.inspect.repl-tool
  "A flight-recorder tool for AI agents and developers working at the REPL.

   Captures Fulcro events (transactions, network activity, state changes, UISM transitions,
   statechart transitions) into a queryable log. Optimized for the workflow:
   clear -> do something -> inspect what happened.

   Usage:
   ```clojure
   (require '[com.fulcrologic.fulcro.inspect.repl-tool :as rt])

   (rt/install! app)
   (rt/clear!)
   ;; ... do something in the app ...
   (rt/digest)    ;; structured summary of what happened
   (rt/tx-log)    ;; just transactions
   ```

   Events are stored in the app's runtime-atom and bounded to a configurable max size.
   A default-app atom allows calling functions without passing `app` every time."
  (:require
    [com.fulcrologic.fulcro.inspect.tools :as fit]))

;; =============================================================================
;; Event type symbols (matching inspect_client and devtool_api)
;; =============================================================================

(def ^:private optimistic-action-type 'com.fulcrologic.fulcro.inspect.devtool-api/optimistic-action)
(def ^:private send-started-type 'com.fulcrologic.fulcro.inspect.devtool-api/send-started)
(def ^:private send-finished-type 'com.fulcrologic.fulcro.inspect.devtool-api/send-finished)
(def ^:private send-failed-type 'com.fulcrologic.fulcro.inspect.devtool-api/send-failed)
(def ^:private db-changed-type 'com.fulcrologic.fulcro.inspect.devtool-api/db-changed)
(def ^:private statechart-event-type 'com.fulcrologic.fulcro.inspect.devtool-api/statechart-event)

;; UISM mutation symbol used to recognize state machine triggers within optimistic-action events
(def ^:private uism-trigger-sym 'com.fulcrologic.fulcro.ui-state-machines/trigger-state-machine-event)

;; =============================================================================
;; Default app atom
;; =============================================================================

(defonce ^:private default-app (atom nil))

;; =============================================================================
;; Internal helpers
;; =============================================================================

(defn- runtime-atom [app] (:com.fulcrologic.fulcro.application/runtime-atom app))

(defn- epoch-ms []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn- next-seq! [app]
  (let [ra (runtime-atom app)]
    (get (swap! ra update ::seq (fnil inc 0)) ::seq)))

(defn- add-event! [app event]
  (let [ra         (runtime-atom app)
        max-events (or (::max-events @ra) 200)]
    (swap! ra update ::event-log
      (fn [log]
        (let [log (or log [])
              log (conj log event)]
          (if (> (count log) max-events)
            (subvec log (- (count log) max-events))
            log))))))

(defn- uism-tx?
  "Returns the UISM params map if tx is a UISM trigger, else nil.
   tx is a mutation call form like (trigger-state-machine-event {::uism/asm-id ... ::uism/event-id ...})"
  [tx]
  (when (and (sequential? tx) (seq tx))
    (when (= (first tx) uism-trigger-sym)
      (second tx))))

(defn- categorize-and-store! [app event]
  (let [event-type (:type event)
        ts         (epoch-ms)
        seq-num    (next-seq! app)
        base       {:repl-tool/timestamp ts
                    :repl-tool/seq       seq-num}]
    (cond
      (= event-type optimistic-action-type)
      (let [tx          (:fulcro.history/tx event)
            uism-params (uism-tx? tx)]
        (if uism-params
          ;; UISM transition
          (add-event! app
            (merge base
              {:repl-tool/category :uism
               :uism/asm-id        (get uism-params :com.fulcrologic.fulcro.ui-state-machines/asm-id)
               :uism/event-id      (get uism-params :com.fulcrologic.fulcro.ui-state-machines/event-id)
               :uism/event-data    (get uism-params :com.fulcrologic.fulcro.ui-state-machines/event-data)
               :tx                 tx
               :component          (:component event)
               :ident              (:ident-ref event)}))
          ;; Regular transaction
          (add-event! app
            (merge base
              {:repl-tool/category :tx
               :tx                 tx
               :component          (:component event)
               :ident              (:ident-ref event)
               :remotes            (:fulcro.history/network-sends event)
               :db-before-id       (:fulcro.history/db-before-id event)
               :db-after-id        (:fulcro.history/db-after-id event)}))))

      (= event-type send-started-type)
      (add-event! app
        (merge base
          {:repl-tool/category :net
           :net/phase          :started
           :remote             (:fulcro.inspect.ui.network/remote event)
           :request-id         (:fulcro.inspect.ui.network/request-id event)
           :request-edn        (:fulcro.inspect.ui.network/request-edn event)
           :started-at         (:fulcro.inspect.ui.network/request-started-at event)}))

      (= event-type send-finished-type)
      (add-event! app
        (merge base
          {:repl-tool/category :net
           :net/phase          :finished
           :request-id         (:fulcro.inspect.ui.network/request-id event)
           :response-edn       (:fulcro.inspect.ui.network/response-edn event)
           :finished-at        (:fulcro.inspect.ui.network/request-finished-at event)}))

      (= event-type send-failed-type)
      (add-event! app
        (merge base
          {:repl-tool/category :net
           :net/phase          :failed
           :request-id         (:fulcro.inspect.ui.network/request-id event)
           :error              (:fulcro.inspect.ui.network/error event)
           :finished-at        (:fulcro.inspect.ui.network/request-finished-at event)}))

      (= event-type db-changed-type)
      (add-event! app
        (merge base
          {:repl-tool/category :db
           :history/version    (:history/version event)
           :history/based-on   (:history/based-on event)
           :diff               (:history/diff event)}))

      (= event-type statechart-event-type)
      (add-event! app
        (merge base
          {:repl-tool/category       :statechart
           :statechart/session-id    (:com.fulcrologic.statecharts/session-id event)
           :statechart/event         (:event event)
           :statechart/data          (:data event)
           :statechart/configuration (:com.fulcrologic.statecharts/configuration event)})))))

;; =============================================================================
;; Setup
;; =============================================================================

(defn install!
  "Register the repl-tool on the given app and set it as the default app.

   Options:
   - :max-events - Maximum events to retain (default 200)"
  ([app] (install! app {}))
  ([app {:keys [max-events] :or {max-events 200}}]
   (swap! (runtime-atom app) assoc ::max-events max-events)
   (when-not (::installed? @(runtime-atom app))
     (fit/register-tool! app (fn [app event] (categorize-and-store! app event)))
     (swap! (runtime-atom app) assoc ::installed? true))
   (reset! default-app app)
   :ok))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn- resolve-app [app]
  (or app @default-app))

(defn clear!
  "Clear the event log. Returns the current timestamp (useful as a `:since` marker)."
  ([] (clear! nil))
  ([app]
   (let [app (resolve-app app)
         ts  (epoch-ms)]
     (swap! (runtime-atom app) assoc ::event-log [] ::seq 0)
     ts)))

;; =============================================================================
;; Raw Event Access
;; =============================================================================

(defn events
  "Return captured events, optionally filtered.

   Options:
   - :category - One of :tx, :net, :db, :uism, :statechart
   - :since    - Timestamp (e.g. from `clear!`); only events after this time
   - :last     - Return only the last N events"
  ([] (events nil {}))
  ([app-or-opts]
   (if (map? app-or-opts)
     (events nil app-or-opts)
     (events app-or-opts {})))
  ([app {:keys [category since last]}]
   (let [app (resolve-app app)
         log (or (::event-log @(runtime-atom app)) [])
         log (cond->> log
               category (filterv #(= category (:repl-tool/category %)))
               since (filterv #(> (:repl-tool/timestamp %) since)))]
     (if last
       (vec (take-last last log))
       log))))

;; =============================================================================
;; Specialized Views
;; =============================================================================

(defn tx-log
  "Return transaction events, each with :tx, :component, :ident, :remotes."
  ([] (tx-log nil))
  ([app]
   (let [app (resolve-app app)]
     (mapv #(select-keys % [:repl-tool/timestamp :repl-tool/seq :tx :component :ident :remotes])
       (events app {:category :tx})))))

(defn- correlate-network
  "Group network events by :request-id into correlated entries."
  [net-events]
  (let [grouped (group-by :request-id net-events)]
    (mapv (fn [[request-id evts]]
            (let [started     (first (filter #(= :started (:net/phase %)) evts))
                  finished    (first (filter #(= :finished (:net/phase %)) evts))
                  failed      (first (filter #(= :failed (:net/phase %)) evts))
                  status      (cond finished :finished, failed :failed, :else :pending)
                  started-at  (:started-at started)
                  finished-at (or (:finished-at finished) (:finished-at failed))
                  duration-ms (when (and started-at finished-at)
                                #?(:clj  (- (.getTime ^java.util.Date finished-at)
                                           (.getTime ^java.util.Date started-at))
                                   :cljs (- (.getTime finished-at)
                                           (.getTime started-at))))]
              (cond-> {:request-id  request-id
                       :remote      (:remote started)
                       :status      status
                       :request-edn (:request-edn started)}
                finished (assoc :response-edn (:response-edn finished))
                failed (assoc :error (:error failed))
                started-at (assoc :started-at started-at)
                finished-at (assoc :finished-at finished-at)
                duration-ms (assoc :duration-ms duration-ms))))
      grouped)))

(defn network-log
  "Return correlated network events grouped by request-id.
   Each entry contains :request-id, :remote, :status (:pending/:finished/:failed),
   :request-edn, :response-edn, :error, :started-at, :finished-at, :duration-ms."
  ([] (network-log nil))
  ([app]
   (let [app (resolve-app app)]
     (correlate-network (events app {:category :net})))))

(defn uism-log
  "Return UISM transition events with :uism/asm-id, :uism/event-id, :uism/event-data."
  ([] (uism-log nil))
  ([app]
   (let [app (resolve-app app)]
     (mapv #(select-keys % [:repl-tool/timestamp :repl-tool/seq
                            :uism/asm-id :uism/event-id :uism/event-data])
       (events app {:category :uism})))))

(defn statechart-log
  "Return statechart transition events with :statechart/session-id, :statechart/event,
   :statechart/data, :statechart/configuration."
  ([] (statechart-log nil))
  ([app]
   (let [app (resolve-app app)]
     (mapv #(select-keys % [:repl-tool/timestamp :repl-tool/seq
                            :statechart/session-id :statechart/event
                            :statechart/data :statechart/configuration])
       (events app {:category :statechart})))))

;; =============================================================================
;; AI-Optimized Summary
;; =============================================================================

(defn- summarize-state-changes
  "Aggregate db-changed events into a combined diff summary."
  [db-events]
  (let [all-updates  (reduce (fn [acc e]
                               (merge acc (get-in e [:diff :fulcro.inspect.lib.diff/updates])))
                       {} db-events)
        all-removals (reduce (fn [acc e]
                               (into acc (get-in e [:diff :fulcro.inspect.lib.diff/removals])))
                       [] db-events)]
    {:updates  all-updates
     :removals all-removals}))

(defn digest
  "Structured summary of activity since the last clear (or a given timestamp).
   Answers: \"I did X, did it work, and what changed?\"

   Returns a map with:
   - :window - time range and event count
   - :transactions - mutation summaries
   - :network - correlated network activity
   - :uism - UISM transitions
   - :statecharts - statechart transitions
   - :state-changes - aggregated state diff"
  ([] (digest nil {}))
  ([app-or-opts]
   (if (map? app-or-opts)
     (digest nil app-or-opts)
     (digest app-or-opts {})))
  ([app {:keys [since]}]
   (let [app        (resolve-app app)
         all-events (events app (cond-> {}
                                  since (assoc :since since)))
         tx-events  (filterv #(= :tx (:repl-tool/category %)) all-events)
         net-events (filterv #(= :net (:repl-tool/category %)) all-events)
         db-events  (filterv #(= :db (:repl-tool/category %)) all-events)
         uism-evts  (filterv #(= :uism (:repl-tool/category %)) all-events)
         sc-events  (filterv #(= :statechart (:repl-tool/category %)) all-events)
         timestamps (mapv :repl-tool/timestamp all-events)
         earliest   (when (seq timestamps) (apply min timestamps))
         latest     (when (seq timestamps) (apply max timestamps))]
     {:window
      {:since       (or since earliest)
       :duration-ms (if (and earliest latest) (- latest earliest) 0)
       :event-count (count all-events)}

      :transactions
      (mapv (fn [e]
              (cond-> {:mutation (when (sequential? (:tx e)) (first (:tx e)))}
                (:tx e) (assoc :params (when (sequential? (:tx e))
                                         (second (:tx e))))
                (:component e) (assoc :component (:component e))
                (:ident e) (assoc :ident (:ident e))
                (:remotes e) (assoc :remotes (:remotes e))))
        tx-events)

      :network
      (correlate-network net-events)

      :uism
      (mapv (fn [e]
              (cond-> {:asm-id   (:uism/asm-id e)
                       :event-id (:uism/event-id e)}
                (:uism/event-data e) (assoc :event-data (:uism/event-data e))))
        uism-evts)

      :statecharts
      (mapv (fn [e]
              (cond-> {:session-id (:statechart/session-id e)
                       :event      (:statechart/event e)}
                (:statechart/data e) (assoc :data (:statechart/data e))
                (:statechart/configuration e) (assoc :configuration (:statechart/configuration e))))
        sc-events)

      :state-changes
      (summarize-state-changes db-events)})))
