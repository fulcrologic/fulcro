(ns untangled.util.logging
  (:require
    [taoensso.timbre :as t]
    )
  )

(defn fatal
  "
  A mock-able wrapper for the timbre logging library. This helps us verify that certain critical logging messages
  are emitted within our unit tests

  Parameters
  * `msgs` An arbitrary vector of logging messages that should be printed after timbre's default fatal line

  Returns a call to taoensso.timbre/fatal with our custom messages.
  "

  [& msgs] (t/fatal msgs))
