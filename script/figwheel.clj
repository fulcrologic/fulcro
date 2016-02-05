(require '[clj.user :refer [start-figwheel]])

(def props (System/getProperties))

(start-figwheel
  (cond-> []
    (contains? props "test") (conj "test")))
