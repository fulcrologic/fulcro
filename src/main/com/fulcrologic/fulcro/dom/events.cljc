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
  #?(:cljs (.. evt -target -value)))

(defn is-key?
  "Is the given key code on the given event?"
  #?(:cljs {:tag boolean})
  [code evt] (= code (.-keyCode evt)))

(defn enter-key? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 13 evt))
(defn escape-key? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 27 evt))
(defn left-arrow? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 37 evt))
(defn right-arrow? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 39 evt))
(defn up-arrow? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 38 evt))
(defn down-arrow? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 40 evt))
(defn page-up? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 33 evt))
(defn page-down? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 34 evt))
(defn enter? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 13 evt))
(defn escape? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 27 evt))
(defn delete? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 46 evt))
(defn tab? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 9 evt))
(defn end? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 35 evt))
(defn home? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 36 evt))
(defn alt? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 18 evt))
(defn ctrl? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 17 evt))
(defn shift? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 16 evt))
(defn F1? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 112 evt))
(defn F2? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 113 evt))
(defn F3? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 114 evt))
(defn F4? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 115 evt))
(defn F5? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 116 evt))
(defn F6? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 117 evt))
(defn F7? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 118 evt))
(defn F8? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 119 evt))
(defn F9? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 120 evt))
(defn F10? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 121 evt))
(defn F11? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 122 evt))
(defn F12? "Returns true if the event has the keyCode of the function name." #?(:cljs {:tag boolean}) [evt] (is-key? 123 evt))
