(ns untangled.server.impl.util-spec
  (:require [untangled-spec.core :refer [specification behavior assertions]]
            [untangled.server.impl.util :as n]
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

(specification "strip-parameters"
  (behavior "removes all parameters from"
    (assertions
      "parameterized prop reads"
      (n/strip-parameters `[(:some/key {:arg :foo})]) => [:some/key]

      "parameterized join reads"
      (n/strip-parameters `[({:some/key [:sub/key]} {:arg :foo})]) => [{:some/key [:sub/key]}]

      "nested parameterized join reads"
      (n/strip-parameters
        `[{:some/key [({:sub/key [:sub.sub/key]} {:arg :foo})]}]) => [{:some/key [{:sub/key [:sub.sub/key]}]}]

      "multiple parameterized reads"
      (n/strip-parameters
        `[(:some/key {:arg :foo})
          :another/key
          {:non-parameterized [:join]}
          {:some/other [{:nested [(:parameterized {:join :just-for-fun})]}]}])
      =>
      [:some/key :another/key {:non-parameterized [:join]} {:some/other [{:nested [:parameterized]}]}]

      "parameterized mutations"
      (n/strip-parameters ['(fire-missiles! {:arg :foo})]) => '[fire-missiles!]

      "multiple parameterized mutations"
      (n/strip-parameters ['(fire-missiles! {:arg :foo})
                           '(walk-the-plank! {:right :now})]) => '[fire-missiles! walk-the-plank!])))

