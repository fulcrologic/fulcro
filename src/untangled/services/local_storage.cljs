(ns untangled.services.local-storage
  (:require
    [cljs-uuid-utils.core :as uuid]
    [cljs.reader :as r]
    )
  )

(defrecord LocalStorageIO
  [async-report simulated-delay simulated-timeout]
  untangled.services.asyncio/AsyncIo
  (save [this uri goodfn errorfn data]
    (let [id (if (:id data) (:id data) (str (uuid/uuid-string (uuid/make-random-uuid))))
          data-with-id (assoc data :id id)
          str-current (.getItem js/localStorage uri)
          current-data (if str-current (r/read-string str-current) [])
          item-removed-data (remove #(= id {:id %}) current-data)
          updated-data (conj item-removed-data data-with-id)]
      (.setItem js/localStorage uri (pr-str updated-data))
      (if (> simulated-delay 0)
        (js/setTimeout (fn [] (goodfn data-with-id)) simulated-delay)
        (goodfn data-with-id))
      (if (> simulated-timeout 0)
        (js/setTimeout (fn [] (errorfn {:error :timeout :data data})) simulated-timeout))
      )
    )
  (delete [this uri goodfn errorfn id]
    (let [current-data (r/read-string (.getItem js/localStorage uri))
          data (first (filter #(= id (:id %)) current-data))
          ]
      (if (nil? data)
        (errorfn {:error :not-found :id id})
        (let [updated-data (remove #(= id (:id %)) current-data)]
          (.setItem js/localStorage uri (pr-str updated-data))
          (goodfn id)                                                           ; TODO need to add in simulated async
          )
        )
      )
    )
  (fetch [this uri goodfn errorfn id]
    (let [current-data (r/read-string (.getItem js/localStorage uri))
          data (first (filter #(= id (:id %)) current-data))
          ]
      (if (nil? data) (errorfn {:error :not-found :id id}) (goodfn data))       ; TODO need to add in simulated async
      )
    )
  (query [this uri goodfn errorfn]
    (let [current-str (.getItem js/localStorage uri)
          current-data (if (nil? current-str) [] (r/read-string current-str))
          ]
      (goodfn current-data)                                                     ; TODO need to add in simulated async
      )
    )
  )


(defn new-local-storage
  "Create a new local storage async io component:
  - async-report component to report async request processing information
  - simulated-delay time in milliseconds
  "
  [async-report simulated-delay simulated-timeout]
  (let [localio (map->LocalStorageIO {:async-report      async-report
                                      :simulated-delay   simulated-delay
                                      :simulated-timeout simulated-timeout
                                      })]

    (.clear js/localStorage)
    localio
    )
  )
