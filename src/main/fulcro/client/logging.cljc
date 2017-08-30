(ns fulcro.client.logging
  #?(:cljs (:require cljs.pprint
             [om.next :refer [*logger*]]
             [goog.log :as glog]
             [goog.debug.Logger.Level :as level])))

#?(:cljs
   (defn set-level [log-level]
     "Takes a keyword (:all, :debug, :info, :warn, :error, :none) and changes the log level accordingly.
     Note that the log levels are listed from least restrictive level to most restrictive."
     (.setLevel *logger*
                (level/getPredefinedLevel
                  (case log-level :all "ALL" :debug "FINE" :info "INFO" :warn "WARNING" :error "SEVERE" :none "OFF"))))
   :clj (defn set-level [l] l))

#?(:cljs
   (defn value-message
     "Include a pretty-printed cljs value as a string with the given text message."
     [msg val]
     (str msg ":\n" (with-out-str (cljs.pprint/pprint val))))
   :clj
   (defn value-message [msg val] (str msg val)))


#?(:cljs
        (defn debug
          "Print a debug message to the Om logger which includes a value.
          Returns the value (like identity) so it can be harmlessly nested in expressions."
          ([value] (glog/fine *logger* (value-message "DEBUG" value)) value)
          ([msg value] (glog/fine *logger* (value-message msg value)) value))
   :clj (defn debug
          ([v] (println v))
          ([m v] (println m v))))

#?(:cljs
   (defn info
     "output an INFO level message to the Om logger"
     [& data]
     (glog/info *logger* (apply str (interpose " " data))))
   :clj
   (def info debug))

#?(:cljs
   (defn warn
     "output a WARNING level message to the Om logger"
     [& data]
     (glog/warning *logger* (apply str (interpose " " data))))
   :clj (def warn debug))

#?(:cljs
   (defn error
     "output an ERROR level message to the Om logger"
     [& data]
     (glog/error *logger* (apply str (interpose " " data))))
   :clj (defn error [& data] (apply println data)))
