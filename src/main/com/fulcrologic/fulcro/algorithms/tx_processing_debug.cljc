(ns com.fulcrologic.fulcro.algorithms.tx-processing-debug
  "helper(s) function for debugging tx processing.  Uses pprint, which adds
  a lot to build size, so it is in a separate ns to keep it out of prod builds."
  (:require
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [clojure.string :as str]
    [clojure.pprint :refer [pprint]]))

(defn tx-status!
  "Debugging function. Shows the current transaction queues with a summary of their content."
  [{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app}]
  (let [{:keys [::txn/submission-queue ::txn/active-queue ::txn/send-queues]} @runtime-atom
        strks    (fn [n & ks]
                   (str/join "," (map (fn [k] (str (some-> k name) " " (get n k))) ks)))
        prtxnode (fn [n]
                   (println (strks n ::txn/id ::txn/tx))
                   (doseq [{:keys [::txn/idx ::txn/results ::txn/dispatch] :as ele} (::elements n)]
                     (println "  Element " idx)
                     (println "  " (strks ele ::started? ::complete? ::original-ast-node))
                     (println "  Dispatch: " (with-out-str (pprint dispatch)))
                     (println "  Results: " (with-out-str (pprint results)))))
        prsend   (fn [s]
                   (println "NODE:")
                   (println "  " (strks s ::ast ::active?)))]
    (println "================================================================================")
    (println "Submission Queue:")
    (doseq [n submission-queue]
      (prtxnode n))
    (println "Active Queue:")
    (doseq [n active-queue]
      (prtxnode n))
    (println "Send Queues:")
    (doseq [k (keys send-queues)]
      (println k " Send Queue:")
      (doseq [n (get send-queues k)]
        (prsend n)))
    (println "================================================================================")))
