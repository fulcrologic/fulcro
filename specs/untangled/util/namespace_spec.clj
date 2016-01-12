(ns untangled.util.namespace-spec
  (:require [untangled-spec.core :refer [specification assertions]]
            [untangled.util.namespace :as n]
            [taoensso.timbre :refer [debug info fatal error]]
            [clojure.tools.namespace.find :refer [find-namespaces]]
            [clojure.java.classpath :refer [classpath]]))

(specification "load-namespaces"
  (assertions
    "processes namespaces that start with a given prefix"
    (n/load-namespaces "resources.load-namespaces") =fn=> #(every? (fn [x] (.startsWith (str x) "resources.load-namespace")) %)
    "returns the symbols of namespaces that were loaded"
    (n/load-namespaces "resources.load-namespace") => ['resources.load-namespace.load-namespaces]
    "will not accept a partial package name as a prefix"
    (n/load-namespaces "resources.load-namespac") => []))

