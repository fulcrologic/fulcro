(ns fulcro.client.logging
  #?(:clj
     (:require [taoensso.timbre :as timbre]))
  #?(:cljs (:require
             [goog.log :as glog]
             [goog.debug.Logger.Level :as level]))
  #?(:cljs (:import [goog.debug Console])))

#?(:clj (def ^:dynamic *logger* (fn [this & args] (apply println args)))
   :cljs
        (defonce *logger*
          (when ^boolean goog.DEBUG
            (.setCapturing (Console.) true)
            (glog/getLogger "fulcro.client"))))

#?(:cljs
        (defn set-level [log-level]
          "Takes a keyword (:all, :debug, :info, :warn, :error, :none) and changes the log level accordingly.
          Note that the log levels are listed from least restrictive level to most restrictive."
          (.setLevel *logger*
            (level/getPredefinedLevel
              (case log-level :all "ALL" :debug "FINE" :info "INFO" :warn "WARNING" :error "SEVERE" :none "OFF"))))
   :clj (defn set-level [l] (timbre/set-level! l)))

#?(:cljs
   (defn value-message
     "Include a pretty-printed cljs value as a string with the given text message."
     [msg val]
     (str msg ":\n" (with-out-str (pr-str val))))
   :clj
   (defn value-message [msg val] (str msg val)))


#?(:cljs
        (defn debug
          "Print a debug message to the logger which includes a value.
          Returns the value (like identity) so it can be harmlessly nested in expressions."
          ([value] (glog/fine *logger* (value-message "DEBUG" value)) value)
          ([msg value] (glog/fine *logger* (value-message msg value)) value))
   :clj (defn debug
          ([v] (timbre/debug v))
          ([m v] (timbre/debug m v))))

#?(:cljs
   (defn info
     "output an INFO level message to the logger"
     [& data]
     (glog/info *logger* (apply str (interpose " " data))))
   :clj
   (defn info [& data]
     (timbre/info (apply str (interpose " " data)))))

#?(:cljs
        (defn warn
          "output a WARNING level message to the logger"
          [& data]
          (glog/warning *logger* (apply str (interpose " " data))))
   :clj (defn warn
          "output a WARNING level message to the logger"
          [& data]
          (timbre/warn (apply str (interpose " " data)))))

#?(:cljs
        (defn error
          "output an ERROR level message to the logger"
          [& data]
          (glog/error *logger* (apply str (interpose " " data))))
   :clj (defn error [& data] (timbre/error (apply str (interpose " " data)))))


