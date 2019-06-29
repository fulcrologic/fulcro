(ns com.fulcrologic.fulcro.networking.mock-server-remote
  "Simple adapter code that allows you to use a generic parser 'as if' it were a client remote in CLJS."
  (:require
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [taoensso.timbre :as log]
    [edn-query-language.core :as eql]
    [cljs.core.async :as async]))

(defn mock-http-server
  "Create a remote that mocks a Fulcro remote server.

  :parser - A function `(fn [eql-query] async-channel)` that returns a core async channel with the result for the
  given eql-query."
  [{:keys [parser] :as options}]
  (merge options
    {:transmit! (fn transmit! [{:keys [active-requests]} {:keys [::txn/ast ::txn/result-handler ::txn/update-handler] :as send-node}]
                  (let [edn           (eql/ast->query ast)
                        ok-handler    (fn [result]
                                        (try
                                          (result-handler (select-keys result #{:transaction :status-code :body :status-text}))
                                          (catch :default e
                                            (log/error e "Result handler failed with an exception."))))
                        error-handler (fn [error-result]
                                        (try
                                          (result-handler (merge {:status-code 500} (select-keys error-result #{:transaction :status-code :body :status-text})))
                                          (catch :default e
                                            (log/error e "Error handler failed with an exception."))))]
                    (try
                      (async/go
                        (let [result (async/<! (parser edn))]
                          (ok-handler {:transaction edn :status-code 200 :body result})))
                      (catch :default e
                        (error-handler {:transaction edn :status-code 500})))))
     :abort!    (fn abort! [this id])}))
