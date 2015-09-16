(ns untangled.services.local-storage
  (:require
    [cljs-uuid-utils.core :as uuid]
    [cljs.reader :as r]
    )
  )

(defn similate-delay [simulated-delay async-fn]
  (cond (= simulated-delay 0) (async-fn)
        :otherwise (js/setTimeout async-fn simulated-delay)
        )
  )

(defrecord LocalStorageIO
  [async-report simulated-delay]
  untangled.services.asyncio/AsyncIo
  (save [this uri goodfn errorfn data]
    (let [id (if (:id data) (:id data) (str (uuid/uuid-string (uuid/make-random-uuid))))
          data-with-id (assoc data :id id)
          str-current (.getItem js/localStorage uri)
          current-data (if str-current (r/read-string str-current) [])
          item-removed-data (remove #(= id {:id %}) current-data)
          updated-data (conj item-removed-data data-with-id)]
      (similate-delay simulated-delay
                      (fn [] (do (.setItem js/localStorage uri (pr-str updated-data))
                                 (goodfn data-with-id))))
      )
    )
  (delete [this uri goodfn errorfn id]
    (let [current-data (r/read-string (.getItem js/localStorage uri))
          data (first (filter #(= id (:id %)) current-data))
          ]
      (if (nil? data)
        (errorfn {:error :not-found :id id})
        (let [updated-data (remove #(= id (:id %)) current-data)]
          (similate-delay simulated-delay
                          (fn [] (do (.setItem js/localStorage uri (pr-str updated-data))
                                     (goodfn id))))
          )
        )
      )
    )

  (fetch [this uri goodfn errorfn id]
    (let [current-str (.getItem js/localStorage uri)
          current-data (if (nil? current-str) []  (r/read-string current-str))
          data (first (filter #(= id (:id %)) current-data))
          ]
      (if (nil? data)
        (errorfn {:error :not-found :id id})
        (similate-delay simulated-delay
                        (fn [] (goodfn data)))
        )
      )
    )

  (query [this uri goodfn errorfn]
    (let [current-str (.getItem js/localStorage uri)
          current-data (if (nil? current-str) [] (r/read-string current-str))
          ]
      (similate-delay simulated-delay
                      (fn [] (goodfn current-data)))
      )
    )
  )

(defn new-local-storage
  "Create a new local storage async io component:
  - async-report component to report async request processing information
  - simulated-delay time in milliseconds
  "
  [async-report simulated-delay]
  (let [localio (map->LocalStorageIO {:async-report    async-report
                                      :simulated-delay simulated-delay
                                      })]

    (.clear js/localStorage)
    localio
    )
  )
