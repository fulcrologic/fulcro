(ns com.fulcrologic.fulcro.algorithms.application-helpers
  (:require
    [taoensso.timbre :as log]))

(defn- set-algorithm [app k v]
  (assoc-in app [:com.fulcrologic.fulcro.application/algorithms k] v))

(defn app-algorithm
  ([app]
   (get app :com.fulcrologic.fulcro.application/algorithms))
  ([app k]
   (if-let [nm (cond
                 (or (keyword? k) (symbol? k)) (keyword "algorithm" (name k))
                 (string? k) (keyword "algorithm" k))]
     (get-in app [:com.fulcrologic.fulcro.application/algorithms nm]
       (fn [& any]
         (throw (ex-info "Missing algorithm: " {:name nm})))))))
