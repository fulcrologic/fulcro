(ns todo.events)

(defn enter-key?
  "Return true if an event was the enter key"
  [evt]
  (= 13 (.-keyCode evt)))

(defn text-value "Returns the text value from an input change event"
  [evt]
  (.-value (.-target evt))
  )
