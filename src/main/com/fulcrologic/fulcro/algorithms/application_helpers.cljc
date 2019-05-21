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

(defn with-tx
  "Replace the transaction submission logic.
  "
  [app tx!]
  (set-algorithm app :algorithm/tx! tx!))

(defn with-optimized-render
  "Replace the render refresh algorithm on an app. Returns a new app:

  ```
  (defonce app (atom nil))

  (reset! app (fulcro-app))

  ;; later
  (swap! app with-render my-render!)

  ;; OR at beginning
  (defonce app (with-render (fulcro-app) my-render))
  ```
  "
  [app render!]
  (set-algorithm app :algorithm/optimized-render! render!))
