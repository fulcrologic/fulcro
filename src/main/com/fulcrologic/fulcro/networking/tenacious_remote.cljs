(ns com.fulcrologic.fulcro.networking.tenacious-remote
  "A wrapper for remotes that adds automatic retry with exponential back-off behavior. This makes the remote more
   tolerant of flaky network communications, and is a simple wrapper that should work with any remote implementation.
   NOTE: Fulcro websockets already has automatic retry support which works a bit differently than this one. Be careful
   that you understand what you are doing if you configure them both at the same time.

   See `com.fulcrologic.fulcro.offline` for features that help support true offline modes of operation."
  (:require
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [taoensso.timbre :as log]))

(defn- backoff-time [n max-delay]
  (let [n  (min 100 (or n 1))
        mx (or max-delay 30000)]
    (min mx (* n n 1000))))

(defn- tenacious-transmit [transmit! {:keys [network-error? max-attempts max-delay]}]
  (let [network-error? (or network-error? (fn [r] (= 500 (:status-code r))))]
    (fn send* [app {::keys     [attempt]
                    ::txn/keys [result-handler] :as send-node}]
      (let [retry!      (fn [result]
                          (let [attempt (inc attempt)]
                            (if (< attempt (or max-attempts 3))
                              (let [tm (backoff-time attempt max-delay)]
                                (log/debug "Remote communication attempt" attempt "failed. Waiting" tm "ms before retry.")
                                (js/setTimeout #(send* app (assoc send-node ::attempt attempt)) tm))
                              (do
                                (log/warn "Tenacious remote exceeded retry limit" max-attempts "See https://book.fulcrologic.com/#warn-remote-retry-limit-exceeded")
                                (result-handler result)))))
            handler     (fn [result]
                          (if (network-error? result)
                            (retry! result)
                            (result-handler result)))
            custom-send (assoc send-node ::txn/result-handler handler)]
        (transmit! app custom-send)))))

(defn tenacious-remote
  "Wrap a Fulcro remote so that attempted communication that fails with a network error will be retried according
   to the configured options.

   * `real-remote` - The real remote that is used for network communication.
   * `options` - A map of configuration options which can contains:
   ** `:network-error?` - A `(fn [result] boolean?)` that can indicate when the error is something that appears to be
      a low-level network error. Only network errors are retried. The default will work for Fulcro http-remote, but
       you must define this if you use an alternate remote type.
   ** `:max-attempts` - The number of times the request will be retried due to network failures. The default is 3.
   ** `:max-delay` - Retries use quadratic back-off on retries to prevent flooding your servers with requests when
       a server outage is the cause of the retries (1s, 4s, 9s, 16s, 25s, etc.).
       By default the maximum retry delay is 30000ms (30 seconds). Use this option to change the max delay between retries.

   Returns the same top-level map that the wrapped remote would have returned, with `:transmit!` wrapped with retry
   behavior. This means any other top-level keys that would have normally come from your remote will still be at the
   top-level of the map.

   In order words:

   (tenacious-remote (http-remote ...) {})

   is roughly equivalent to `(update (http-remote ...) :transmit! ...)`."
  [real-remote options]
  (let [real-transmit! (:transmit! real-remote)]
    (assoc real-remote :transmit! (tenacious-transmit real-transmit! options))))
