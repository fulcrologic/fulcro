(ns com.fulcrologic.fulcro.algorithms.merge-spec
  (:require
    #?(:clj  [clojure.test :refer :all]
       :cljs [clojure.test :refer [deftest]])
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [fulcro-spec.core :refer [assertions specification component when-mocking behavior provided]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    [com.fulcrologic.fulcro.application :as app]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
    [com.fulcrologic.fulcro.algorithms.scheduling :as sched]))

(declare =>)

(defsc MMChild [_ _]
  {:query         [:db/id]
   :initial-state {:db/id :param/id}})

(defsc MMParent [_ _]
  {:query         [:db/id {:main-child (comp/get-query MMChild)} {:children (comp/get-query MMChild)}]
   :initial-state {:db/id :param/id :main-child :param/main :children :param/children}})

(specification "Mixed Mode Initial State"
  (component "defsc components that use template initial state"
    (assertions
      "Accept maps of child parameters and automatically construct children from them"
      (comp/get-initial-state MMParent {:id 1 :main {:id 1} :children [{:id 1}]})
      => {:db/id      1
          :main-child {:db/id 1}
          :children   [{:db/id 1}]}
      "Allow to-one children to be initialized directly with a call to get-initial-state"
      (comp/get-initial-state MMParent {:id 1 :main (comp/get-initial-state MMChild {:id 1}) :children [{:id 1}]})
      => {:db/id      1
          :main-child {:db/id 1}
          :children   [{:db/id 1}]}
      "Allow to-many children to be initialized directly with calls to get-initial-state"
      (comp/get-initial-state MMParent {:id 1 :main {:id 1} :children [(comp/get-initial-state MMChild {:id 1})]})
      => {:db/id      1
          :main-child {:db/id 1}
          :children   [{:db/id 1}]}
      "Allow to-many children to be initialized with a mix of maps and calls to get-initial-state"
      (comp/get-initial-state MMParent {:id 1 :main {:id 1} :children [{:id 3} (comp/get-initial-state MMChild {:id 1})]})
      => {:db/id      1
          :main-child {:db/id 1}
          :children   [{:db/id 3} {:db/id 1}]})))

(defsc MergeX [_ _]
  {:initial-state (fn [params] {:type :x :n :x})
   :query         (fn [] [:n :type])})

(defsc MergeY [_ _]
  {:initial-state (fn [params] {:type :y :n :y})
   :query         (fn [] [:n :type])})

(defsc MergeAChild [_ _]
  {:initial-state (fn [params] {:child :merge-a})
   :ident         (fn [] [:mergea :child])
   :query         (fn [] [:child])})


(defsc MergeA [_ _]
  {:initial-state (fn [params] {:type :a :n :a :child (comp/get-initial-state MergeAChild nil)})
   :query         (fn [] [:type :n {:child (comp/get-query MergeAChild)}])})

(defsc MergeB [_ _]
  {:initial-state (fn [params] {:type :b :n :b})
   :query         (fn [] [:n])})

(defsc MergeUnion [_ _]
  {:initial-state (fn [params] (comp/get-initial-state MergeA {}))
   :ident         (fn [] [:mergea-or-b :at-union])
   :query         (fn [] {:a (comp/get-query MergeA) :b (comp/get-query MergeB)})})

(defsc MergeRoot [_ _]
  {:initial-state (fn [params] {:a 1 :b (comp/get-initial-state MergeUnion {})})
   :query         (fn [] [:a {:b (comp/get-query MergeUnion)}])})

(defsc U2 [_ _]
  {:initial-state (fn [params] (comp/get-initial-state MergeX {}))
   :query         (fn [] {:x (comp/get-query MergeX) :y (comp/get-query MergeY)})})

;; Nested routing tree
;; NestedRoot
;;     |
;;     U1
;;    /  B    A = MergeRoot B = MergeB
;;    R2
;;   U2       A2
;;  X  Y

