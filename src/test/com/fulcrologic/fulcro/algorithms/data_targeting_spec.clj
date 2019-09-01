(ns com.fulcrologic.fulcro.algorithms.data-targeting-spec
  (:require
    [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
    [com.fulcrologic.fulcro.components :refer [defsc]]
    [fulcro-spec.core :refer
     [specification behavior assertions provided component when-mocking]]))

(defsc A [_ _])

(specification "Special targeting"
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
                                                                                                                                    :things [[:x 1] [:x 2] [:y 2]]}}}
          "multiple targets with explicit ident"
          (targeting/process-target {:table {1 {:id    1
                                                :stuff [:old :ident]}}} [:new :ident] (targeting/multiple-targets
                                                                                        [:table 1 :stuff]
                                                                                        [:root/spot])) => {:root/spot [:new :ident]
                                                                                                           :table     {1 {:id    1
                                                                                                                          :stuff [:new :ident]}}}

          "multiple targets with explicit ident 2"
          (targeting/process-target
            {:table-1      {1 {:id 1}}
             ::some-result [:new :ident]
             :new          {:ident {:data 1}}
             :table-2      {1 {:id 1}}}
            ::some-result
            (targeting/multiple-targets
              [:table-1 1 :stuff]
              [:table-2 1 :stuff]
              [:root/spot]))
          => {:table-1   {1 {:id    1
                             :stuff [:new :ident]}}
              :table-2   {1 {:id    1
                             :stuff [:new :ident]}}
              :root/spot [:new :ident]
              :new       {:ident {:data 1}}}))))
  (component "to-many source targeting"
    (assertions
      "can move to-many refs to new edges"
      (targeting/process-target {:table      {1 {:id 1}
                                              2 {:id 2}}
                                 :new        {1 {:id 1}
                                              2 {:id 2}}
                                 :source-key [[:new 1] [:new 2]]}
        :source-key
        (targeting/multiple-targets
          [:table 1 :stuff]
          [:table 2 :stuff]))
      =>
      {:table {1 {:id    1
                  :stuff [[:new 1] [:new 2]]}
               2 {:id    2
                  :stuff [[:new 1] [:new 2]]}}
       :new   {1 {:id 1}
               2 {:id 2}}})))

(specification "integrate-ident*"
  (let [state {:a    {:path [[:table 2]]}
               :b    {:path [[:table 2]]}
               :d    [:table 6]
               :many {:path [[:table 99] [:table 88] [:table 77]]}}]
    (assertions
      "Can append to an existing vector"
      (-> state
        (targeting/integrate-ident* [:table 3] :append [:a :path])
        (get-in [:a :path]))
      => [[:table 2] [:table 3]]

      "Will append (create) on a non-existent vector"
      (-> state
        (targeting/integrate-ident* [:table 3] :append [:a :missing])
        (get-in [:a :missing]))
      => [[:table 3]]

      "(is a no-op if the ident is already there)"
      (-> state
        (targeting/integrate-ident* [:table 3] :append [:a :path])
        (get-in [:a :path]))
      => [[:table 2] [:table 3]]

      "Can prepend to an existing vector"
      (-> state
        (targeting/integrate-ident* [:table 3] :prepend [:b :path])
        (get-in [:b :path]))
      => [[:table 3] [:table 2]]

      "Will prepend (create) on a non-existent vector"
      (-> state
        (targeting/integrate-ident* [:table 3] :prepend [:a :missing])
        (get-in [:a :missing]))
      => [[:table 3]]

      "(is a no-op if already there)"
      (-> state
        (targeting/integrate-ident* [:table 3] :prepend [:b :path])
        (get-in [:b :path]))
      => [[:table 3] [:table 2]]

      "Can create/replace a to-one ident"
      (-> state
        (targeting/integrate-ident* [:table 3] :replace [:d])
        (get-in [:d]))
      => [:table 3]
      (-> state
        (targeting/integrate-ident* [:table 3] :replace [:c :path])
        (get-in [:c :path]))
      => [:table 3]

      "Can replace an existing to-many element in a vector"
      (-> state
        (targeting/integrate-ident* [:table 3] :replace [:many :path 1])
        (get-in [:many :path]))
      => [[:table 99] [:table 3] [:table 77]])))
