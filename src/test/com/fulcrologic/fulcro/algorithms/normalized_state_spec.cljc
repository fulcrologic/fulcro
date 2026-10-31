(ns com.fulcrologic.fulcro.algorithms.normalized-state-spec
  (:require
    [com.fulcrologic.fulcro.algorithms.normalized-state :as nsh]
    [com.fulcrologic.fulcro.components :refer [defsc]]
    [fulcro-spec.core :refer [=> assertions component specification]]))


(specification "tree-path->db-path"

  (component "Resolves top-level to-one references"
    (let [state {:fastest-car [:car/id 1]
                 :car/id      {1 {:car/model "model-1"}
                               2 {:car/model "model-2"}}}]
      (assertions
        (nsh/tree-path->db-path state [:fastest-car])
        => [:car/id 1])))

  (component "Resolves top-level to-many references"
    (let [state {:grandparents [[:person/id 1] [:person/id 2]]
                 :person/id    {1 {:person/name "person-1"}
                                2 {:person/name "person-2"}}}]
      (assertions
        (nsh/tree-path->db-path state [:grandparents 1])
        => [:person/id 2])))

  (component "Resolves table-nested to-one references"
    (let [state {:person/id {1 {:person/name  "person-1"
                                :person/email [:email/id 1]}
                             2 {:person/name  "person-2"
                                :person/email [:email/id 2]}}
                 :email/id  {1 {:email/provider "Google"}}}]
      (assertions
        (nsh/tree-path->db-path state [:person/id 1 :person/email])
        => [:email/id 1])))

  (component "Resolves table-nested to-many references"
    (let [state {:person/id {1 {:person/name "person-1"
                                :person/cars [[:car/id 1] [:car/id 2]]}
                             2 {:person/name "person-2"
                                :person/cars [[:car/id 1]]}}
                 :car/id    {1 {:car/model "model-1"}
                             2 {:car/model "model-2"}}}]
      (assertions
        (nsh/tree-path->db-path state [:person/id 1 :person/cars 0])
        => [:car/id 1]))))


(specification "get-in"
  (component "Follows edges from root that are normalized"
    (let [state {:person/id {1 {:person/name "person-1"}
                             2 {:person/name "person-2"}}}]
      (assertions
        (get-in state [:person/id 1 :person/name]) => "person-1")))

  (component "Behaves like clojure.core/get-in for denormalized data"
    (let [denorm-data {:a [[:b 1]] :b [:b 1]}
          state       {:denorm {:level-1 {:level-2 denorm-data}}}]
      (assertions
        (nsh/get-in-graph state [:denorm :level-1 :level-2]) => denorm-data)))

  (component "Returns nil when the value isn't found"
    (let [state {:person/id {1 {:person/name "person-1"
                                :person/cars [[:car/id 1] [:car/id 2]]}
                             2 {:person/name "person-2"
                                :person/cars [[:car/id 1]]}}}]
      (assertions
        (nsh/get-in-graph state [:person/id 1 :person/email]) => nil
        (nsh/get-in-graph state [:car/id 1]) => nil)))


  (component "Returns not-found when provided this option"
    (let [state {:person/id {1 {:person/name  "person-1"
                                :person/email [:email/id 1]}
                             2 {:person/name  "person-2"
                                :person/email [:email/id 2]}}}]
      (assertions
        (nsh/get-in-graph state [:person/id 3 :person/name] "not-found") => "not-found"))))


