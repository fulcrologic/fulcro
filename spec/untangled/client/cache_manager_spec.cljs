(ns untangled.client.cache-manager-spec
  [:require [untangled.client.cache-manager :as cm]]
  (:require-macros
    [untangled-spec.core :refer [specification behavior provided assertions]]
    [cljs.test :refer [is]]))

(specification "Cache entry"
  (let [initial-time 0
        now          1
        duration     5
        cache-entry  (cm/map->CacheEntry
                       {:time-last-refreshed initial-time
                        :seconds-valid       duration})]

    (behavior "can be instantiated"
      (is (not (nil? cache-entry))))

    (behavior "`touch` : updates last-refreshed"
      (let [t0      (:time-last-refreshed cache-entry)
            touched (cm/touch cache-entry now)
            t1      (:time-last-refreshed touched)]
        (is (< t0 t1))))

    (behavior "`stale?` : checks if data needs to be refreshed"
      (let [expiry (+ (:time-last-refreshed cache-entry)
                     (:seconds-valid cache-entry))]
        (is (not (cm/stale? cache-entry now)))
        (is (cm/stale? cache-entry (inc expiry)))))

    (behavior "`refresh`"
      (let [call-counter (atom 0)
            refresh-fn   (fn [cache-entry]
                           (swap! call-counter inc))
            ce           (assoc cache-entry :refresh-fn refresh-fn)
            ts           5]


        (provided "does not apply `refresh-fn` when `enable-refresh` is `false`"
          (cm/enable-refresh? ce) => false
          (cm/refresh ce ts)
          (is (= @call-counter 0)))

        (provided "applies refresh-fn when `enable-refresh` is `true`"
          (cm/enable-refresh? ce) => true
          (cm/refresh ce ts)
          (is (= @call-counter 1)))))

    (behavior "`enable-refresh?` : calls `is-refresh-enabled-fn` and returns its value"
      (let [call-counter          (atom 0)
            expected              true
            is-refresh-enabled-fn (fn [cache-entry]
                                    (swap! call-counter inc)
                                    expected)
            cm                    (assoc cache-entry :is-refresh-enabled-fn is-refresh-enabled-fn)]
        (is (= (cm/enable-refresh? cm) expected))
        (is (= @call-counter 1))))))

(specification "Cache manager"
  (let [manager (cm/map->CacheManager
                  {:cache-entries {}})]

    (behavior "`add` : can add a cache entry"
      (let [entry :really-an-entry-really
            mgr   (cm/add manager :the-key entry)]
        (is (= (:the-key (:cache-entries mgr))
              entry))))

    (behavior "`should-update?` :"
      (let [time-last-checked        10
            debounce-freq-in-seconds 5
            mgr                      (assoc manager :time-last-checked time-last-checked
                                                    :debounce-freq-in-seconds debounce-freq-in-seconds)
            now                      (+ time-last-checked debounce-freq-in-seconds 1)
            one-sec-ago              (dec now)]
        (behavior "with time beyond `debounce-freq-in-seconds`"
          (is (cm/should-update? mgr now)))
        (behavior "with time before `debounce-freq-in-seconds`"
          (is (not (cm/should-update? mgr one-sec-ago))))))

    (behavior "`refresh-entries` :"
      (let [call-counter (atom 0)
            refresh-fn   (fn [_] (swap! call-counter inc))
            e1           (cm/map->CacheEntry {:refresh-fn refresh-fn})
            e2           (cm/map->CacheEntry {:refresh-fn refresh-fn})
            mgr          (-> manager
                           (cm/add :e1 e1)
                           (cm/add :e2 e2))
            ts           0]
        (provided "with stale true, calls refresh-fn"
          (cm/stale? _ _) => true
          (cm/enable-refresh? _) => true
          (cm/should-update? _ _) => true
          (cm/refresh-entries mgr ts)
          (is (= @call-counter 2))))

      (let [call-counter (atom 0)
            refresh-fn   (fn [_] (swap! call-counter inc))
            e1           (cm/map->CacheEntry {:refresh-fn refresh-fn})
            e2           (cm/map->CacheEntry {:refresh-fn refresh-fn})
            mgr          (-> manager
                           (cm/add :e1 e1)
                           (cm/add :e2 e2))
            ts           0]
        (provided "with stale false, does not call refresh-fn"
          (cm/stale? _ _) => false
          (cm/should-update? _ _) => true
          (cm/refresh-entries mgr ts)
          (is (= @call-counter 0))))

      (let [call-counter (atom 0)
            refresh-fn   (fn [_] (swap! call-counter inc))
            e1           (cm/map->CacheEntry
                           {:refresh-fn            refresh-fn
                            :is-refresh-enabled-fn (constantly true)})
            e2           (cm/map->CacheEntry
                           {:refresh-fn            refresh-fn
                            :is-refresh-enabled-fn (constantly false)})
            mgr          (-> manager
                           (cm/add :e1 e1)
                           (cm/add :e2 e2))
            ts           0]
        (provided "respects mixed `is-refresh-enabled-fn`"
          (cm/stale? _ _) => true
          (cm/should-update? _ _) => true
          (cm/refresh-entries mgr ts)
          (is (= @call-counter 1))))

      (let [call-counter (atom 0)
            refresh-fn   (fn [_] (swap! call-counter inc))
            e1           (cm/map->CacheEntry
                           {:refresh-fn            refresh-fn
                            :is-refresh-enabled-fn (constantly true)
                            :key                   :e1})
            e2           (cm/map->CacheEntry
                           {:refresh-fn            refresh-fn
                            :is-refresh-enabled-fn (constantly true)
                            :key                   :e2})
            mgr          (-> manager
                           (cm/add :e1 e1)
                           (cm/add :e2 e2))
            ts           0]
        (provided "respects mixed `stale?` settings"
          (cm/stale? the-entry _) => (= the-entry e1)
          (cm/should-update? _ _) => true
          (cm/refresh-entries mgr ts)
          (is (= @call-counter 1)))))))

