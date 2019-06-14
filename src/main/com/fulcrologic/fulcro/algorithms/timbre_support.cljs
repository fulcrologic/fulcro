(ns com.fulcrologic.fulcro.algorithms.timbre-support
  "
  Logging helpers to make js console logging more readable. The recommended use of these functions is as follows:

  - Make sure you're using Binaryage devtools (on classpath. shadow-cljs will auto-add it when detected).
  - IMPORTANT: Enable custom formatters in console settings for Chrome. This will print cljs data as cljs (instead of raw js).
  - Make a development preload cljs file, and tell shadow-cljs to preload it.
  - In the preload file, add something like this:

  ```
  (ns app.development-preload
    (:require
      [taoensso.timbre :as log]
      [com.fulcrologic.fulcro.algorithms.timbre-support :refer [console-appender prefix-output-fn]))

  (log/set-level! :debug)
  (log/merge-config! {:output-fn prefix-output-fn
                      :appenders {:console (console-appender)}})
  ```

  and you'll get much more readable error messages in the js console.

  NOTE: when logging errors, be sure to log the exception first. This is documented in timbre, but easy to miss:

  ```
  (try
    ...
    (catch :default ex
      (log/error ex ...))
  ```

  See the development_preload.cljs and shadow-cljs.edn files in the latest Fulcro 3 template for an example.
  "
(:require
  [clojure.string :as str]))

;; Taken mostly from timbre itself.  Modified to output better results from ex-info exceptions (e.g. to improve expound
;; experience)
(defn console-appender
  "Returns a js/console appender for ClojureScript. This appender uses the normal output-fn to generate the main
  message, but it also does raw output of the original logging args so that devtools can format data structures.

  Furthermore, if it detects an ExceptionInfo it will print the `ex-message` *after* so that you can see the real
  message of the exception last in the console.  This is particularly handy when using specs and expound with
  spec instrumentation.

  For accurate line numbers in Chrome, add these Blackbox[1] patterns:
    `/taoensso/timbre/appenders/core\\.js$`
    `/taoensso/timbre\\.js$`
    `/cljs/core\\.js$`

  [1] Ref. https://goo.gl/ZejSvR"
  [& [opts]]
  {:enabled?   true
   :async?     false
   :min-level  nil
   :rate-limit nil
   :output-fn  :inherit
   :fn         (if (exists? js/console)
                 (let [;; Don't cache this; some libs dynamically replace js/console
                       level->logger
                       (fn [level]
                         (or
                           (case level
                             :trace js/console.trace
                             :debug js/console.debug
                             :info js/console.info
                             :warn js/console.warn
                             :error js/console.error
                             :fatal js/console.error
                             :report js/console.info)
                           js/console.log))]

                   (fn [{:keys [level vargs ?err output-fn] :as data}]
                     (when-let [logger (level->logger level)]
                       (let [output (when output-fn (output-fn (assoc data :msg_ "" :?err nil)))
                             args   (if-let [err ?err]
                                      (cons output (cons err vargs))
                                      (cons output vargs))]
                         (.apply logger js/console (into-array args))
                         (when (instance? ExceptionInfo ?err)
                           (js/console.log (ex-message ?err)))))))
                 (fn [data] nil))})

(defn prefix-output-fn
  "Mostly taken from timbre, but just formats message prefix as output (e.g. only location/line/level). Use with the
   console appender from this namespace to get better logging output in cljs."
  ([data] (prefix-output-fn nil data))
  ([opts data]                                              ; For partials
   (let [{:keys [level ?ns-str ?file ?line]} data]
     (str (str/upper-case (name level)) " " "[" (or ?ns-str ?file "?") ":" (or ?line "?") "] - "))))


