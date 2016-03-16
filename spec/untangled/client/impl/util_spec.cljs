(ns untangled.client.impl.util-spec
  (:require
    [untangled-spec.core :refer-macros [specification when-mocking assertions]]
    [untangled.client.impl.util :as util]))

(specification "Log app state"
  (let [state {:foo        {:a :b
                            12 {:c         ["hello" "world"]
                                [:wee :ha] {:e [{:e :g}
                                                {:a [1 2 3 4]}
                                                {:t :k}]
                                            :g :h
                                            :i :j}}}
               {:map :key} {:other :data}
               [1 2 3]     :data}]

    (when-mocking
      (cljs.pprint/pprint data) => data

      (assertions
        "Handle non-sequential keys"
        (util/log-app-state state {:map :key}) => (get state {:map :key})

        "Handles sequential keys"
        (util/log-app-state state [[1 2 3]]) => :data

        "Handles non-sequential and sequential keys together"
        (util/log-app-state state [:foo :a] {:map :key}) => {:foo        {:a :b}
                                                             {:map :key} {:other :data}}

        "Handles distinct paths"
        (util/log-app-state state [:foo 12 [:wee :ha] :g] [{:map :key}]) => {:foo        {12 {[:wee :ha] {:g :h}}}
                                                                             {:map :key} {:other :data}}

        "Handles shared paths"
        (util/log-app-state state [:foo 12 [:wee :ha] :g] [:foo :a]) => {:foo {12 {[:wee :ha] {:g :h}}
                                                                               :a :b}}

        "Handles keys and paths together"
        (util/log-app-state state {:map :key} [:foo 12 :c 1]) => {:foo        {12 {:c {1 "world"}}}
                                                                  {:map :key} {:other :data}}))))
