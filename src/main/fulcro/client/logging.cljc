(ns fulcro.client.logging
  #?(:cljs (:require
             [goog.log :as glog]
             [goog.debug.Logger.Level :as level]))
  #?(:cljs (:import [goog.debug Console])))

(def logging-priority {:all 100 :debug 5 :info 4 :warn 3 :error 2 :fatal 1 :none 0})
(defonce current-logging-level (atom 0))
(defonce logger (atom (fn [level message & args]
                        (when (< (get logging-priority @current-logging-level 0) (get logging-priority level 0))
                          (apply println message args)))))

(defn set-level [log-level]
  "Takes a keyword (:all, :debug, :info, :warn, :error, :fatal, :none) and changes the log level accordingly.
  Note that the log levels are listed from least restrictive level to most restrictive. "
  (reset! current-logging-level (get logging-priority log-level 2)))

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
  ([value] (debug "DEBUG" value))
  ([msg value]
   (when @logger
     (@logger :debug (value-message msg value))) value))

(defn info
  "output an INFO level message to the logger"
  [& data]
  (when @logger
    (apply @logger :info data)))

(defn warn
  "output a WARNING level message to the logger"
  [& data]
  (when @logger
    (apply @logger :warn data)))

(defn error
  "output an ERROR level message to the logger"
  [& data]
  (when @logger
    (apply @logger :error data)))

(defn fatal
  "output an FATAL level message to the logger"
  [& data]
  (when @logger
    (apply @logger :fatal data)))
