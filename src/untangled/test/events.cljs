(ns untangled.test.events
  (:require [untangled.test.dom :as dom]
            [cljs.test :as t :include-macros true]
            [clojure.string :as str]))


(defprotocol IEventDetector
  (clear [this] "Clear all detected events.")
  (saw? [this evt] "Returns true if this detector has seen the given event.")
  (trigger-count [this evt] "Returns the number of times the event has been seen.")
  (is-seen? [this evt] "A cljs.test assertion form of saw? with better output.")
  (is-trigger-count [this evt cnt] "A cljs.test assertion form of checking trigger count with nice output.")
  (is-data-at [this evt n data] "A cljs.test assertion form of checking for event data on the n-th call")
  )


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; React event simulation wrappers
;
; TODO:
; Test all of these events against a single component (i.e. a div) with a single
; ":on[Event]" handler function attached to all of the events that does something very simple, like
; changing an ":[event]ChangeSuccess" boolean from false to true.

(defn send-click [target-kind target-search dom]
  (if-let [ele (dom/find-element target-kind target-search dom)]
    (try (js/React.addons.TestUtils.Simulate.click ele)
         (catch js/TypeError e (#'dom/report-addons-dependency-error e)))))

#_(defn blur [x & {:keys [] :as evt-data}] ((try (try (js/React.addons.TestUtils.Simulate.blur x (clj->js evt-data))
                                                      (catch js/TypeError e (#'dom/report-addons-dependency-error e)))

                                                 (catch js/TypeError e ""))))
(defn click [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.click x (clj->js evt-data))
                                               (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn contextMenu [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.contextMenu x (clj->js evt-data))
                                                     (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn copy [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.copy x (clj->js evt-data))
                                              (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn cut [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.cut x (clj->js evt-data))
                                             (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn doubleClick [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.doubleClick x (clj->js evt-data))
                                                     (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn drag [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.drag x (clj->js evt-data))
                                              (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn dragEnd [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.dragEnd x (clj->js evt-data))
                                                 (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn dragEnter [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.dragEnter x (clj->js evt-data))
                                                   (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn dragExit [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.dragExit x (clj->js evt-data))
                                                  (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn dragLeave [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.dragLeave x (clj->js evt-data))
                                                   (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn dragOver [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.dragOver x (clj->js evt-data))
                                                  (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn dragStart [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.dragStart x (clj->js evt-data))
                                                   (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn simulate-drop [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.drop x (clj->js evt-data))
                                                       (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn focus [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.focus x (clj->js evt-data))
                                               (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn input [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.input x (clj->js evt-data))
                                               (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn keyDown [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.keyDown x (clj->js evt-data))
                                                 (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn keyPress [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.keyPress x (clj->js evt-data))
                                                  (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn keyUp [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.keyUp x (clj->js evt-data))
                                               (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn load [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.load x (clj->js evt-data))
                                              (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn error [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.error x (clj->js evt-data))
                                               (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn mouseDown [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.mouseDown x (clj->js evt-data))
                                                   (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn mouseMove [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.mouseMove x (clj->js evt-data))
                                                   (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn mouseOut [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.mouseOut x (clj->js evt-data))
                                                  (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn mouseOver [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.mouseOver x (clj->js evt-data))
                                                   (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn mouseUp [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.mouseUp x (clj->js evt-data))
                                                 (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn paste [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.paste x (clj->js evt-data))
                                               (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn reset [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.reset x (clj->js evt-data))
                                               (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn scroll [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.scroll x (clj->js evt-data))
                                                (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn submit [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.submit x (clj->js evt-data))
                                                (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn touchCancel [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.touchCancel x (clj->js evt-data))
                                                     (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn touchEnd [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.touchEnd x (clj->js evt-data))
                                                  (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn touchMove [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.touchMove x (clj->js evt-data))
                                                   (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn touchStart [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.touchStart x (clj->js evt-data))
                                                    (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn wheel [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.wheel x (clj->js evt-data))
                                               (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn mouseEnter [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.mouseEnter x (clj->js evt-data))
                                                    (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn mouseLeave [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.mouseLeave x (clj->js evt-data))
                                                    (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn change [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.change x (clj->js evt-data))
                                                (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn compositionEnd [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.compositionEnd x (clj->js evt-data))
                                                        (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn compositionStart [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.compositionStart x (clj->js evt-data))
                                                          (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn compositionUpdate [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.compositionUpdate x (clj->js evt-data))
                                                           (catch js/TypeError e (#'dom/report-addons-dependency-error e))))
(defn select [x & {:keys [] :as evt-data}] (try (js/React.addons.TestUtils.Simulate.select x (clj->js evt-data))
                                                (catch js/TypeError e (#'dom/report-addons-dependency-error e))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom event simulations
;
; TODO: (dbl-click elem ...)


(defn str-to-keycode
  "
  Converts a one-character string into the equivalent keyCode. For more info on keycodes,
  see http://www.w3schools.com/jsref/event_key_keycode.asp.

  Returns:   `[keycode modifier]`
  `keycode`  The JavaScript keyCode indicating what key was pressed.
  "
  [key-str]
  (.charCodeAt key-str 0))


(defn send-key
  "
  Simulates the series of events that occur when a user pressess a single key.

  Parameters:
  `element` A rendered HTML element, as generated with `untangled.test.dom/render-as-dom`.
  `key-str` A string representing the key that was pressed, i.e. \"a\" or \">\".
  "
  [element key-str]
  (assert (dom/is-rendered-element? element) "Argument must be a rendered DOM element.")
  (let [keycode (str-to-keycode key-str)
        modifier (some #{keycode} '(16 17 18 91))                               ; (shift ctrl alt meta)
        is-modifier? (not (nil? modifier))]
    (if is-modifier? (keyDown element :keyCode keycode :which keycode)
                     (keyPress element :keyCode keycode :which keycode))

    ;; The above are the minimum events needed to emulate a keystroke.
    ;; More fully modeled event simulation is possible as follows:
    ;
    ;(when is-modifier? (keyDown element :keyCode modifier :which modifier))
    ;(keyDown element :key key-str :keyCode keycode :which keycode)
    ;(keyPress element :keyCode keycode :which keycode)
    ;(keyUp element :keyCode keycode :which keycode)
    ;(when is-modifier? (keyUp element :keyCode modifier :which modifier))))
    ))


(defn send-keys
  "
  Simulates a sequence of key presses.

  Parameters:
  `element` A rendered HTML element, as generated with `untangled.test.dom/render-as-dom`.
  `key-str` A string representing the keys that were pressed, i.e. \"aSdf\".
  "
  [element key-str]
  (dorun (map #(send-key element %) (str/split key-str ""))))


(defrecord EventDetector [events]
  cljs.core/Fn
  cljs.core/IFn
  (-invoke [this evt data]
    (swap! events #(update-in % [evt :count] inc))
    (swap! events #(update-in % [evt :data] (fn [ed] (vec (concat ed [data])))))
    )
  IEventDetector
  (clear [this] (reset! events {}))
  (saw? [this evt] (contains? @events evt))
  (trigger-count [this evt] (get-in @events [evt :count] 0))
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
  (is-data-at [this evt n data]
    (let [seen (trigger-count this evt)]
      (if (>= seen n)
        (let [evt-data (get-in @events [evt :data n])]
          (if (= data evt-data)
            (t/do-report {:type :pass})
            (t/do-report {:type   :fail :expected (str "To see event data '" data "' for '" evt "' at " n "th call.")
                          :actual (str "Saw event data '" evt-data "' for '" evt "'")})

            ))
        (t/do-report {:type   :fail :expected (str "To see event at '" evt "' " n " time(s).")
                      :actual (str "Saw event '" evt "' only seen " seen " time(s).")}))
      )
    )
  )

(defn event-detector [] (EventDetector. (atom {})))

