(ns untangled.server.core-spec
  (:require
    [clojure.test :as t]
    [untangled.server.core :as core]
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
    (core/make-untangled-server :parser #() :parser-injections [:a :x 'sym]) =throws=> (AssertionError #""))
  (let [wrap-test (fn [x] (fn [h] (fn [req] (assoc (h req) :test x))))]
    (assertions
      "accepts pre and post middleware seqs"
      (-> (core/make-untangled-server :parser #()
            :middleware {:pre [(wrap-test 1) (wrap-test 2)]
                         :fallback [(wrap-test true) (wrap-test false)]})
        :handler)
      =fn=> (fn [{:keys [pre-hook fallback-hook]}]
              (t/is (= 1 (:test ((@pre-hook identity) {}))))
              (t/is (= true (:test ((@fallback-hook identity) {}))))
              true))))
