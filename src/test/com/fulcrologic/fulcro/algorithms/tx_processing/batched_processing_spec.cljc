(ns com.fulcrologic.fulcro.algorithms.tx-processing.batched-processing-spec
  (:require
    [com.fulcrologic.fulcro.algorithms.tempid :refer [uuid]]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.algorithms.tx-processing.batched-processing :as bp]
    [com.fulcrologic.fulcro.application :refer [fulcro-app]]
    [edn-query-language.core :as eql]
    [fulcro-spec.core :refer [assertions component specification]]))

(defn mock-app [] (fulcro-app {:optimized-render! identity}))

(defn ->read-send
  "Creates a read-only send node (non-mutation) suitable for batching.
   All nodes share the same `tx-id` so they group together in `batch-sends`."
  [tx-id idx query result-handler-atom]
  {::txn/id             (uuid tx-id)
   ::txn/idx            idx
   ::txn/ast            (eql/query->ast query)
   ::txn/result-handler (fn [result] (reset! result-handler-atom result))
   ::txn/update-handler (fn [result] )
   ::txn/active?        false
   ::txn/options        {}})

(specification "batch-sends result-handler with non-sequence body (issue #580)"
  (component "when server returns a map body (e.g. error response) instead of a sequence"
    (let [app            (mock-app)
          result-a       (atom nil)
          result-b       (atom nil)
          send-queue     [(->read-send 1 0 [:user/name] result-a)
                          (->read-send 1 1 [:user/email] result-b)]
          {:keys [::txn/send-node]} (binding [bp/*remove-send* (fn [& _])]
                                      (bp/batch-sends app :remote send-queue))
          result-handler (::txn/result-handler send-node)
          error-response {:status-code 404
                          :body        {:error "Not found"}}]

      (result-handler error-response)

      (assertions
        "calls the first handler"
        (some? @result-a) => true
        "calls the second handler"
        (some? @result-b) => true
        "passes the full error body to all handlers"
        (:body @result-a) => {:error "Not found"}
        (:body @result-b) => {:error "Not found"}
        "preserves the status code for all handlers"
        (:status-code @result-a) => 404
        (:status-code @result-b) => 404))))
