(ns untangled.test.events
  (:require [untangled.test.dom :as dom]
            [cljs.test :as t :include-macros true]))


(defn send-click [target-kind target-search dom]
  (if-let [ele (dom/find-element target-kind target-search dom)]
    (js/React.addons.TestUtils.Simulate.click ele)
    )
  )

(defprotocol IEventDetector
  (clear [this] "Clear all detected events.")
  (saw? [this evt] "Returns true if this detector has seen the given event.")
  (trigger-count [this evt] "Returns the number of times the event has been seen.")
  (is-seen? [this evt] "A cljs.test assertion form of saw? with better output.")
  (is-trigger-count [this evt cnt] "A cljs.test assertion form of checking trigger count with nice output.")
  )

(defrecord EventDetector [events]
  cljs.core/Fn
  cljs.core/IFn
  (-invoke [this evt] (swap! events #(update % evt inc)))
  IEventDetector
  (clear [this] (reset! events {}))
  (saw? [this evt] (contains? @events evt))
  (trigger-count [this evt] (or (get @events evt) 0))
  (is-seen? [this evt] (if (saw? this evt)
                         (t/do-report {:type :pass})
                         (t/do-report {:type :fail :expected evt :actual "Event not seen"})
                         ))
  (is-trigger-count [this evt cnt]
    (let [seen (trigger-count this evt)]
      (if (= cnt seen)
        (t/do-report {:type :pass})
        (t/do-report {:type   :fail :expected (str "To see event '" evt "' " cnt " time(s).")
                      :actual (str "Saw event '" evt "' " seen " time(s).")})
        ))
    )
  )

(defn event-detector [] (EventDetector. (atom {})))

