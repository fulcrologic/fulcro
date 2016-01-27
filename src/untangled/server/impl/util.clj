(ns untangled.server.impl.util
  (:require
    om.tempid
    [clojure.tools.namespace.find :refer [find-namespaces]]
    [clojure.java.classpath :refer [classpath]])
  (:import (om.tempid TempId)))

(defn deep-merge [& xs]
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))

(defn is-om-tempid? [val]
  (instance? TempId val))


