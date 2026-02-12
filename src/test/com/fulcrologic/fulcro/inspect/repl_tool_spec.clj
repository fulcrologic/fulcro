(ns com.fulcrologic.fulcro.inspect.repl-tool-spec
  "Tests for the repl-tool flight recorder.

   NOTE: These tests require -Dcom.fulcrologic.fulcro.inspect=true (set by the :clj-tests alias)
   so that inspect notifications flow through the `ido` macro."
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom-server :as dom]
    [com.fulcrologic.fulcro.headless :as h]
    [com.fulcrologic.fulcro.inspect.inspect-client :as ic]
    [com.fulcrologic.fulcro.inspect.repl-tool :as rt]
    [com.fulcrologic.fulcro.inspect.tools :as fit]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [fulcro-spec.core :refer [assertions behavior component specification]]))

(declare =>)

;; =============================================================================
;; Test Components
;; =============================================================================

(defmutation set-counter
  "Set the counter to a specific value."
  [{:keys [value]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [:component/id :counter :counter/value] value)))

(defmutation increment
  "Increment the counter by 1."
  [_]
  (action [{:keys [state]}]
    (swap! state update-in [:component/id :counter :counter/value] (fnil inc 0))))

(defsc Counter [_ {:counter/keys [value]}]
  {:query         [:counter/value]
   :ident         (fn [] [:component/id :counter])
   :initial-state {:counter/value 0}}
  (dom/div {:id "counter"}
    (dom/span {:id "count"} (str value))))

(defsc Root [_ {:root/keys [counter]}]
  {:query         [{:root/counter (comp/get-query Counter)}]
   :initial-state {:root/counter {}}}
  (dom/div {:id "app"}
    ((comp/factory Counter) counter)))

;; =============================================================================
;; Helper
;; =============================================================================

(defn- build-app
  "Build a headless test app with repl-tool installed."
  []
  (let [app (h/build-test-app {:root-class Root})]
    (rt/install! app)
    (rt/clear!)
    app))

(defn- simulate-db-changed!
  "Directly notify the repl-tool of a db-changed event (bypassing ido/inspect-client)."
  [app]
  (fit/notify! app 'com.fulcrologic.fulcro.inspect.devtool-api/db-changed
    {:history/version  2
     :history/based-on 1
     :history/diff     {:fulcro.inspect.lib.diff/updates  {:foo "bar"}
                        :fulcro.inspect.lib.diff/removals []}}))

