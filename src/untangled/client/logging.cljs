(ns untangled.client.logging
  (:require cljs.pprint
            [om.next :refer [*logger*]]
            [goog.log :as glog]))

; TODO: A function to set logging level

(defn value-message
  "Include a pretty-printed cljs value as a string with the given text message."
  [msg val]
  (str msg ": " (with-out-str (cljs.pprint/pprint val))))

(defn info
  ([msg] (info msg nil))
  ([msg exception] (glog/info *logger* msg exception)))

(defn warn
  ([msg] (warn msg nil))
  ([msg exception] (glog/warning *logger* msg exception)))

(defn debug
  "Print a debug message which includes a value. Returns the value (like identity) so it can be harmlessly nested in expressions."
  ([value] (glog/fine *logger* (value-message "Value" value)) value)
  ([msg value] (glog/fine *logger* (value-message msg value)) value))

(defn error
  ([msg] (error msg nil))
  ([msg exception] (glog/error *logger* msg exception)))
