(ns dom-tools.event-sim
  (:require [dom-tools.query]))


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

(defn blur [x evt] (js/React.addons.TestUtils.Simulate.blur x evt))
(defn click [x evt] (js/React.addons.TestUtils.Simulate.click x evt))
(defn contextMenu [x evt] (js/React.addons.TestUtils.Simulate.contextMenu x evt))
(defn copy [x evt] (js/React.addons.TestUtils.Simulate.copy x evt))
(defn cut [x evt] (js/React.addons.TestUtils.Simulate.cut x evt))
(defn doubleClick [x evt] (js/React.addons.TestUtils.Simulate.doubleClick x evt))
(defn drag [x evt] (js/React.addons.TestUtils.Simulate.drag x evt))
(defn dragEnd [x evt] (js/React.addons.TestUtils.Simulate.dragEnd x evt))
(defn dragEnter [x evt] (js/React.addons.TestUtils.Simulate.dragEnter x evt))
(defn dragExit [x evt] (js/React.addons.TestUtils.Simulate.dragExit x evt))
(defn dragLeave [x evt] (js/React.addons.TestUtils.Simulate.dragLeave x evt))
(defn dragOver [x evt] (js/React.addons.TestUtils.Simulate.dragOver x evt))
(defn dragStart [x evt] (js/React.addons.TestUtils.Simulate.dragStart x evt))
(defn simulate-drop [x evt] (js/React.addons.TestUtils.Simulate.drop x evt))
(defn focus [x evt] (js/React.addons.TestUtils.Simulate.focus x evt))
(defn input [x evt] (js/React.addons.TestUtils.Simulate.input x evt))
(defn keyDown [x evt] (js/React.addons.TestUtils.Simulate.keyDown x evt))
(defn keyPress [x evt] (js/React.addons.TestUtils.Simulate.keyPress x evt))
(defn keyUp [x evt] (js/React.addons.TestUtils.Simulate.keyUp x evt))
(defn load [x evt] (js/React.addons.TestUtils.Simulate.load x evt))
(defn error [x evt] (js/React.addons.TestUtils.Simulate.error x evt))
(defn mouseDown [x evt] (js/React.addons.TestUtils.Simulate.mouseDown x evt))
(defn mouseMove [x evt] (js/React.addons.TestUtils.Simulate.mouseMove x evt))
(defn mouseOut [x evt] (js/React.addons.TestUtils.Simulate.mouseOut x evt))
(defn mouseOver [x evt] (js/React.addons.TestUtils.Simulate.mouseOver x evt))
(defn mouseUp [x evt] (js/React.addons.TestUtils.Simulate.mouseUp x evt))
(defn paste [x evt] (js/React.addons.TestUtils.Simulate.paste x evt))
(defn reset [x evt] (js/React.addons.TestUtils.Simulate.reset x evt))
(defn scroll [x evt] (js/React.addons.TestUtils.Simulate.scroll x evt))
(defn submit [x evt] (js/React.addons.TestUtils.Simulate.submit x evt))
(defn touchCancel [x evt] (js/React.addons.TestUtils.Simulate.touchCancel x evt))
(defn touchEnd [x evt] (js/React.addons.TestUtils.Simulate.touchEnd x evt))
(defn touchMove [x evt] (js/React.addons.TestUtils.Simulate.touchMove x evt))
(defn touchStart [x evt] (js/React.addons.TestUtils.Simulate.touchStart x evt))
(defn wheel [x evt] (js/React.addons.TestUtils.Simulate.wheel x evt))
(defn mouseEnter [x evt] (js/React.addons.TestUtils.Simulate.mouseEnter x evt))
(defn mouseLeave [x evt] (js/React.addons.TestUtils.Simulate.mouseLeave x evt))
(defn change [x evt] (js/React.addons.TestUtils.Simulate.change x evt))
(defn compositionEnd [x evt] (js/React.addons.TestUtils.Simulate.compositionEnd x evt))
(defn compositionStart [x evt] (js/React.addons.TestUtils.Simulate.compositionStart x evt))
(defn compositionUpdate [x evt] (js/React.addons.TestUtils.Simulate.compositionUpdate x evt))
(defn select [x evt] (js/React.addons.TestUtils.Simulate.select x evt))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom event simulations
;
; TODO:
; Provide some more powerful meta-functions
;
; Start with these three:
; (click elem & :keys :vals)
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