(defsc R2 [_ _]
  {:initial-state (fn [params] {:id 1 :u2 (comp/get-initial-state U2 {})})
   :query         (fn [] [:id {:u2 (comp/get-query U2)}])})

(defsc U1 [_ _]
  {:initial-state (fn [params] (comp/get-initial-state MergeB {}))
   :query         (fn [] {:r2 (comp/get-query R2) :b (comp/get-query MergeB)})})

(defsc NestedRoot [_ _]
  {:initial-state (fn [params] {:u1 (comp/get-initial-state U1 {})})
   :query         (fn [] [{:u1 (comp/get-query U1)}])})

(defsc SU1 [_ props]
  {:initial-state (fn [params] (comp/get-initial-state MergeB {}))
   :ident         (fn [] [(:type props) 1])
   :query         (fn [] {:a (comp/get-query MergeA) :b (comp/get-query MergeB)})})

;; Sibling routing tree
;; SiblingRoot
;;     |   \
;;   SU1   SU2
;;  A   B  X  Y

(defsc SU2 [_ props]
  {:initial-state (fn [params] (comp/get-initial-state MergeX {}))
   :ident         (fn [] [(:type props) 2])
   :query         (fn [] {:x (comp/get-query MergeX) :y (comp/get-query MergeY)})})

(defsc SiblingRoot [_ _]
  {:initial-state (fn [params] {:su1 (comp/get-initial-state SU1 {}) :su2 (comp/get-initial-state SU2 {})})
   :query         (fn [] [{:su1 (comp/get-query SU1)} {:su2 (comp/get-query SU2)}])})


(specification "merge-alternate-union-elements!"
  (behavior "For applications with sibling unions"
    (when-mocking
      (merge/merge-component! app comp state) =1x=> (do
                                                      (assertions
                                                        "Merges level one elements"
                                                        comp => SU1
                                                        state => (comp/get-initial-state MergeA {})))
      (merge/merge-component! app comp state) =1x=> (do
                                                      (assertions
                                                        "Merges only the state of branches that are not already initialized"
                                                        comp => SU2
                                                        state => (comp/get-initial-state MergeY {})))

      (merge/merge-alternate-union-elements! :app SiblingRoot)))

  (behavior "For applications with nested unions"
    (when-mocking
      (merge/merge-component! app comp state) =1x=> (do
                                                      (assertions
                                                        "Merges level one elements"
                                                        comp => U1
                                                        state => (comp/get-initial-state R2 {})))
      (merge/merge-component! app comp state) =1x=> (do
                                                      (assertions
                                                        "Merges only the state of branches that are not already initialized"
                                                        comp => U2
                                                        state => (comp/get-initial-state MergeY {})))

      (merge/merge-alternate-union-elements! :app NestedRoot)))
  (behavior "For applications with non-nested unions"
    (let [app (app/fulcro-app)]
      (when-mocking
        (merge/merge-component! app comp state) => (do
                                                     (assertions
                                                       "Merges only the state of branches that are not already initialized"
                                                       comp => MergeUnion
                                                       state => (comp/get-initial-state MergeB {})))

        (merge/merge-alternate-union-elements! app MergeRoot)))))

(defn phone-number [id n] {:id id :number n})

(defn person [id name numbers] {:id id :name name :numbers numbers})
(defsc MPhone [_ props]
  {:query (fn [] [:id :number])
   :ident (fn [] [:phone/id (:id props)])})

(defsc MPerson [_ props]
  {:query (fn [] [:id :name {:numbers (comp/get-query MPhone)}])
   :ident (fn [] [:person/id (:id props)])})

(defsc MPhonePM [_ _]
  {:ident     [:phone/id :id]
   :query     [:id :number]
   :pre-merge (fn [{:keys [current-normalized data-tree]}]
                (merge
                  {:id              :sample-phone-id
                   :ui/initial-flag :start}
                  current-normalized
                  data-tree))})

