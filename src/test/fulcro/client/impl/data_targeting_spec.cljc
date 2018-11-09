(ns fulcro.client.impl.data-targeting-spec
  (:require
    [fulcro.client.impl.data-targeting :as targeting]
    [fulcro.client.primitives :refer [defsc]]
    [fulcro-spec.core :refer
     [specification behavior assertions provided component when-mocking]]))

(defsc A [_ _])

(specification "Special targeting" :focused
  (assertions
    "Reports false for non-special targets"
    (targeting/special-target? [:class]) => false
    (targeting/special-target? [::class]) => false
    "Allows for arbitrary metadata on the target"
    (targeting/special-target? (with-meta [::x :y :z] {:class A})) => false
    "Tolerates an empty target"
    (targeting/special-target? nil) => false
    "Is detectable"
    (targeting/special-target? (targeting/append-to [])) => true
    (targeting/special-target? (targeting/prepend-to [])) => true
    (targeting/special-target? (targeting/replace-at [])) => true
    (targeting/special-target? (targeting/multiple-targets [:a] [:b])) => true)
  (component "process-target"
    (let [starting-state      {:root/thing [:y 2]
                               :table      {1 {:id 1 :thing [:x 1]}}}
          starting-state-many {:root/thing [[:y 2]]
                               :table      {1 {:id 1 :things [[:x 1] [:x 2]]}}}
          starting-state-data {:root/thing {:some "data"}
                               :table      {1 {:id 1 :things [{:foo "bar"}]}}}]
      (component "non-special targets"
        (assertions
          "moves an ident at some top-level key to an arbitrary path (non-special)"
          (targeting/process-target starting-state :root/thing [:table 1 :thing]) => {:table {1 {:id 1 :thing [:y 2]}}}
          "creates an ident for some location at a target location (non-special)"
          (targeting/process-target starting-state [:table 1] [:root/thing]) => {:root/thing [:table 1]
                                                                           :table      {1 {:id 1 :thing [:x 1]}}}
          "replaces a to-many with a to-many (non-special)"
          (targeting/process-target starting-state-many :root/thing [:table 1 :things]) => {:table {1 {:id 1 :things [[:y 2]]}}}))
      (component "special targets"
        (assertions
          "can prepend into a to-many"
          (targeting/process-target starting-state-many :root/thing (targeting/prepend-to [:table 1 :things])) => {:table {1 {:id 1 :things [[:y 2] [:x 1] [:x 2]]}}}
          "can append into a to-many"
          (targeting/process-target starting-state-many :root/thing (targeting/append-to [:table 1 :things])) => {:table {1 {:id 1 :things [[:x 1] [:x 2] [:y 2]]}}}
          "keep data on db when remove-ok? is false"
          (targeting/process-target starting-state-data :root/thing (targeting/prepend-to [:table 1 :things]) false)
          => {:root/thing {:some "data"}
              :table      {1 {:id 1 :things [{:some "data"} {:foo "bar"}]}}}
          ; Unsupported:
          ;"can replace an element in a to-many"
          ;(targeting/process-target starting-state-many :root/thing (targeting/replace-at [:table 1 :things 0])) => {:table {1 {:id 1 :things [[:y 2] [:x 2]]}}}
          "can affect multiple targets"
          (targeting/process-target starting-state-many :root/thing (targeting/multiple-targets
                                                                (targeting/prepend-to [:table 1 :stuff])
                                                                [:root/spot]
                                                                (targeting/append-to [:table 1 :things]))) => {:root/spot [[:y 2]]
                                                                                                        :table     {1 {:id     1
                                                                                                                       :stuff  [[:y 2]]
                                                                                                                       :things [[:x 1] [:x 2] [:y 2]]}}})))))
