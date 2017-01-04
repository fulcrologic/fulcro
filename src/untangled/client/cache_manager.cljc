(ns untangled.client.cache-manager)

(defprotocol ICacheEntry
  (touch [this new-timestamp] "Returns new cache entry with `time-last-refreshed`
                               updated to `new-timestamp`.")
  (stale? [this timestamp] "True when `seconds-valid` has been exceeded.")
  (refresh [this timestamp] "Function to refresh the cached data.")
  (enable-refresh? [this] "Boolean indicating whether `refresh` should be called."))

(defrecord
  CacheEntry
  [time-last-refreshed                                      ; Unix epoch timestamp
   seconds-valid
   key
   refresh-fn
   is-refresh-enabled-fn]

  ICacheEntry
  (touch
    [this new-timestamp]
    (assoc this :time-last-refreshed new-timestamp))

  (stale?
    [this from-when]
    (< (+ time-last-refreshed seconds-valid) from-when))

  (refresh
    [this timestamp]
    (if refresh-fn
      (if (enable-refresh? this)
        (let [new-entry (touch this timestamp)]
          (refresh-fn new-entry)
          new-entry)
        this)
      this))

  (enable-refresh?
    [this]
    (when is-refresh-enabled-fn
      (is-refresh-enabled-fn this))))


(defprotocol ICacheManager
  (add [this key cache-entry])
  (refresh-entries [this timestamp])
  (should-update? [this timestamp]))

(defrecord
  CacheManager
  [cache-entries
   time-last-checked
   debounce-freq-in-seconds]

  ICacheManager
  (add
    [this key cache-entry]
    (assoc-in this [:cache-entries key] cache-entry))

  (should-update? [this timestamp]
    (< (+ time-last-checked debounce-freq-in-seconds) timestamp))

  (refresh-entries
    [this current-time]
    (if (should-update? this current-time)
      (let [refresh-entry (fn [entry]
                            (if (stale? entry current-time)
                              (refresh entry current-time)
                              entry))
            new-entries   (->> cache-entries
                            (map (fn [[k entry]]
                                   [k (refresh-entry entry)]))
                            (into {}))]
        (assoc this :cache-entries new-entries))
      this)))
