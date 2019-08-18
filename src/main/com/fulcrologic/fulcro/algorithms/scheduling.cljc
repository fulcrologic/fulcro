(ns com.fulcrologic.fulcro.algorithms.scheduling
  "Algorithms for delaying some action by a particular amount of time."
  (:require
    [ghostwheel.core :refer [>fdef =>]]
    [clojure.core.async :as async]))

(defn defer
  "Schedule f to run in `tm` ms."
  [f tm]
  #?(:cljs (js/setTimeout f tm)
     :clj  (async/go
             (async/<! (async/timeout (+ 100 tm)))
             (f))))

(defn schedule!
  "Schedule the processing of a specific action in the runtime atom. This is a no-op if the item is already scheduled.
  When the timeout arrives it runs the given action and sets the given flag back to false.

  - `scheduled-key` - The runtime flag that tracks scheduling for the processing.
  - `action` - The function to run when the scheduled time comes.
  - `tm` - Number of ms to delay (default 0)."
  ([app scheduled-key action tm]
   [:com.fulcrologic.fulcro.application/app keyword? fn? int? => any?]
   (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app]
     (when-not (get @runtime-atom scheduled-key)
       (swap! runtime-atom assoc scheduled-key true)
       (defer (fn []
                (swap! runtime-atom assoc scheduled-key false)
                (action app)) tm))))
  ([app scheduled-key action]
   [:com.fulcrologic.fulcro.application/app keyword? fn? => any?]
   (schedule! app scheduled-key action 0)))

(defn schedule-animation!
  "Schedule the processing of a specific action in the runtime atom on the next animation frame.

  - `scheduled-key` - The runtime flag that tracks scheduling for the processing.
  - `action` - The function to run when the scheduled time comes."
  ([app scheduled-key action]
   [:com.fulcrologic.fulcro.application/app keyword? fn? => any?]
   #?(:clj  (action)
      :cljs (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app]
              (when-not (get @runtime-atom scheduled-key)
                (swap! runtime-atom assoc scheduled-key true)
                (let [f (fn []
                          (swap! runtime-atom assoc scheduled-key false)
                          (action))]
                  (if-not (exists? js/requestAnimationFrame)
                    (defer f 16)
                    (js/requestAnimationFrame f))))))))
