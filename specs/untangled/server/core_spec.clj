(ns untangled.server.core-spec
  (:require
    [clojure.test :as t]
    [untangled.server.core :as core]
    [untangled-spec.core :refer [specification behavior provided component assertions]])
  (:import (clojure.lang ExceptionInfo)))

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
              true)))

  (behavior (str "accepts a seq of libraries for use in xform-ing the params"
              ", are applied in order: left->right libraries + top-level")
    (assertions
      "can wrap the parser :read and :mutate"
      (-> (core/make-untangled-server :parser {:read (constantly "read") :mutate (constantly "mutate")}
            :libraries [{:parser {:reads {::test-read (fn [env k params] {:value ::ok})}
                                  :mutates {'test-mutate (fn [env k params] {:action (constantly ::ok)})}}}])
        :handler :api-parser)
      =fn=> (fn [parser]
              (t/is (= ::ok (::test-read (parser {} [::test-read] nil))))
              (t/is (= ::ok (:result ('test-mutate (parser {} '[(test-mutate)] nil)))))
              true)
      "can add :parser-injections"
      (-> (core/make-untangled-server :parser {}
            :parser-injections #{:foo :bar}
            :libraries [{:parser-injections #{:foo :qux}}])
        :handler :injected-keys)
      => #{:foo :bar :qux}
      "can add :components"
      (-> (core/make-untangled-server :parser {}
            :libraries [{:components {::test-comp {:test :comp}}}])
        ::test-comp)
      => {:test :comp}
      "if they conflict, throws an error"
      (core/make-untangled-server :parser {}
        :components {:bad "old"}
        :libraries [{:components {:bad "new"}}])
      =throws=> (ExceptionInfo #"conflicting component"
                  #(-> % ex-data
                     (= {:failing-library {:components {:bad "new"}}
                         :found "old" , :path :bad, :tried "new"})))
      "can add :extra-routes"
      (-> (core/make-untangled-server :parser {}
            :extra-routes {:routes ["/" {"foo" :foo}]
                           :handlers {:foo (constantly :foo)}}
            :libraries [{:extra-routes {:routes ["/lib1/" {"test" :test}]
                                        :handlers {:test (fn [env match] :test.ok)}}}
                        {:extra-routes {:routes ["/lib2/" {}]
                                        :handlers {}}}])
        :handler :extra-routes)
      =fn=> (fn [extra-routes]
              (t/is (= (map :routes extra-routes)
                       [["/lib1/" {"test" :test}] ["/lib2/" {}] ["/" {"foo" :foo}]]))
              true))))
