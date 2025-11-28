(ns com.fulcrologic.fulcro.dom.events
  "Utility functions for working with low-level synthetic js events on the DOM")

(defn stop-propagation!
  "Calls .stopPropagation on the given event. Safe to use in CLJC files."
  [evt] #?(:cljs (.stopPropagation ^js evt)))

(defn prevent-default!
  "Calls .preventDefault on the given event. Safe to use in CLJC files."
  [evt] #?(:cljs (.preventDefault ^js evt)))

(defn target-value
  "Returns the event #js evt.target.value. Safe to use in CLJC."
  [evt]
  #?(:cljs (.. ^js evt -target -value)
     :clj  (-> evt :target :value)))

;; =============================================================================
;; Key Code Constants
;; =============================================================================

(def tab-keycode 9)
(def enter-keycode 13)
(def shift-keycode 16)
(def ctrl-keycode 17)
(def alt-keycode 18)
(def escape-keycode 27)
(def page-up-keycode 33)
(def page-down-keycode 34)
(def end-keycode 35)
(def home-keycode 36)
(def left-arrow-keycode 37)
(def up-arrow-keycode 38)
(def right-arrow-keycode 39)
(def down-arrow-keycode 40)
(def delete-keycode 46)
(def F1-keycode 112)
(def F2-keycode 113)
(def F3-keycode 114)
(def F4-keycode 115)
(def F5-keycode 116)
(def F6-keycode 117)
(def F7-keycode 118)
(def F8-keycode 119)
(def F9-keycode 120)
(def F10-keycode 121)
(def F11-keycode 122)
(def F12-keycode 123)

;; =============================================================================
;; Key Code Predicates
;; =============================================================================

(defn is-key?
  "Is the given key code on the given event?"
  #?(:cljs {:tag boolean})
  [code evt]
  (= code #?(:cljs (.-keyCode ^js evt)
             :clj  (or (:keyCode evt) (:key-code evt)))))

(defn enter-key? "Returns true if the event has the enter key code." #?(:cljs {:tag boolean}) [evt] (is-key? enter-keycode evt))
(defn escape-key? "Returns true if the event has the escape key code." #?(:cljs {:tag boolean}) [evt] (is-key? escape-keycode evt))
(defn left-arrow? "Returns true if the event has the left arrow key code." #?(:cljs {:tag boolean}) [evt] (is-key? left-arrow-keycode evt))
(defn right-arrow? "Returns true if the event has the right arrow key code." #?(:cljs {:tag boolean}) [evt] (is-key? right-arrow-keycode evt))
(defn up-arrow? "Returns true if the event has the up arrow key code." #?(:cljs {:tag boolean}) [evt] (is-key? up-arrow-keycode evt))
(defn down-arrow? "Returns true if the event has the down arrow key code." #?(:cljs {:tag boolean}) [evt] (is-key? down-arrow-keycode evt))
(defn page-up? "Returns true if the event has the page up key code." #?(:cljs {:tag boolean}) [evt] (is-key? page-up-keycode evt))
(defn page-down? "Returns true if the event has the page down key code." #?(:cljs {:tag boolean}) [evt] (is-key? page-down-keycode evt))
(defn enter? "Returns true if the event has the enter key code." #?(:cljs {:tag boolean}) [evt] (is-key? enter-keycode evt))
(defn escape? "Returns true if the event has the escape key code." #?(:cljs {:tag boolean}) [evt] (is-key? escape-keycode evt))
(defn delete? "Returns true if the event has the delete key code." #?(:cljs {:tag boolean}) [evt] (is-key? delete-keycode evt))
(defn tab? "Returns true if the event has the tab key code." #?(:cljs {:tag boolean}) [evt] (is-key? tab-keycode evt))
(defn end? "Returns true if the event has the end key code." #?(:cljs {:tag boolean}) [evt] (is-key? end-keycode evt))
(defn home? "Returns true if the event has the home key code." #?(:cljs {:tag boolean}) [evt] (is-key? home-keycode evt))
(defn alt? "Returns true if the event has the alt key code." #?(:cljs {:tag boolean}) [evt] (is-key? alt-keycode evt))
(defn ctrl? "Returns true if the event has the ctrl key code." #?(:cljs {:tag boolean}) [evt] (is-key? ctrl-keycode evt))
(defn shift? "Returns true if the event has the shift key code." #?(:cljs {:tag boolean}) [evt] (is-key? shift-keycode evt))
(defn F1? "Returns true if the event has the F1 key code." #?(:cljs {:tag boolean}) [evt] (is-key? F1-keycode evt))
(defn F2? "Returns true if the event has the F2 key code." #?(:cljs {:tag boolean}) [evt] (is-key? F2-keycode evt))
(defn F3? "Returns true if the event has the F3 key code." #?(:cljs {:tag boolean}) [evt] (is-key? F3-keycode evt))
(defn F4? "Returns true if the event has the F4 key code." #?(:cljs {:tag boolean}) [evt] (is-key? F4-keycode evt))
(defn F5? "Returns true if the event has the F5 key code." #?(:cljs {:tag boolean}) [evt] (is-key? F5-keycode evt))
(defn F6? "Returns true if the event has the F6 key code." #?(:cljs {:tag boolean}) [evt] (is-key? F6-keycode evt))
(defn F7? "Returns true if the event has the F7 key code." #?(:cljs {:tag boolean}) [evt] (is-key? F7-keycode evt))
(defn F8? "Returns true if the event has the F8 key code." #?(:cljs {:tag boolean}) [evt] (is-key? F8-keycode evt))
(defn F9? "Returns true if the event has the F9 key code." #?(:cljs {:tag boolean}) [evt] (is-key? F9-keycode evt))
(defn F10? "Returns true if the event has the F10 key code." #?(:cljs {:tag boolean}) [evt] (is-key? F10-keycode evt))
(defn F11? "Returns true if the event has the F11 key code." #?(:cljs {:tag boolean}) [evt] (is-key? F11-keycode evt))
(defn F12? "Returns true if the event has the F12 key code." #?(:cljs {:tag boolean}) [evt] (is-key? F12-keycode evt))