(defsc MPersonPM [_ props]
  {:pre-merge (fn [{:keys [current-normalized data-tree]}]
                (merge
                  {:id :sample-person-id}
                  current-normalized
                  data-tree))
   :ident     [:person/id :id]
   :query     [:id :name {:numbers (comp/get-query MPhonePM)}]})

(defsc Score
  [_ {::keys []}]
  {:pre-merge (fn [{:keys [current-normalized data-tree]}]
                (merge
                  {:ui/expanded? false}
                  current-normalized
                  data-tree))
   :ident     [::score-id ::score-id]
   :query     [::score-id ::points :ui/expanded?]})

(defsc Scoreboard [_ props]
  {:pre-merge (fn [{:keys [current-normalized data-tree]}]
                (let [{::keys [scores]} data-tree
                      high-score (apply max (map ::points scores))
                      scores     (mapv
                                   (fn [{::keys [points] :as score}]
                                     (assoc score :ui/expanded? (= points high-score)))
                                   scores)]
                  (merge
                    current-normalized
                    (assoc data-tree ::scores scores))))
   :ident     [::scoreboard-id ::scoreboard-id]
   :query     [::scoreboard-id
               {::scores (comp/get-query Score)}]} "")

(defonce id-counter (atom 0))

(defsc UiItem
  [_ _]
  {:pre-merge (fn [{:keys [current-normalized data-tree]}]
                (merge
                  {::id (swap! id-counter inc)}
                  current-normalized
                  data-tree))
   :ident     [::id ::id]
   :query     [::id ::title]})

(defsc UiLoadedItem
  [_ _]
  {:pre-merge (fn [{:keys [current-normalized data-tree]}]
                (merge
                  {:ui/item {}}
                  current-normalized
                  data-tree))
   :ident     [::loaded-id ::loaded-id]
   :query     [::loaded-id ::name
               {:ui/item (comp/get-query UiItem)}]})

(defsc UiCollectionHolder
  [_ _]
  {:ident [::col-id ::col-id]
   :query [::col-id
           {::load-items (comp/get-query UiLoadedItem)}]})

