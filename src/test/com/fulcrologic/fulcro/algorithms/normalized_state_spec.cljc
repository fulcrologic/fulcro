(ns com.fulcrologic.fulcro.algorithms.normalized-state-spec
  (:require
    [fulcro-spec.core :refer [assertions specification component when-mocking behavior]]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as nsh]))

(specification "tree-path->db-path"

  (behavior "Resolves top-level to-one references"
    (let [state {:fastest-car [:car/id 1]
                 :car/id      {1 {:car/model "model-1"}
                               2 {:car/model "model-2"}}}]
      (assertions
        (nsh/tree-path->db-path state [:fastest-car])
        => [:car/id 1])))

  (behavior "Resolves top-level to-many references"
    (let [state {:grandparents [[:person/id 1] [:person/id 2]]
                 :person/id    {1 {:person/name "person-1"}
                                2 {:person/name "person-2"}}}]
      (assertions
        (nsh/tree-path->db-path state [:grandparents 1])
        => [:person/id 2])))

  (behavior "Resolves table-nested to-one references"
    (let [state {:person/id {1 {:person/name  "person-1"
                                :person/email [:email/id 1]}
                             2 {:person/name  "person-2"
                                :person/email [:email/id 2]}}
                 :email/id  {1 {:email/provider "Google"}}}]
      (assertions
        (nsh/tree-path->db-path state [:person/id 1 :person/email])
        => [:email/id 1])))

  (behavior "Resolves table-nested to-many references"
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
  (behavior "Follows edges from root that are normalized"
    (let [state {:person/id {1 {:person/name "person-1"}
                             2 {:person/name "person-2"}}}]
      (assertions
        (get-in state [:person/id 1 :person/name]) => "person-1")))

  (behavior "Behaves like clojure.core/get-in for denormalized data"
    (let [denorm-data {:a [[:b 1]] :b [:b 1]}
          state       {:denorm {:level-1 {:level-2 denorm-data}}}]
      (assertions
        (nsh/get-in-graph state [:denorm :level-1 :level-2]) => denorm-data)))

  (behavior "Returns nil when the value isn't found"
    (let [state {:person/id {1 {:person/name "person-1"
                                :person/cars [[:car/id 1] [:car/id 2]]}
                             2 {:person/name "person-2"
                                :person/cars [[:car/id 1]]}}}]
      (assertions
        (nsh/get-in-graph state [:person/id 1 :person/email]) => nil
        (nsh/get-in-graph state [:car/id 1]) => nil)))


  (behavior "Returns not-found when provided this option"
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
  (behavior "Pulls the props from the component given the app state"
    (let [state {:person/id {1 {:person/id 1 :person/name "Dad" :person/children [[:person/id 2] [:person/id 3]]}
                             2 {:person/id 2 :person/name "Son"}
                             3 {:person/id 3 :person/name "Daughter"}}}]
      (assertions
        (nsh/ui->props state Person [:person/id 1]) => {:person/id       1 :person/name "Dad"
                                                        :person/children [{:person/id 2 :person/name "Son"}
                                                                          {:person/id 3 :person/name "Daughter"}]}))))

(specification "remove-entity*"
  (behavior "Without cascading"
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
        "Ignores denormalized data"
        (-> (nsh/remove-entity state [:person/id 1])
          (nsh/get-in-graph [:denorm :level-1 :level-2])) => denorm-data
        "Removes table-nested to-one references"
        (let [new-state (nsh/remove-entity state [:email/id 1])]
          (-> (or
                (clojure.core/get-in new-state [:person/id 1 :person/email])
                (nsh/get-in-graph new-state [:person/id 1 :person/email]))
            nil?)) => true
        "Removes table-nested to-many refs"
        (-> (nsh/remove-entity state [:car/id 1])
          (nsh/get-in-graph [:person/id 1 :person/cars])) => [[:car/id 2]])))



  (behavior "With cascading, non-recursive behavior"
    (let [state {:person/id {1 {:person/id    1
                                :person/email [:email/id 1]
                                :person/cars  [[:car/id 1]
                                               [:car/id 2]]}
                             2 {:person/id 2}}
                 :car/id    {1 {:car/id 1}
                             2 {:car/id 2}}
                 :email/id  {1 {:email/id 1}}}]
      (assertions
        "Removes a single, to-one and non-recursive cascased entity"
        (let [new-state (nsh/remove-entity state [:person/id 1] #{:person/email})]
          (->
            (or
              (nsh/get-in-graph new-state [:person/id 1])
              (nsh/get-in-graph new-state [:email/id 1]))
            nil?)) => true

        "Removes a single, to-many and non-recursive cascased entity"
        (let [new-state (nsh/remove-entity state [:person/id 1] #{:person/cars})]
          (or
            (nsh/get-in-graph new-state [:person/id 1])
            (nsh/get-in-graph new-state [:car/id 1])
            (nsh/get-in-graph new-state [:car/id 2]))) => nil

        "Removes multiple, to-one and non-recursive cascased entity"
        (let [new-state (nsh/remove-entity state [:person/id 1]
                          #{:person/email :person/cars})]
          (or
            (nsh/get-in-graph new-state [:person/id 1])
            (nsh/get-in-graph new-state [:email/id 1])
            (nsh/get-in-graph new-state [:car/id 1])
            (nsh/get-in-graph new-state [:car/id 2]))) => nil)))

  (behavior "With cascading, recursive behavior"
    (let [state {:person/id {1 {:person/id       1
                                :person/spouse   [:person/id 2]
                                :person/children [[:person/id 3]]}
                             2 {:person/id       2
                                :person/spouse   [:person/id 1]
                                :person/children [[:person/id 3]]}
                             3 {:person/id 3}}}]
      (assertions
        "Removes a single, to-many cascased entities"
        (let [new-state (nsh/remove-entity state [:person/id 1] #{:person/children})]
          (or
            (nsh/get-in-graph new-state [:person/id 1])
            (nsh/get-in-graph new-state [:person/id 3]))) => nil

        "Removes multiple, to-many cascased entities"
        (let [new-state (nsh/remove-entity state [:person/id 1]
                          #{:person/children :person/spouse})]
          (or
            (get new-state [:person/id 2])
            (get new-state [:person/id 3]))) => nil))))


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
  (behavior "Given a vector of idents and sorting parameter"
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
  (behavior "Updates the app state wrt caller"
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

(specification "swap!->"
  (behavior "Threads an atom's value through the given operations during a `swap!`."
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
