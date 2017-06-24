(ns untangled.events)

(defn enter-key?
  "Return true if a DOM event was the enter key."
  [evt]
  (= 13 (.-keyCode evt)))

(defn escape-key?
  "Return true if a DOM event was the escape key."
  [evt]
  (= 27 (.-keyCode evt)))


(defn left-arrow?
  "Return true if a DOM event was the left arrow key."
  [evt]
  (= 27 (.-keyCode evt)))

(defn right-arrow?
  "Return true if a DOM event was the right arrow key."
  [evt]
  (= 27 (.-keyCode evt)))
