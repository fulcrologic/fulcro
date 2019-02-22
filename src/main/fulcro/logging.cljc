(ns fulcro.logging
  "Utilities for logging on the client and server.

  This namespace exists so that fulcro libraries have a mechanism to do logging that can be lightweight without
  pull in any additional dependencies. You can use this logging support, but it is not designed to be great, just
  lightweight and efficient.

  It is highly recommended that you use a dedicated logging library in production apps, such as timbre. However, if
  you're writing an add-on for Fulcro it is probably best to use this logging so you give our users a consistent
  way to deal with Fulcro logging."
  #?(:cljs (:require-macros fulcro.logging))
  (:require
    [clojure.string :as str]
    [clojure.stacktrace :as strace]
    #?@(:cljs ([goog.log :as glog]
                [goog.object :as gobj]
                [goog.debug.Logger.Level :as level])))
  #?(:cljs (:import [goog.debug Console])))

(def logging-priority {:all 100 :trace 6 :debug 5 :info 4 :warn 3 :error 2 :fatal 1 :none 0})

(defn should-log?
  "Returns true if the current logging level indicates that the message level is of interest."
  [current-logging-level message-level]
  (let [c (or current-logging-level 4)
        m (get logging-priority message-level 4)]
    (<= m c)))

(defonce ^:private current-logging-level
  (atom 0))

#?(:cljs
   (def level-map
     (let [levels  [:all :trace :debug :info :error :warn :fatal]
           glevels ["ALL" "FINE" "FINE" "INFO" "SEVERE" "WARNING" "SEVERE"]]
       (zipmap levels (map #(level/getPredefinedLevel %) glevels)))))

(defonce ^:private logger
  (do
    #?(:cljs
       (when ^boolean goog.DEBUG
         (.setCapturing (Console.) true)))
    (atom (fn built-in-logger [{:keys [file line]} level & args]
            (when (should-log? @current-logging-level level)
              (let [location (str (or file "?") ":" (or line "?"))]
                #?(:cljs (let [logger          (glog/getLogger file (level/getPredefinedLevel "ALL"))
                               first-exception (first (filter #(instance? js/Error %) args))
                               message         (str/join " " args)
                               glevel          (get level-map level (:info level-map))]
                           (when logger
                             (.log logger glevel message first-exception)))
                   :clj  (println (str (-> level name str/upper-case) " [" location "] : " (str/join " " args))))))))))

#?(:clj
   (defmacro system-log-level
     "Get the current runtime JVM system logging level from fulcro.logging. Works for clj and cljs."
     []
     (some->
       (System/getProperty "fulcro.logging")
       keyword)))

(defn -log
  "Private implementation for macro output. Use `log` instead."
  [location level & things-to-print]
  (when @logger
    (apply @logger location level things-to-print)))

(defn- log* [])

(defn fline [and-form] (some-> and-form meta :line))

#?(:clj
   (defmacro log
     "Logs to the current global logging function, which can be set in the top-level logger atom.

     If you set the JVM option to something like -Dfulcro.logging=error then this macro will not output
     any code for logging below that level.

     level - one of :trace, :debug, :info, :warn, :error, or :fatal.
     things-to-include - Any number of additional things to log. The logging function may give special treatment
     to certain types (e.g. exceptions).

     See `set-logger!`."
     [logging-level & things-to-include]
     (let [l            (system-log-level)
           has-options? (map? logging-level)
           level        (if has-options? (:level logging-level) logging-level)
           elide?       (and l (not (should-log? l level)))]
       (when-not elide?
         (let [file     (or (some-> &env :ns :name str) (some-> *ns* ns-name str) "?")
               line     (or (and has-options? (:line logging-level)) (fline &form) "?")
               js?      (some-> &env :ns :name)
               location {:file file :line line}]
           `(try
              (fulcro.logging/-log ~location ~level ~@things-to-include)
              (catch ~(if js? 'js/Error 'Exception) e#
                (fulcro.logging/-log ~location ~level "Log statement failed (arguments did not evaluate)." e#))))))))

(defn set-level! [log-level]
  "Takes a keyword (:all, :trace, :debug, :info, :warn, :error, :fatal, :none) and changes the runtime log level accordingly.
  Note that the log levels are listed from least restrictive level to most restrictive.
  The system property fulcro.logging can be set on the JVM to cause elision of logging statements from code."
  (let [new-level (get logging-priority log-level 2)]
    (reset! current-logging-level new-level)))

(defn set-logger!
  "Set the fulcro logging function.

  log-fn - A (fn [{:keys [file line] :as location} level & args] ...)"
  [log-fn]
  (reset! logger log-fn))

(defmacro trace [& args] `(log {:line ~(fline &form) :level :trace} ~@args))
(defmacro debug [& args] `(log {:line ~(fline &form) :level :debug} ~@args))
(defmacro info [& args] `(log {:line ~(fline &form) :level :info} ~@args))
(defmacro warn [& args] `(log {:line ~(fline &form) :level :warn} ~@args))
(defmacro error [& args] `(log {:line ~(fline &form) :level :error} ~@args))
(defmacro fatal [& args] `(log {:line ~(fline &form) :level :fatal} ~@args))
