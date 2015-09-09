(ns untangled.test.events
  (:require [untangled.test.dom]))


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
(defn click [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.click x (hashmap-to-js-obj evt-data)))
(defn contextMenu [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.contextMenu x evt-data))
(defn copy [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.copy x evt-data))
(defn cut [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.cut x evt-data))
(defn doubleClick [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.doubleClick x evt-data))
(defn drag [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.drag x evt-data))
(defn dragEnd [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.dragEnd x evt-data))
(defn dragEnter [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.dragEnter x evt-data))
(defn dragExit [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.dragExit x evt-data))
(defn dragLeave [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.dragLeave x evt-data))
(defn dragOver [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.dragOver x evt-data))
(defn dragStart [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.dragStart x evt-data))
(defn simulate-drop [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.drop x evt-data))
(defn focus [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.focus x evt-data))
(defn input [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.input x evt-data))
(defn keyDown [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.keyDown x evt-data))
(defn keyPress [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.keyPress x evt-data))
(defn keyUp [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.keyUp x evt-data))
(defn load [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.load x evt-data))
(defn error [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.error x evt-data))
(defn mouseDown [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseDown x evt-data))
(defn mouseMove [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseMove x evt-data))
(defn mouseOut [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseOut x evt-data))
(defn mouseOver [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseOver x evt-data))
(defn mouseUp [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseUp x evt-data))
(defn paste [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.paste x evt-data))
(defn reset [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.reset x evt-data))
(defn scroll [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.scroll x evt-data))
(defn submit [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.submit x evt-data))
(defn touchCancel [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.touchCancel x evt-data))
(defn touchEnd [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.touchEnd x evt-data))
(defn touchMove [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.touchMove x evt-data))
(defn touchStart [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.touchStart x evt-data))
(defn wheel [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.wheel x evt-data))
(defn mouseEnter [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseEnter x evt-data))
(defn mouseLeave [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.mouseLeave x evt-data))
(defn change [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.change x evt-data))
(defn compositionEnd [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.compositionEnd x evt-data))
(defn compositionStart [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.compositionStart x evt-data))
(defn compositionUpdate [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.compositionUpdate x evt-data))
(defn select [x & {:keys [] :as evt-data}] (js/React.addons.TestUtils.Simulate.select x evt-data))


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
  Converts a one-character string into the equivalent JavaScript keyCode.

  Returns:   `[keycode modifier]`
  `keycode`  The JavaScript keyCode indicating what key was pressed.
  `modifier` Either a `:keyword` indicating what modifier is required to produce the character,
             or `nil` if no modifier key is required. "
  [key-str])


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
  (let [[keycode modifier] (str-to-keycode key-str)
        shift-pressed? (= :shift modifier)]
    (when shift-pressed? (keyDown element {:keyCode modifier}))
    (keyDown element {:keyCode keycode})
    (keyPress element {:keyCode keycode})
    (keyUp element {:keyCode keycode})
    (when shift-pressed? (keyUp element {:keyCode modifier}))))
