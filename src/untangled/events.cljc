(ns untangled.events)

(defn enter-key?
  "Return true if a DOM event was the enter key."
  [evt]
  (= 13 (.-keyCode evt)))

(defn escape-key?
  "Return true if a DOM event was the escape key."
  [evt]
  (= 27 (.-keyCode evt)))


