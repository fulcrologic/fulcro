(ns untangled.services.async-report)


(defprotocol IAsyncReport
  (started [this m] "started a new request")
  (error [this m] "a request reported an error")
  (completed [this m] "a request has completed")
  (current-async-request-count [this] "returns the current number of outstanding requests")
  )


(defrecord AsyncReport
  [started-fn error-fn completed-fn current-request-count]
  IAsyncReport
  (started [this m] "started a new request")
  (error [this m] "a request reported an error")
  (completed [this m] "a request has completed")
  (current-async-request-count [this] current-request-count)
  )


(defn new-async-report
  "Create a new async reporting component:
  - started-fn a single argument function that is called when an async method has started
  - error-fn
  "
  [started-fn error-fn completed-fn]
  (map->AsyncReport {:started-fn            started-fn
                     :completed-fn          completed-fn
                     :error-fn              error-fn
                     :current-request-count 0
                     }))