(specification "merge-component"
  (let [component-tree   (person :tony "Tony" [(phone-number 1 "555-1212") (phone-number 2 "123-4555")])
        sally            {:id :sally :name "Sally" :numbers [[:phone/id 3]]}
        phone-3          {:id 3 :number "111-2222"}
        state-map        {:people    [[:person/id :sally]]
                          :phone/id  {3 phone-3}
                          :person/id {:sally sally}}
        new-state-map    (merge/merge-component state-map MPerson component-tree)
        expected-person  {:id :tony :name "Tony" :numbers [[:phone/id 1] [:phone/id 2]]}
        expected-phone-1 {:id 1 :number "555-1212"}
        expected-phone-2 {:id 2 :number "123-4555"}]
    (assertions
      "merges the top-level component with normalized links to children"
      (get-in new-state-map [:person/id :tony]) => expected-person
      "merges the normalized children"
      (get-in new-state-map [:phone/id 1]) => expected-phone-1
      (get-in new-state-map [:phone/id 2]) => expected-phone-2
      "leaves the original state untouched"
      (contains? new-state-map :people) => true
      (get-in new-state-map [:person/id :sally]) => sally
      (get-in new-state-map [:phone/id 3]) => phone-3
      "honors sweep-merge (overwrites data that is in query but did not appear in result) if asked"
      (merge/merge-component
        {:person/id {1 {:id 1 :name "Joe" :numbers [[:phone/id 1]]}}}
        MPerson {:id 1 :numbers []}
        :remove-missing? true)
      => {:person/id {1 {:id 1 :numbers []}}}
      "Prevents sweep-merge by default"
      (merge/merge-component
        {:person/id {1 {:id 1 :name "Joe" :numbers [[:phone/id 1]]}}}
        MPerson {:id 1 :numbers []})
      => {:person/id {1 {:id 1 :name "Joe" :numbers []}}}))

  (assertions
    "Can merge basic data into the database (pre-merge not override)"
    (merge/merge-component {} MPersonPM (person :mary "Mary" [(phone-number 55 "98765-4321")]))
    => {:person/id {:mary {:id      :mary
                           :name    "Mary"
                           :numbers [[:phone/id 55]]}}
        :phone/id  {55 {:id              55
                        :number          "98765-4321"
                        :ui/initial-flag :start}}}

    "Can assign IDs to primary entities via pre-merge"
    (merge/merge-component {} MPersonPM {:name "Mary2" :numbers [{:number "98765-4321"}]}
      :replace [:global-ref])
    => {:global-ref [:person/id :sample-person-id]
        :person/id  {:sample-person-id {:id      :sample-person-id
                                        :name    "Mary2"
                                        :numbers [[:phone/id :sample-phone-id]]}}
        :phone/id   {:sample-phone-id {:id              :sample-phone-id
                                       :number          "98765-4321"
                                       :ui/initial-flag :start}}}

    "can merge nested to-many items and apply pre-merge"
    (merge/merge-component {} Scoreboard {::scoreboard-id 123
                                          ::scores        [{::score-id 1
                                                            ::points   4}
                                                           {::score-id 2
                                                            ::points   8}
                                                           {::score-id 3
                                                            ::points   7}]})
    => {::scoreboard-id {123 {::scoreboard-id 123
                              ::scores        [[::score-id 1]
                                               [::score-id 2]
                                               [::score-id 3]]}}
        ::score-id      {1 {::score-id    1
                            ::points      4
                            :ui/expanded? false}
                         2 {::score-id    2
                            ::points      8
                            :ui/expanded? true}
                         3 {::score-id    3
                            ::points      7
                            :ui/expanded? false}}}

    "can place ident via replace named parameter with pre-merge"
    (merge/merge-component {} MPersonPM (person :mary "Mary" [(phone-number 55 "98765-4321")]) :replace [:main-person])
    => {:person/id   {:mary {:id      :mary
                             :name    "Mary"
                             :numbers [[:phone/id 55]]}}
        :phone/id    {55 {:id              55
                          :number          "98765-4321"
                          :ui/initial-flag :start}}
        :main-person [:person/id :mary]}

    "pre-merge step can assign an id to generated sub-elements (to-one)"
    (do
      (reset! id-counter 0)
      (merge/merge-component {} UiLoadedItem
        {::loaded-id 1
         ::name      "a"}))
    => {::loaded-id {1 {::loaded-id 1
                        ::name      "a"
                        :ui/item    [::id 1]}}
        ::id        {1 {::id 1}}}

    "pre-merge step can assign an id to generated sub-elements (to-many)"
    (do
      (reset! id-counter 0)
      (merge/merge-component {} UiCollectionHolder
        {::col-id     123
         ::load-items [{::loaded-id 1
                        ::name      "a"}
                       {::loaded-id 2
                        ::name      "b"}]}))
    => {::col-id    {123 {::col-id     123
                          ::load-items [[::loaded-id 1]
                                        [::loaded-id 2]]}}
        ::loaded-id {1 {::loaded-id 1
                        ::name      "a"
                        :ui/item    [::id 1]}
                     2 {::loaded-id 2
                        ::name      "b"
                        :ui/item    [::id 2]}}
        ::id        {1 {::id 1}
                     2 {::id 2}}}))

(specification "merge-component!"
  (let [app (app/fulcro-app)]
    (when-mocking
      (merge/merge-component s c d & np) => (do
                                              (assertions
                                                "calls merge-component with the component and data"
                                                c => MPerson
                                                d => {}
                                                "includes the correct named parameters"
                                                np => [:replace [:x]]
                                                ))
      (sched/schedule-animation! a k act) => (assertions
                                               "schedules a refresh"
                                               true => true)

      (merge/merge-component! app MPerson {} :replace [:x]))))

(def table-1 {:type :table :id 1 :rows [1 2 3]})
(defsc Table [_ _]
  {:initial-state (fn [p] table-1)
   :query         (fn [] [:type :id :rows])})

