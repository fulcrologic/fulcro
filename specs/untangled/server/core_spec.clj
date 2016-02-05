(ns untangled.server.core-spec
  (:require [untangled.server.core :as core]
            [untangled-spec.core :refer [specification behavior provided component assertions]]))

(specification "transitive join"
  (behavior "Creates a map a->c from a->b combined with b->c"
    (assertions
      (core/transitive-join {:a :b} {:b :c}) => {:a :c})))

(specification "make-untangled-server"
  (assertions
    "requires :parser as a parameter, and that parser be a function"
    (core/make-untangled-server) =throws=> (AssertionError #"")
    (core/make-untangled-server :parser 'cymbal) =throws=> (AssertionError #"")
    "requires that :components be a map"
    (core/make-untangled-server :parser #() :components [1 2 3]) =throws=> (AssertionError #"")
    "throws an exception if injections are not keywords"
    (core/make-untangled-server :parser #() :parser-injections [:a :x 'sym]) =throws=> (AssertionError #"")))
