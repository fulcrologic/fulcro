(ns fulcro.client-spec
  (:require
    [clojure.core.async :as async]
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [fulcro.client :as fc]
    [fulcro.client.impl.parser :as parser]
    [fulcro.client.impl.protocols :as omp]
    [fulcro.client.mutations :as m]
    [fulcro.client.network :as net]
    [fulcro.client.primitives :as prim :refer [defui defsc]]
    [fulcro.client.util :as fcu]
    [fulcro.logging :as log]
    [fulcro.util :as util]))

#?(:cljs
   (specification "Fulcro Application -- clear-pending-remote-requests!"
     (let [channel  (async/chan 1000)
           mock-app (fc/map->Application {:send-queues {:remote channel}})]
       (async/put! channel 1 #(async/put! channel 2 (fn [] (async/put! channel 3 (fn [] (async/put! channel 4))))))

       (fc/clear-pending-remote-requests! mock-app nil)

       (assertions
         "Removes any pending items in the network queue channel"
         (async/poll! channel) => nil))))

#?(:cljs
   (specification "mutation-query?"
     (behavior "Detects mutations"
       (assertions
         "containing mutation joins"
         (fc/-mutation-query? '[{(f) [:x]}]) => true
         (fc/-mutation-query? '[(g) {(f) [:x]}]) => true
         "even if they contain keyword follow-on reads"
         (fc/-mutation-query? '[(h) :x :y]) => true))
     (behavior "Detects queries"
       (assertions
         "that contain props and joins"
         (fc/-mutation-query? '[:x {:y [:z]}]) => false
         "that contain nothing but props"
         (fc/-mutation-query? '[:x]) => false
         "that are parameterized"
         (fc/-mutation-query? '[(:y {:p 1})]) => false))))

(defui ^:once BadResetAppRoot
  Object
  (render [this] nil))

(defui ^:once ResetAppRoot
  static prim/InitialAppState
  (initial-state [this params] {:x 1}))

#?(:cljs
   (specification "Fulcro Application -- reset-app!"
     (let [scb-calls        (atom 0)
           custom-calls     (atom 0)
           mock-app         (fc/map->Application {:send-queues      {:remote :fake-queue}
                                                  :started-callback (fn [] (swap! scb-calls inc))})
           cleared-network? (atom false)
           merged-unions?   (atom false)
           history-reset?   (atom false)
           re-rendered?     (atom false)
           state            (atom {})]
       (behavior "Logs an error if the supplied component does not implement InitialAppState"
         (when-mocking
           (log/-log location level & args) => (assertions
                                                 (first args) => "The specified root component does not implement InitialAppState!")
           (fc/reset-app! mock-app BadResetAppRoot nil)))

       (behavior "On a proper app root"
         (when-mocking
           (fc/clear-queue t) => (reset! cleared-network? true)
           (prim/app-state r) => state
           (fc/merge-alternate-union-elements! app r) => (reset! merged-unions? true)
           (fc/reset-history-impl a) => (reset! history-reset? true)
           (fcu/force-render a) => (reset! re-rendered? true)

           (fc/reset-app! mock-app ResetAppRoot nil)
           (fc/reset-app! mock-app ResetAppRoot :original)
           (fc/reset-app! mock-app ResetAppRoot (fn [a] (swap! custom-calls inc))))

         (assertions
           "Clears the network queue"
           @cleared-network? => true
           "Resets app history"
           @history-reset? => true
           "Sets the base state from component"
           @state => {:x 1}
           "Attempts to merge alternate union branches into state"
           @merged-unions? => true
           "Re-renders the app"
           @re-rendered? => true
           "Calls the original started-callback when callback is :original"
           @scb-calls => 1
           "Calls the supplied started-callback when callback is a function"
           @custom-calls => 1)))))

(defui RootNoState Object (render [this]))
(defui RootWithState
  static prim/InitialAppState
  (initial-state [c p] {:a 1})
  Object
  (render [this]))

#?(:cljs
   (specification "Mounting a Fulcro Application"
     (let [mounted-mock-app {:mounted? true :initial-state {}}]
       (provided "When it is already mounted"
         (fc/refresh* a r t) =1x=> (do
                                     (assertions
                                       "Refreshes the UI"
                                       1 => 1)
                                     a)

         (fc/mount* mounted-mock-app :fake-root :dom-id)))
     (behavior "When is is not already mounted"
       (behavior "and root does NOT implement InitialAppState"
         (let [supplied-state       {:a 1}
               app-with-initial-map {:mounted? false :initial-state supplied-state :reconciler-options :OPTIONS}]
           (when-mocking
             (fc/-initialize app state root dom opts) => (do
                                                          (assertions
                                                            "Initializes the app with a plain map"
                                                            state => supplied-state))

             (fc/mount* app-with-initial-map RootNoState :dom-id)))
         (let [supplied-atom         (atom {:a 1})
               app-with-initial-atom {:mounted? false :initial-state supplied-atom :reconciler-options :OPTIONS}]
           (when-mocking
             (fc/-initialize app state root dom opts) => (do
                                                          (assertions
                                                            "Initializes the app with a supplied atom"
                                                            (identical? state supplied-atom) => true))

             (fc/mount* app-with-initial-atom RootNoState :dom-id))))
       (behavior "and root IMPLEMENTS InitialAppState"
         (let [mock-app {:mounted? false :initial-state {} :reconciler-options :OPTIONS}]
           (when-mocking
             (fc/-initialize app state root dom opts) => (assertions
                                                          "Initializes the app with the InitialAppState if the supplied state is empty"
                                                          state => (prim/get-initial-state RootWithState nil))

             (fc/mount* mock-app RootWithState :dom-id)))
         (let [explicit-non-empty-map {:a 1}
               mock-app               {:mounted? false :initial-state explicit-non-empty-map :reconciler-options :OPTIONS}]
           (behavior "When an explicit non-empty map and InitialAppState are present:"
             (when-mocking
               (fc/-initialize app state root dom opts) => (do
                                                            (assertions
                                                              "Prefers the *explicit* state"
                                                              (identical? state explicit-non-empty-map) => true))

               (fc/mount* mock-app RootWithState :dom-id))))
         (let [supplied-atom (atom {})
               mock-app      {:mounted? false :initial-state supplied-atom :reconciler-options :OPTIONS}]
           (behavior "When an explicit atom and InitialAppState are present:"
             (when-mocking
               (fc/-initialize app state root dom opts) => (do
                                                            (assertions
                                                              "Prefers the *explicit* state"
                                                              (identical? state supplied-atom) => true))

               (fc/mount* mock-app RootWithState :dom-id))))
         (let [mock-app {:mounted? false :reconciler-options :OPTIONS}]
           (behavior "When only InitialAppState is present:"
             (when-mocking
               (fc/-initialize app state root dom opts) => (do
                                                            (assertions
                                                              "Supplies the raw InitialAppState to internal initialize"
                                                              state => (prim/get-initial-state RootWithState nil)))

               (fc/mount* mock-app RootWithState :dom-id))))))))


#?(:cljs
   (specification "Aborting items on the remote queue"
     (let [queue     (async/chan 1024)
           payload-1 {:id 1 ::prim/query '[(h)]}
           payload-2 {:id 2 ::net/abort-id :X}
           payload-3 {:id 3 ::prim/query '[(g)]}]

       (async/offer! queue payload-1)
       (async/offer! queue payload-2)
       (async/offer! queue payload-3)

       (fc/-abort-items-on-queue queue :X)

       (assertions
         "Removes just the items that have transactions with that abort id"
         (async/poll! queue) => payload-1
         (async/poll! queue) => payload-3
         (async/poll! queue) => nil))))
