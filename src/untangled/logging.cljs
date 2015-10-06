(ns untangled.logging)

;; TODO: Make these browser agnostic, with some additional colorization or whatever the console will support.
(defn log [message] (.log js/console message))
(defn warn [message] (.log js/console (str "WARNING: " message)))
(defn error [message] (.log js/console (str "ERROR: " message)))
