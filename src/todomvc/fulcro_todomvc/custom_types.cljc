(ns fulcro-todomvc.custom-types
  (:require
    [com.fulcrologic.fulcro.algorithms.transit :as transit]))

(deftype Point [x y])

(defn install! []
  (transit/install-type-handler!
    (transit/type-handler Point "geo/point"
      (fn [^Point p] [(.-x p) (.-y p)])
      (fn [[x y]] (Point. x y)))))
