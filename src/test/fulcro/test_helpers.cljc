(ns fulcro.test-helpers
  (:require [clojure.walk :as walk]))

(defn expand-meta [f]
  (walk/postwalk
    (fn [x]
      ; calls have metadata with line/column numbers in them in clj...ignore those
      (if (and (meta x) (not= #{:line :column} (-> x meta keys set)))
        {::source x ::meta (meta x)}
        x))
    f))
