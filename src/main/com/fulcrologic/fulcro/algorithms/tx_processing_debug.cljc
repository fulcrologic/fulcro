(ns com.fulcrologic.fulcro.algorithms.tx-processing-debug
  "Helper functions for debugging tx processing.  Uses pprint, which adds
  a lot to build size, so it is in a separate ns to keep it out of prod builds."
  (:require
    [clojure.string :as str]
    [clojure.pprint :refer [pprint]]))

(defn tx-status!
  "Debugging function. Shows the current transaction queues with a summary of their content."
  [location {:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app}]
  (let [{:com.fulcrologic.fulcro.algorithms.tx-processing/keys [submission-queue active-queue send-queues]} @runtime-atom
        strks    (fn [n & ks]
                   (str/join "\n" (map (fn [k] (str k " = " (with-out-str (pprint (get n k))))) ks)))
        prtxnode (fn [n]
                   (println (strks n :com.fulcrologic.fulcro.algorithms.tx-processing/id :com.fulcrologic.fulcro.algorithms.tx-processing/tx))
                   (doseq [{:com.fulcrologic.fulcro.algorithms.tx-processing/keys [idx results dispatch] :as ele}
                           (:com.fulcrologic.fulcro.algorithms.tx-processing/elements n)]
                     (println "  Element " idx)
                     (println "  " (strks ele :com.fulcrologic.fulcro.algorithms.tx-processing/started?
                                     :com.fulcrologic.fulcro.algorithms.tx-processing/complete?
                                     :com.fulcrologic.fulcro.algorithms.tx-processing/original-ast-node))
                     (println "  Dispatch: " (with-out-str (pprint dispatch)))
                     (println "  Results: " (with-out-str (pprint results)))))
        prsend   (fn [s]
                   (println "NODE:")
                   (println "  " (strks s :com.fulcrologic.fulcro.algorithms.tx-processing/ast :com.fulcrologic.fulcro.algorithms.tx-processing/active?)))]
    (println location)
    (println "================================================================================")
    (println "Submission Queue:")
    (doseq [n submission-queue]
      (prtxnode n))
    (println "======")
    (println "Active Queue:")
    (doseq [n active-queue]
      (prtxnode n))
    (println "======")
    (println "Send Queues:")
    (doseq [k (keys send-queues)]
      (println k " Send Queue:")
      (doseq [n (get send-queues k)]
        (prsend n)))
    (println "================================================================================")))
