(ns fulcro.logging-spec
  (:require [fulcro-spec.core :refer [specification behavior provided assertions when-mocking]]
            [clojure.string :as str]
            [fulcro.logging :as log]))

(specification "should-log?"
  (assertions
    "Correctly indicates when to log"
    (log/should-log? :debug :debug) => true
    (log/should-log? :debug :error) => true
    (log/should-log? :debug :info) => true
    (log/should-log? :debug :error) => true
    (log/should-log? :debug :info) => true
    (log/should-log? :error :debug) => false
    (log/should-log? :info :debug) => false
    (log/should-log? :error :debug) => false
    (log/should-log? :info :debug) => false
    "treats an unknown logging level as :info"
    (log/should-log? :unknown :error) => true
    (log/should-log? :unknown :info) => true
    (log/should-log? :unknown :debug) => false))

(specification "The logging macro"
  (behavior "emits code to call the internal language-specific logger."
    (when-mocking
      (log/-log location level & args) => (assertions
                                            "The line is logged"
                                            (:line location) => 36
                                            "The namespace is logged"
                                            (:file location) => "fulcro.logging-spec"
                                            "The correct level is passed"
                                            level => :fatal
                                            "The arguments are passed as a list"
                                            args => ["Hello world" 1 2 3])

      (log/log :fatal "Hello world" 1 2 3))))

(specification "logging helper macros"
  (if (log/should-log? (log/system-log-level) :fatal)
    (when-mocking
      (log/-log n l & args) =1x=> (assertions
                                    "fatal emits a fatal message"
                                    args => ["Message"]
                                    l => :fatal)
      (log/fatal "Message"))
    (behavior "fatal skipped (system log level is eliding those logging statements)" :manual-test))
  (if (log/should-log? (log/system-log-level) :error)
    (when-mocking
      (log/-log n l & args) =1x=> (assertions
                                    "error emits a error message"
                                    args => ["Message"]
                                    l => :error)
      (log/error "Message"))
    (behavior "info skipped (system log level is eliding those logging statements)" :manual-test))
  (if (log/should-log? (log/system-log-level) :info)
    (when-mocking
      (log/-log n l & args) =1x=> (assertions
                                    "info emits a info message"
                                    args => ["Message"]
                                    l => :info)
      (log/info "Message"))
    (behavior "info skipped (system log level is eliding those logging statements)" :manual-test))
  (if (log/should-log? (log/system-log-level) :debug)
    (when-mocking
      (log/-log n l & args) =1x=> (assertions
                                    "debug emits a debug message"
                                    args => ["Message"]
                                    l => :debug)
      (log/debug "Message"))
    (behavior "debug skipped (system log level is eliding those logging statements)" :manual-test))
  (if (log/should-log? (log/system-log-level) :trace)
    (when-mocking
      (log/-log n l & args) =1x=> (assertions
                                    "trace emits a trace message"
                                    args => ["Message"]
                                    l => :trace)
      (log/trace "Message"))
    (behavior "trace skipped (system log level is eliding those logging statements)" :manual-test)))

(specification "Eliding logging statements (run this with -Dfulcro.logging=error)"
  (if (= :error (log/system-log-level))
    (let [logs (atom 0)]
      (when-mocking
        (log/-log n l & args) =2x=> (swap! logs inc)

        (log/log :debug "Hello world" 1 2 3)
        (log/log :info "Hello world" 1 2 3)
        (log/log :fatal "Hello world" 1 2 3)
        (log/log :error "Hello world" 1 2 3)

        (assertions
          "results in the correct number of logging calls"
          @logs => 2)))
    (behavior "SKIPPED" :manual-test)))
