(ns untangled.server.core-spec
  (:require [untangled.server.core :as core]
            [untangled-spec.core :refer [specification behavior provided component assertions]]))

(specification "transitive join"
  (behavior "Creates a map a->c from a->b combined with b->c"
    (assertions
      (core/transitive-join {:a :b} {:b :c}) => {:a :c})))