(def graph-1 {:type :graph :id 1 :data [1 2 3]})
(defsc Graph [_ _]
  {:initial-state (fn [p] graph-1)
   :query         (fn [] [:type :id :data])})

(defsc Reports [_ props]
  {:initial-state (fn [p] (comp/get-initial-state Graph nil)) ; initial state will already include Graph
   :ident         (fn [] [(:type props) (:id props)])
   :query         (fn [] {:graph (comp/get-query Graph) :table (comp/get-query Table)})})

(defsc MRRoot [_ _]
  {:initial-state (fn [p] {:reports (comp/get-initial-state Reports nil)})
   :query         (fn [] [{:reports (comp/get-query Reports)}])})

(specification "merge-alternate-union-elements"
  (let [initial-state (merge (comp/get-initial-state MRRoot nil) {:a 1})
        state-map     (fnorm/tree->db MRRoot initial-state true)
        new-state     (merge/merge-alternate-union-elements state-map MRRoot)]
    (assertions
      "can be used to merge alternate union elements to raw state"
      (get-in new-state [:table 1]) => table-1
      "(existing state isn't touched)"
      (get new-state :a) => 1
      (get new-state :reports) => [:graph 1]
      (get-in new-state [:graph 1]) => graph-1)))

(defsc User [_ _]
  {:query [:user/id :user/name]
   :ident :user/id})

(defsc UserPM [_ _]
  {:query     [:user/id :user/name]
   :pre-merge (fn [{:keys [data-tree current-normalized state-map]}]
                (merge
                  {:ui/visible? true}
                  current-normalized
                  data-tree))
   :ident     :user/id})

(defsc UserPMT [_ _]
  {:query     [:user/id :user/name]
   :pre-merge (fn [{:keys [data-tree current-normalized]}]
                ;; rewriting the ID to verify that targeting uses the correct (altered) ident
                (merge
                  current-normalized
                  data-tree
                  {:user/id 2}))
   :ident     :user/id})

(specification "merge-mutation-joins" :focus
  (behavior "Merges basic return values into app state"
    (let [state     {}
          tree      {'some-mutation {:user/id 1 :user/name "Joe"}}
          query     [{(list 'some-mutation {:x 1}) (comp/get-query User)}]
          new-state (merge/merge-mutation-joins state query tree)]
      (assertions
        new-state => {:user/id {1 {:user/id 1 :user/name "Joe"}}})))
  (behavior "Does pre-merge processing"
    (let [state     {}
          tree      {'some-mutation {:user/id 1 :user/name "Joe"}}
          query     [{(list 'some-mutation {:x 1}) (comp/get-query UserPM)}]
          new-state (merge/merge-mutation-joins state query tree)]
      (assertions
        new-state => {:user/id {1 {:ui/visible? true :user/id 1 :user/name "Joe"}}})))
  (behavior "Does data targeting based on pre-merge result"
    (let [state     {}
          tree      {'some-mutation {:user/id 1 :user/name "Joe"}}
          query     [{(list 'some-mutation {:x 1}) (vary-meta (comp/get-query UserPMT)
                                                     assoc ::targeting/target [:top-key])}]
          new-state (merge/merge-mutation-joins state query tree)]
      (assertions
        new-state => {:top-key [:user/id 2]
                      :user/id {2 {:user/id 2 :user/name "Joe"}}})))
  (behavior "Targeting on pre-merge overwrite with id re-assignment"
    (let [state     {:user/id {1 {:user/id 1 :user/name "Tom"}}}
          tree      {'some-mutation {:user/id 1 :user/name "Joe"}}
          query     [{(list 'some-mutation {:x 1}) (vary-meta (comp/get-query UserPMT)
                                                     assoc ::targeting/target [:top-key])}]
          new-state (merge/merge-mutation-joins state query tree)]
      (assertions
        new-state => {:top-key [:user/id 2]
                      :user/id {1 {:user/id 1 :user/name "Tom"}
                                2 {:user/id 2 :user/name "Joe"}}}))))
