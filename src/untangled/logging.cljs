(ns untangled.logging)

(defn log [message] (.log js/console message))