(defsc Person [this {:keys [:person/id :person/name :person/children] :as props}]
  {:query [:person/id :person/name {:person/children '...}]
   :ident :person/id})

(specification "ui->props"
  (component "Pulls the props from the component given the app state"
    (let [state {:person/id {1 {:person/id 1 :person/name "Dad" :person/children [[:person/id 2] [:person/id 3]]}
                             2 {:person/id 2 :person/name "Son"}
                             3 {:person/id 3 :person/name "Daughter"}}}]
      (assertions
        (nsh/ui->props state Person [:person/id 1]) => {:person/id       1 :person/name "Dad"
                                                        :person/children [{:person/id 2 :person/name "Son"}
                                                                          {:person/id 3 :person/name "Daughter"}]}))))

(specification {:covers {`nsh/dissoc-in "771be6"}} "dissoc-in"
  (component "Leaf-level dissoc (single-key path)"
    (assertions
      "removes a top-level key that exists"
      (nsh/dissoc-in {:a 1 :b 2} [:a])
      => {:b 2}

      "returns map unchanged when single key does not exist"
      (nsh/dissoc-in {:a 1 :b 2} [:c])
      => {:a 1 :b 2}

      "returns nil when map is nil and path is single key"
      (nsh/dissoc-in nil [:a])
      => nil))

  (component "Multi-key path with missing or falsey intermediate value"
    (assertions
      "returns map unchanged when intermediate key is missing"
      (nsh/dissoc-in {:a {:b {:c 1}}} [:x :y :z])
      => {:a {:b {:c 1}}}

      "returns map unchanged when intermediate key has nil value"
      (nsh/dissoc-in {:a nil} [:a :b])
      => {:a nil}

      "returns map unchanged when intermediate key has false value (if-let falsey)"
      (nsh/dissoc-in {:a false} [:a :b])
      => {:a false}))

  (component "Multi-key path with non-empty child after removal"
    (assertions
      "removes leaf key and preserves sibling keys in child map"
      (nsh/dissoc-in {:a {:b 1 :c 2}} [:a :b])
      => {:a {:c 2}}

      "removes deeply nested leaf while preserving all intermediate structure and siblings"
      (nsh/dissoc-in {:a {:b {:c 1 :d 2}} :e 3} [:a :b :c])
      => {:a {:b {:d 2}} :e 3}))

  (component "Multi-key path with empty child after removal (pruning)"
    (assertions
      "prunes parent key when child map becomes empty after removal"
      (nsh/dissoc-in {:a {:b 1}} [:a :b])
      => {}

      "cascades pruning through all empty ancestor levels"
      (nsh/dissoc-in {:a {:b {:c 1}}} [:a :b :c])
      => {}

      "prunes only the empty branch while preserving top-level siblings"
      (nsh/dissoc-in {:a {:b {:c 1}} :d 4} [:a :b :c])
      => {:d 4}

      "prunes empty child but preserves non-empty sibling at same level"
      (nsh/dissoc-in {:a {:b 1 :c {:d 2}}} [:a :c :d])
      => {:a {:b 1}}))

  (component "Edge cases with complex keys and empty paths"
    (assertions
      "handles vector/ident keys correctly in normalized state maps"
      (nsh/dissoc-in {[:person/id 1] {:name "Alice" :age 30}} [[:person/id 1] :age])
      => {[:person/id 1] {:name "Alice"}}

      "returns map unchanged when path is empty"
      (nsh/dissoc-in {:a 1 :b 2} [])
      => {:a 1 :b 2})))

(specification "remove-entity*"
  (component "Without cascading"
    (let [denorm-data {:a [[:person/id 1] [:person/id 2]]
                       :b [:person/id 1]}

          state       {:fastest-car  [:car/id 1]
                       :grandparents [[:person/id 1] [:person/id 2]]
                       :denorm       {:level-1 {:level-2 denorm-data}}
                       :person/id    {1 {:person/id    1
                                         :person/email [:email/id 1]
                                         :person/cars  [[:car/id 1]
                                                        [:car/id 2]]}
                                      2 {:person/id 2}}
                       :car/id       {1 {:car/id 1}
                                      2 {:car/id 2}}
                       :email/id     {1 {:email/id 1}}}]
      (assertions
        "Removes the entity itself from the database"
        (-> (nsh/remove-entity state [:person/id 1])
          (nsh/get-in-graph [:person/id 1])) => nil
        "Removes top-level to-one references"
        (-> (nsh/remove-entity state [:car/id 1]) :fastest-car) => nil
        "Removes top-level to-many refs"
        (-> (nsh/remove-entity state [:person/id 1]) :grandparents) => [[:person/id 2]]
        "Removes table-nested to-one references"
        (get-in (nsh/remove-entity state [:email/id 1]) [:person/id 1 :person/email]) => nil
        "Removes table-nested to-many refs"
        (-> (nsh/remove-entity state [:car/id 1])
          (nsh/get-in-graph [:person/id 1 :person/cars])) => [[:car/id 2]])))



  (component "With cascading, non-recursive behavior"
    (let [state {:person/id {1 {:person/id    1
                                :person/email [:email/id 1]
                                :person/cars  [[:car/id 1]
                                               [:car/id 2]]}
                             2 {:person/id 2}}
                 :car/id    {1 {:car/id 1}
                             2 {:car/id 2}}
                 :email/id  {1 {:email/id 1}}}]
      (let [new-state (nsh/remove-entity state [:person/id 1] #{:person/email})]
        (assertions
          "removes the person entity when cascading to-one"
          (nsh/get-in-graph new-state [:person/id 1]) => nil
          "cascades the removal to the email entity"
          (nsh/get-in-graph new-state [:email/id 1]) => nil))

      (let [new-state (nsh/remove-entity state [:person/id 1] #{:person/cars})]
        (assertions
          "removes the person entity when cascading to-many"
          (nsh/get-in-graph new-state [:person/id 1]) => nil
          "cascades removal of car 1"
          (nsh/get-in-graph new-state [:car/id 1]) => nil
          "cascades removal of car 2"
          (nsh/get-in-graph new-state [:car/id 2]) => nil))

      (let [new-state (nsh/remove-entity state [:person/id 1] #{:person/email :person/cars})]
        (assertions
          "removes the person entity when cascading multiple fields"
          (nsh/get-in-graph new-state [:person/id 1]) => nil
          "cascades removal of the email entity with multiple cascade"
          (nsh/get-in-graph new-state [:email/id 1]) => nil
          "cascades removal of car 1 with multiple cascade"
          (nsh/get-in-graph new-state [:car/id 1]) => nil
          "cascades removal of car 2 with multiple cascade"
          (nsh/get-in-graph new-state [:car/id 2]) => nil))))

  (component "With cascading, recursive behavior"
    (let [state {:person/id {1 {:person/id       1
                                :person/spouse   [:person/id 2]
                                :person/children [[:person/id 3]]}
                             2 {:person/id       2
                                :person/spouse   [:person/id 1]
                                :person/children [[:person/id 3]]}
                             3 {:person/id 3}}}]
      (let [new-state (nsh/remove-entity state [:person/id 1] #{:person/children})]
        (assertions
          "removes person 1 when cascading children"
          (nsh/get-in-graph new-state [:person/id 1]) => nil
          "cascades removal of person 3 (child)"
          (nsh/get-in-graph new-state [:person/id 3]) => nil))

      (let [new-state (nsh/remove-entity state [:person/id 1] #{:person/children :person/spouse})]
        (assertions
          "removes person 2 (cascaded spouse)"
          (get-in new-state [:person/id 2]) => nil
          "removes person 3 (cascaded child)"
          (get-in new-state [:person/id 3]) => nil))))

  (component "With deep removal"
    (let [state {:person/id {1 {:person/id       1
                                :person/spouse   [:person/id 2]
                                :person/children [[:person/id 3]]}
                             2 {:person/id       2
                                :ui/params       {:foo {:bar [:person/id 1]}}
                                :person/spouse   [:person/id 1]
                                :person/children [[:person/id 3]]}
                             3 {:person/id 3}}}]
      (assertions
        "Normally leaves the nested ident in place"
        (let [new-state (nsh/remove-entity state [:person/id 1] #{:person/children} {:clear-nested-idents? false})]
          (nsh/get-in-graph new-state [:person/id 2 :ui/params :foo])) => {:bar [:person/id 1]}

        "But can be asked to remove it"
        (let [new-state (nsh/remove-entity state [:person/id 1] #{:person/children} {:clear-nested-idents? true})]
          (nsh/get-in-graph new-state [:person/id 2 :ui/params :foo])) => {}))))


#_(specification "remove-edge*"

    (behavior "Without cascading"
      (let [state {:fastest-car    [:car/id 1]
                   :denorm         {:level-1 {:level-2 {:a [[:person/id 1] [:person/id 2]]
                                                        :b [:person/id 1]}}}
                   :favourite-cars [[:car/id 1] [:car/id 2]]
                   :car/id         {1 {:car/registered-owner [:person/id 1]}
                                    2 {:car/registered-owner [:person/id 1]}}
                   :person/id      {1 {:person/age        42
                                       :person/cars       [[:car/id 1] [:car/id 2]]
                                       :person/address    [:address/id 1]
                                       :alternate-address [:address/id 2]}}
                   :address/id     {1 {:address/state "Oregon"}
                                    2 {:address/state "Idaho"}}}]
        (assertions


          "Refuses to remove a denormalized edge"
          (nsh/remove-edge state [:denorm :level-1 :level2 :b]) => state

          "Refuses to remove a non-edge"
          (nsh/remove-edge state [:person/id 1 :person/age]) => state

          "Removes top-level to-one edge"
          (let [new-state (nsh/remove-edge state [:fastest-car])]
            (or
              (get-in new-state [:car/id 1])
              (get-in new-state [:fastest-car]))) => nil

          "Removes top-level to-many edge"
          (let [new-state (nsh/remove-edge state [:favourite-cars])]
            (or
              (get-in new-state [:car/id 1])
              (get-in new-state [:car/id 2])
              (get-in new-state [:favourite-cars]))) => []

          "Removes table-nested to-one edge"
          (let [new-state (nsh/remove-edge state [:person/id 1 :person/address])]
            (or
              (get-in new-state [:address/id 1])
              (get-in new-state [:person/id 1 :person/address]))) => nil

          "Removes table-nested to-many edge"
          (let [new-state (nsh/remove-edge state [:person/id 1 :person/cars])]
            (or
              (get-in new-state [:car/id 1])
              (get-in new-state [:car/id 2])
              (get-in new-state [:person/id 1 :person/cars]))) => [])))

    (behavior "With to-one cascaded edge removal"
      (let [state {:person/id      {1 {:person/id   1
                                       :latest-car  [:car/id 2]
                                       :person/cars [[:car/id 1] [:car/id 2]]}
                                    2 {:person/id 2}}
                   :fastest-car    [:car/id 1]
                   :favourite-cars [[:car/id 1] [:car/id 2]]
                   :car/id         {1 {:car/id     1
                                       :car/engine [:engine/id 1]}
                                    2 {:car/id     2
                                       :car/engine [:engine/id 2]}}
                   :engine/id      {1 {:engine/id 1}
                                    2 {:engine/id 2}}}]

        (assertions

          "Removes top-level to-one edge"
          (let [new-state (nsh/remove-edge state [:fastest-car] #{:car/engine})]
            (-> (or
                  (get-in new-state [:car/id 1])
                  (get-in new-state [:engine/id 1]))
              nil?)) => true


          "Removes top-level to-many edge"
          (let [new-state (nsh/remove-edge state [:favourite-cars] #{:car/engine})]
            (-> (or
                  (get-in new-state [:car/id 1])
                  (get-in new-state [:engine/id 1])
                  (get-in new-state [:car/id 2])
                  (get-in new-state [:engine/id 2]))
              nil?)) => true


          "Removes table-nested to-one edge"
          (let [new-state (nsh/remove-edge state [:person/id 1 :latest-car] #{:car/engine})]
            (-> (or
                  (get-in new-state [:engine/id 2])
                  (get-in new-state [:car/id 2]))
              nil?)) => true


          "Removes table-nested to-many edge"
          (let [new-state (nsh/remove-edge state [:person/id 1 :person/cars] #{:car/engine})]
            (-> (or
                  (get-in new-state [:car/id 1])
                  (get-in new-state [:engine/id 1])
                  (get-in new-state [:car/id 2])
                  (get-in new-state [:engine/id 2]))
              nil?)) => true)))


    (behavior "With to-many cascaded edge removal"
      (let [state {:person/id      {1 {:person/id   1
                                       :latest-car  [:car/id 2]
                                       :person/cars [[:car/id 1] [:car/id 2]]}}
                   :fastest-car    [:car/id 1]
                   :favourite-cars [[:car/id 1] [:car/id 2]]
                   :car/id         {1 {:car/id     1
                                       :car/colors [[:color/id 1]]}
                                    2 {:car/id     2
                                       :car/colors [[:color/id 1]
                                                    [:color/id 2]]}}
                   :color/id       {1 {:color/id 1}
                                    2 {:color/id 2}}}]

        (assertions

          "Removes top-level to-one edge"
          (let [new-state (nsh/remove-edge state [:fastest-car] #{:car/colors})]
            (-> (or
                  (get-in new-state [:car/id 1])
                  (get-in new-state [:color/id 1]))
              nil?)) => true


          "Removes top-level to-many edge"
          (let [new-state (nsh/remove-edge state [:favourite-cars] #{:car/colors})]
            (-> (or
                  (get-in new-state [:car/id 1])
                  (get-in new-state [:color/id 1])
                  (get-in new-state [:car/id 2])
                  (get-in new-state [:color/id 2]))
              nil?)) => true


          "Removes table-nested to-one edge"
          (let [new-state (nsh/remove-edge state [:person/id 1 :latest-car] #{:car/colors})]
            (-> (or
                  (get-in new-state [:car/id 2])
                  (get-in new-state [:color/id 1])
                  (get-in new-state [:color/id 2]))
              nil?)) => true


          "Removes table-nested to-many edge"
          (let [new-state (nsh/remove-edge state [:person/id 1 :person/cars] #{:car/colors})]
            (-> (or
                  (get-in new-state [:car/id 1])
                  (get-in new-state [:color/id 1])
                  (get-in new-state [:car/id 2])
                  (get-in new-state [:color/id 2]))
              nil?)) => true))))

(defn person [id first last age]
  {:person/id         id
   :person/first-name first
   :person/last-name  last
   :person/age        age})

(specification "sort-idents-by"
  (component "Given a vector of idents and sorting parameter"
    (let [state                     {:person/id {1 (person 1 "Sally" "Smith" 11)
                                                 2 (person 2 "Ally" "Smith" 44)
                                                 3 (person 3 "Tom" "Forth" 34)}}
          people                    [[:person/id 1] [:person/id 2] [:person/id 3]]
          sorted-by-first-name      [[:person/id 2] [:person/id 1] [:person/id 3]]
          sorted-by-last-name-first [[:person/id 3] [:person/id 2] [:person/id 1]]
          sorted-by-age             [[:person/id 1] [:person/id 3] [:person/id 2]]
          sorted-by-age-descending  [[:person/id 2] [:person/id 3] [:person/id 1]]]
      (assertions
        "sorts idents based on simple key function"
        (nsh/sort-idents-by state people :person/first-name) => sorted-by-first-name
        (nsh/sort-idents-by state people :person/age) => sorted-by-age
        "sorts idents based on composite key function"
        (nsh/sort-idents-by state people (fn [{:person/keys [first-name last-name]}]
                                           (str last-name "," first-name))) => sorted-by-last-name-first
        "Honors custom comparator"
        (nsh/sort-idents-by state people :person/age #(compare %2 %1)) => sorted-by-age-descending))))

(specification "update-caller!"
  (component "Updates the app state wrt caller"
    (let [state        (atom {:person/id {1 {:person/id   1
                                             :latest-car  [:car/id 2]
                                             :person/cars [[:car/id 1] [:car/id 2]]}}
                              :car/id    {1 {:car/id 1}
                                          2 {:car/id 2}}})
          mutation-env {:ref   [:person/id 1]
                        :state state}]

      (nsh/update-caller! mutation-env assoc-in [:latest-car] [:car/id 3])

      (assertions
        (get-in @state [:person/id 1 :latest-car]) => [:car/id 3]))))


(specification "update-caller-in!"
  (let [base-state   {:person/id {1 (person 1 "Tom" "Jones" 55)
                                  2 (person 2 "Sally" "Fields" 63)
                                  3 (person 3 "Allison" "Smith" 50)}
                      :list      {1 {:list/people [[:person/id 1] [:person/id 2] [:person/id 3]]}}}
        state-atom   (atom base-state)
        mutation-env {:state state-atom :ref [:list 1]}]
    (nsh/update-caller-in! mutation-env [:list/people] (partial sort-by second #(compare %2 %1)))
    (assertions
      "Applies the desired function at the sub-path of the mutation ref"
      (get-in @state-atom [:list 1 :list/people]) => [[:person/id 3] [:person/id 2] [:person/id 1]])))

(specification {:covers {`nsh/integrate-ident "3b655d"}} "integrate-ident"

  (component "with no named parameters"
    (let [state {:person/id {1 {:person/id 1 :person/name "Alice"}}}
          ident [:person/id 1]]
      (assertions
        "returns state unchanged when no params given"
        (nsh/integrate-ident state ident) => state
        "returns state unchanged when a single unpaired param is given"
        (nsh/integrate-ident state ident :append) => state
        "silently drops trailing unpaired param and applies only complete pairs"
        (nsh/integrate-ident state ident :append [:root/list] :prepend)
        => (nsh/integrate-ident state ident :append [:root/list]))))

  (component "with :prepend — ident already present"
    (let [ident  [:person/id 2]
          state  {:root/people [[:person/id 2] [:person/id 3]]}
          result (nsh/integrate-ident state ident :prepend [:root/people])]
      (assertions
        "preserves the list exactly when ident is already present"
        (get-in result [:root/people]) => [[:person/id 2] [:person/id 3]])))

  (component "with :prepend — ident not in list, path exists"
    (let [ident  [:person/id 2]
          state  {:root/people [[:person/id 3]]}
          result (nsh/integrate-ident state ident :prepend [:root/people])]
      (assertions
        "places ident at the front of the list"
        (first (get-in result [:root/people])) => ident
        "retains existing items after the prepended ident"
        (second (get-in result [:root/people])) => [:person/id 3])))

  (component "with :prepend — path does not exist"
    (let [ident  [:person/id 2]
          result (nsh/integrate-ident {} ident :prepend [:root/people])]
      (assertions
        "creates the list at a non-existent path containing only the ident"
        (get-in result [:root/people]) => [ident])))

  (component "with :append — ident already present"
    (let [ident  [:person/id 2]
          state  {:root/people [[:person/id 3] [:person/id 2]]}
          result (nsh/integrate-ident state ident :append [:root/people])]
      (assertions
        "preserves the list exactly when ident is already present"
        (get-in result [:root/people]) => [[:person/id 3] [:person/id 2]])))

  (component "with :append — ident not in list, path exists"
    (let [ident  [:person/id 2]
          state  {:root/people [[:person/id 3]]}
          result (nsh/integrate-ident state ident :append [:root/people])]
      (assertions
        "places ident at the end of the list"
        (last (get-in result [:root/people])) => ident
        "retains existing items before the appended ident"
        (first (get-in result [:root/people])) => [:person/id 3])))

  (component "with :append — path does not exist"
    (let [ident  [:person/id 2]
          result (nsh/integrate-ident {} ident :append [:root/people])]
      (assertions
        "creates the list at a non-existent path containing only the ident"
        (get-in result [:root/people]) => [ident])))

  (component "with :replace — to-one"
    (let [ident  [:person/id 2]
          state  {:person/id {1 {:person/id 1 :person/spouse nil}}}
          result (nsh/integrate-ident state ident :replace [:person/id 1 :person/spouse])]
      (assertions
        "replaces the value at the target path"
        (get-in result [:person/id 1 :person/spouse]) => ident))
    (let [ident  [:person/id 2]
          state  {:person/id {1 {:person/id 1 :person/spouse [:person/id 2]}}}
          result (nsh/integrate-ident state ident :replace [:person/id 1 :person/spouse])]
      (assertions
        "always writes even when ident is already at the target path"
        (get-in result [:person/id 1 :person/spouse]) => ident)))

  (component "with :replace — to-many, valid index"
    (let [ident  [:person/id 3]
          state  {:person/id {1 {:person/id 1 :person/friends [[:person/id 2] [:person/id 99]]}}}
          result (nsh/integrate-ident state ident :replace [:person/id 1 :person/friends 0])]
      (assertions
        "replaces the element at the given index"
        (get-in result [:person/id 1 :person/friends 0]) => ident
        "leaves elements at other indices unchanged"
        (get-in result [:person/id 1 :person/friends 1]) => [:person/id 99])))

  (component "with :replace — to-many index errors"
    (let [ident [:person/id 2]
          state {:person/id {1 {:person/id 1 :person/friends [[:person/id 3]]}}}]
      (assertions
        "throws when replacing into a to-many with a non-numeric index"
        (nsh/integrate-ident state ident :replace [:person/id 1 :person/friends :bad-index])
        =throws=> #?(:clj IllegalArgumentException :cljs js/Error)
        "throws when replacing into a to-many with an out-of-bounds index"
        (nsh/integrate-ident state ident :replace [:person/id 1 :person/friends 99])
        =throws=> #?(:clj IndexOutOfBoundsException :cljs js/Error))))

  (component "with multiple operations"
    (let [ident  [:person/id 2]
          state  {:root/people [] :root/vip []}
          result (nsh/integrate-ident state ident
                   :append  [:root/people]
                   :prepend [:root/vip])]
      (assertions
        "appends ident to the first target path"
        (get-in result [:root/people]) => [ident]
        "prepends ident to the second target path"
        (get-in result [:root/vip]) => [ident]))
    (let [ident  [:person/id 2]
          state  {:root/people []}
          result (nsh/integrate-ident state ident
                   :append [:root/people]
                   :append [:root/people])]
      (assertions
        "second duplicate append is skipped because accumulated state already has the ident"
        (get-in result [:root/people]) => [ident])))

  (component "with non-ident values"
    (assertions
      "appends a plain string value"
      (nsh/integrate-ident {} "Alice" :append [:names]) => {:names ["Alice"]}
      "appends a plain number value"
      (nsh/integrate-ident {} 42 :append [:ids]) => {:ids [42]}
      "appends a map value"
      (nsh/integrate-ident {} {:a 1} :append [:items]) => {:items [{:a 1}]}
      "deduplication works for non-ident values"
      (nsh/integrate-ident {:names ["Alice"]} "Alice" :append [:names]) => {:names ["Alice"]}
      "prepend deduplication works for nil value"
      (nsh/integrate-ident {:items [nil]} nil :prepend [:items]) => {:items [nil]})))

(specification "swap!->"
  (component "Threads an atom's value through the given operations during a `swap!`."
    (let [state (atom {:person/id {1 {:person/id   1
                                      :latest-car  [:car/id 2]
                                      :person/cars [[:car/id 1] [:car/id 2]]}}
                       :car/id    {1 {:car/id     1
                                      :car/colors [[:color/id 1]]}
                                   2 {:car/id     2
                                      :car/colors [[:color/id 1]
                                                   [:color/id 2]]}}})]

      (nsh/swap!-> state
        (assoc-in [:person/id 1 :person/age] 42)
        (update-in [:person/id 1 :person/age] inc))

      (assertions
        (nsh/get-in-graph @state [:person/id 1 :person/age]) => 43))))
