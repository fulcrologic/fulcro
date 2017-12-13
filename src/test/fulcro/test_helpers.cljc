(ns fulcro.test-helpers
  (:require [clojure.walk :as walk]))

(defn expand-meta [f]
  (walk/postwalk
    (fn [x]
      (if (meta x)
        {::source x ::meta (meta x)}
        x))
    f))
