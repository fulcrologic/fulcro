(ns untangled.test.events
  (:require [untangled.test.dom]
            [clojure.string :as str]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; React event simulation wrappers
;
; TODO:
; Test all of these events against a single component (i.e. a div) with a single
; ":on[Event]" handler function attached to all of the events that does something very simple, like
; changing an ":[event]ChangeSuccess" boolean from false to true.
;
; TODO:
; Check existing libraries for a way to create event data.

(defn hashmap-to-js-obj
  "Converts a clojure hashmap to a javascript object."
  [hashmap]
  (->> hashmap
       (map (fn [x] [(name (first x)) (last x)]))
       (into {})
       (clj->js)))


(defn blur [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.blur x (clj->js evt-data)))
(defn click [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.click x (clj->js evt-data)))
(defn contextMenu [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.contextMenu x (clj->js evt-data)))
(defn copy [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.copy x (clj->js evt-data)))
(defn cut [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.cut x (clj->js evt-data)))
(defn doubleClick [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.doubleClick x (clj->js evt-data)))
(defn drag [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.drag x (clj->js evt-data)))
(defn dragEnd [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.dragEnd x (clj->js evt-data)))
(defn dragEnter [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.dragEnter x (clj->js evt-data)))
(defn dragExit [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.dragExit x (clj->js evt-data)))
(defn dragLeave [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.dragLeave x (clj->js evt-data)))
(defn dragOver [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.dragOver x (clj->js evt-data)))
(defn dragStart [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.dragStart x (clj->js evt-data)))
(defn simulate-drop [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.drop x (clj->js evt-data)))
(defn focus [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.focus x (clj->js evt-data)))
(defn input [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.input x (clj->js evt-data)))
(defn keyDown [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.keyDown x (clj->js evt-data)))
(defn keyPress [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.keyPress x (clj->js evt-data)))
(defn keyUp [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.keyUp x (clj->js evt-data)))
(defn load [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.load x (clj->js evt-data)))
(defn error [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.error x (clj->js evt-data)))
(defn mouseDown [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseDown x (clj->js evt-data)))
(defn mouseMove [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseMove x (clj->js evt-data)))
(defn mouseOut [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseOut x (clj->js evt-data)))
(defn mouseOver [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseOver x (clj->js evt-data)))
(defn mouseUp [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseUp x (clj->js evt-data)))
(defn paste [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.paste x (clj->js evt-data)))
(defn reset [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.reset x (clj->js evt-data)))
(defn scroll [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.scroll x (clj->js evt-data)))
(defn submit [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.submit x (clj->js evt-data)))
(defn touchCancel [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.touchCancel x (clj->js evt-data)))
(defn touchEnd [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.touchEnd x (clj->js evt-data)))
(defn touchMove [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.touchMove x (clj->js evt-data)))
(defn touchStart [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.touchStart x (clj->js evt-data)))
(defn wheel [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.wheel x (clj->js evt-data)))
(defn mouseEnter [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseEnter x (clj->js evt-data)))
(defn mouseLeave [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseLeave x (clj->js evt-data)))
(defn change [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.change x (clj->js evt-data)))
(defn compositionEnd [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.compositionEnd x (clj->js evt-data)))
(defn compositionStart [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.compositionStart x (clj->js evt-data)))
(defn compositionUpdate [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.compositionUpdate x (clj->js evt-data)))
(defn select [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.select x (clj->js evt-data)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom event simulations
;
; TODO:
; Provide some more powerful meta-functions
;
; Start with these three:
; (click elem & :keys :vals)
; (click elem :x 1 :y 2)
; (send-keys elem "asdf" & more?)
; (dbl-click elem ...)
;
;
; Examples of usage:
;
;(click-button :label "Today" component)
;(click-button :key "1" component)
;(click-button "key" "whatever" component)
;(click-button :class-name "" component)
;(click-checkbox :data-boo "blah" component)
;(send-input  )
;(send-keys  )


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
  Simulates the series of events that occur when a user pressess a single key, which may be
  modified with [shift].

  - For non-modified (i.e. lowercase) keys, the event sequence is `keydown->keypress->keyup`.
  - For modified (i.e. uppercase letters and keys such as !@#$ etc.), the event sequence is
  `[shift-keydown]->keydown->keypress->keyup->[shift-keyup]`.

  Parameters:
  `element` A rendered HTML element, as generated with `untangled.test.dom/render-as-dom`.
  `key-str` A string representing the key that was pressed, i.e. \"a\" or \">\".
  "
  [element key-str]
  (let [keycode (str-to-keycode key-str)
        modifier (some #{keycode} '(16 17 18 91))
        is-modifier? (not (nil? modifier))]
    (if is-modifier? (keyDown element :keyCode keycode :which keycode)
                     (keyPress element :keyCode keycode :which keycode))

    ;; TODO? More fully modeled event simulation.
    ;; The above are the minimum events needed to emulate a keystroke.
    ;; The full series of events generated is:
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
  (map #(send-key element key-str) (str/split key-str "")))