(defn- simulate-send-started!
  "Directly notify the repl-tool of a send-started event."
  [app request-id]
  (fit/notify! app 'com.fulcrologic.fulcro.inspect.devtool-api/send-started
    {:fulcro.inspect.ui.network/remote             :remote
     :fulcro.inspect.ui.network/request-id         request-id
     :fulcro.inspect.ui.network/request-started-at (java.util.Date.)
     :fulcro.inspect.ui.network/request-edn        '[(do-thing {:x 1})]}))

(defn- simulate-send-finished!
  "Directly notify the repl-tool of a send-finished event."
  [app request-id]
  (fit/notify! app 'com.fulcrologic.fulcro.inspect.devtool-api/send-finished
    {:fulcro.inspect.ui.network/request-id          request-id
     :fulcro.inspect.ui.network/request-finished-at (java.util.Date.)
     :fulcro.inspect.ui.network/response-edn        {:do-thing {:result :ok}}}))

(defn- simulate-send-failed!
  "Directly notify the repl-tool of a send-failed event."
  [app request-id]
  (fit/notify! app 'com.fulcrologic.fulcro.inspect.devtool-api/send-failed
    {:fulcro.inspect.ui.network/request-id          request-id
     :fulcro.inspect.ui.network/request-finished-at (java.util.Date.)
     :fulcro.inspect.ui.network/error               {:message "timeout"}}))

;; =============================================================================
;; Tests
;; =============================================================================

(specification "Repl tool prerequisite"
  (assertions
    "INSPECT must be enabled (run with -Dcom.fulcrologic.fulcro.inspect=true)"
    ic/INSPECT => "true"))

(specification "install! and clear!"
  (let [app (build-app)]
    (behavior "installs the repl-tool on the app"
      (assertions
        "install! returns :ok"
        (rt/install! app) => :ok

        "sets the default app so zero-arity calls work"
        (rt/events) => []))

    (behavior "clear! resets the event log"
      (comp/transact! app [(increment)])
      (let [ts (rt/clear!)]
        (assertions
          "returns a timestamp"
          (pos? ts) => true

          "empties the event log"
          (rt/events) => [])))))

(specification "Event capture - transactions via comp/transact!"
  (let [app (build-app)]
    (comp/transact! app [(set-counter {:value 42})])

    (behavior "captures optimistic action events as :tx category"
      (let [tx-events (rt/events {:category :tx})]
        (assertions
          "captures at least one tx event"
          (pos? (count tx-events)) => true

          "each event has the :tx category"
          (every? #(= :tx (:repl-tool/category %)) tx-events) => true

          "the transaction contains the mutation symbol"
          (some #(= `set-counter (first (:tx %))) tx-events) => true

          "events have timestamps and sequence numbers"
          (every? :repl-tool/timestamp tx-events) => true
          (every? :repl-tool/seq tx-events) => true)))))

(specification "Event capture - db changes via fit/notify!"
  (let [app (build-app)]
    (simulate-db-changed! app)

    (behavior "captures db-changed events"
      (let [db-events (rt/events {:category :db})]
        (assertions
          "captures the db change event"
          (count db-events) => 1

          "event has the :db category"
          (:repl-tool/category (first db-events)) => :db

          "event contains a diff"
          (some? (:diff (first db-events))) => true

          "event contains history version info"
          (:history/version (first db-events)) => 2
          (:history/based-on (first db-events)) => 1)))))

(specification "Event capture - network via fit/notify!"
  (let [app (build-app)]
    (simulate-send-started! app "req-1")
    (simulate-send-finished! app "req-1")

    (behavior "captures network send events"
      (let [net-events (rt/events {:category :net})]
        (assertions
          "captures both started and finished events"
          (count net-events) => 2

          "events have the :net category"
          (every? #(= :net (:repl-tool/category %)) net-events) => true

          "includes a started phase"
          (some #(= :started (:net/phase %)) net-events) => true

          "includes a finished phase"
          (some #(= :finished (:net/phase %)) net-events) => true)))

    (behavior "captures failed sends"
      (rt/clear!)
      (simulate-send-started! app "req-2")
      (simulate-send-failed! app "req-2")
      (let [net-events (rt/events {:category :net})]
        (assertions
          "includes a failed phase"
          (some #(= :failed (:net/phase %)) net-events) => true)))))

(specification "events filtering"
  (let [app (build-app)]
    (comp/transact! app [(increment)])
    (comp/transact! app [(set-counter {:value 10})])
    (simulate-db-changed! app)

    (behavior ":category filter works"
      (assertions
        "can filter to just :tx events"
        (every? #(= :tx (:repl-tool/category %)) (rt/events {:category :tx})) => true

        "can filter to just :db events"
        (every? #(= :db (:repl-tool/category %)) (rt/events {:category :db})) => true))

    (behavior ":last filter returns only the last N events"
      (let [all    (rt/events)
            last-2 (rt/events {:last 2})]
        (assertions
          "returns exactly 2 events"
          (count last-2) => 2

          "they are the last 2 from the full log"
          last-2 => (vec (take-last 2 all)))))

    (behavior ":since filter returns events after timestamp"
      (let [mid-ts (rt/clear!)]
        (comp/transact! app [(increment)])
        (let [since-events (rt/events {:since mid-ts})]
          (assertions
            "all returned events are after the timestamp"
            (every? #(> (:repl-tool/timestamp %) mid-ts) since-events) => true))))))

(specification "tx-log"
  (let [app (build-app)]
    (comp/transact! app [(set-counter {:value 5})])
    (comp/transact! app [(increment)])

    (behavior "returns a simplified view of transaction events"
      (let [log (rt/tx-log)]
        (assertions
          "returns 2 entries"
          (count log) => 2

          "each entry has the expected keys"
          (every? #(contains? % :tx) log) => true
          (every? #(contains? % :repl-tool/timestamp) log) => true
          (every? #(contains? % :repl-tool/seq) log) => true)))))

(specification "network-log"
  (let [app (build-app)]
    (simulate-send-started! app "req-100")
    (simulate-send-finished! app "req-100")

    (behavior "returns correlated network entries grouped by request-id"
      (let [log (rt/network-log)]
        (assertions
          "returns one correlated entry"
          (count log) => 1

          "entry has a request-id and status"
          (:request-id (first log)) => "req-100"
          (:status (first log)) => :finished

          "entry has request and response EDN"
          (some? (:request-edn (first log))) => true
          (some? (:response-edn (first log))) => true)))))

(specification "digest"
  (let [app (build-app)]
    (comp/transact! app [(set-counter {:value 1})])
    (comp/transact! app [(increment)])
    (simulate-db-changed! app)

    (behavior "returns a structured summary"
      (let [d (rt/digest)]
        (assertions
          "has a :window section with event count"
          (pos? (get-in d [:window :event-count])) => true

          "has a :transactions section"
          (vector? (:transactions d)) => true
          (pos? (count (:transactions d))) => true

          "transactions contain mutation symbols"
          (some #(= `set-counter (:mutation %)) (:transactions d)) => true
          (some #(= `increment (:mutation %)) (:transactions d)) => true

          "has a :network section"
          (vector? (:network d)) => true

          "has a :state-changes section"
          (map? (:state-changes d)) => true)))

    (behavior "respects :since option"
      (let [ts (do (rt/clear!) (Thread/sleep 2) (System/currentTimeMillis))
            _  (Thread/sleep 2)
            _  (comp/transact! app [(increment)])
            d  (rt/digest {:since ts})]
        (assertions
          "only includes events after the timestamp"
          (pos? (get-in d [:window :event-count])) => true

          "only has the increment transaction"
          (= 1 (count (:transactions d))) => true
          (= `increment (-> d :transactions first :mutation)) => true)))))

(specification "Multiple installs are idempotent"
  (let [app (build-app)]
    (rt/install! app)
    (rt/install! app)
    (comp/transact! app [(increment)])

    (behavior "events are not duplicated by multiple install! calls"
      (let [tx-events (rt/events {:category :tx})]
        (assertions
          "only one tx event per transaction (not multiplied)"
          (count tx-events) => 1)))))

(specification "max-events bounds the log"
  (let [app (h/build-test-app {:root-class Root})]
    (rt/install! app {:max-events 3})
    (rt/clear!)

    (dotimes [_ 5]
      (comp/transact! app [(increment)]))

    (behavior "the event log is bounded"
      (let [all-events (rt/events)]
        (assertions
          "total events don't exceed the max"
          (<= (count all-events) 3) => true)))))
