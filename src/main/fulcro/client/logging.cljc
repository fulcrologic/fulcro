(ns fulcro.client.logging
  "DEPRECATED: Will be removed in a future release. As of version 2.5, this is just a facade on top of fulcro.logging that
  delegates to it."
  (:require [fulcro.logging :as log]))

(def set-level log/set-level!)

#?(:cljs
   (defn value-message
     "Include a pretty-printed cljs value as a string with the given text message."
     [msg val]
     (str msg ":\n" (pr-str val)))
   :clj
   (defn value-message [msg val] (str msg val)))

(defn debug
  "Print a debug message to the logger which includes a value.
  Returns the value (like identity) so it can be harmlessly nested in expressions."
  ([value] (log/debug (value-message "DEBUG" value)) value)
  ([msg value] (log/debug (value-message msg value)) value))

(defn info
  "output an INFO level message to the logger"
  [& data]
  (log/info (apply str (interpose " " data))))

(defn warn
  "output a WARNING level message to the logger"
  [& data]
  (log/warn (apply str (interpose " " data))))

(defn error
  "output an ERROR level message to the logger"
  [& data]
  (log/error (apply str (interpose " " data))))
