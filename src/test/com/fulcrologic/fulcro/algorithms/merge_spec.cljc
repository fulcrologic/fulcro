(ns com.fulcrologic.fulcro.algorithms.merge-spec
  (:require
    #?(:clj  [clojure.test :refer :all]
       :cljs [clojure.test :refer [deftest]])
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [fulcro-spec.core :refer [assertions specification component when-mocking behavior]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]
    [com.fulcrologic.fulcro.algorithms.misc :as util]
    [com.fulcrologic.fulcro.application :as app]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]))

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

(defsc MergeTestChild [_ props]
  {:ident (fn [] [:child/by-id (:id props)])
   :query (fn [] [:id :label])})

(defsc MergeTestParent [_ props]
  {:initial-state (fn [params] {:ui/checked true})
   :ident         (fn [] [:parent/by-id (:id props)])
   :query         (fn [] [:ui/checked :id :title {:child (comp/get-query MergeTestChild)}])})

(specification "merge-component!"
  (assertions
    "merge-query is the component query joined on it's ident"
    (merge/component-merge-query {} MergeTestParent {:id 42}) => [{[:parent/by-id 42] [:ui/checked :id :title {:child (comp/get-query MergeTestChild)}]}])
  (component "preprocessing the object to merge"
    (let [no-state             {:parent/by-id {}}
          no-state-merge-data  (:merge-data (merge/-preprocess-merge no-state MergeTestParent {:id 42}))
          state-with-old       {:parent/by-id {42 {:ui/checked true :id 42 :title "Hello"}}}
          id                   [:parent/by-id 42]
          old-state-merge-data (-> (merge/-preprocess-merge state-with-old MergeTestParent {:id 42}) :merge-data :fulcro/merge)]
      (assertions
        "Uses the existing object in app state as base for merge when present"
        (get-in old-state-merge-data [id :ui/checked]) => true
        "Marks fields that were queried but are not present as prim/not-found"
        old-state-merge-data => {[:parent/by-id 42] {:id         42
                                                     :ui/checked true
                                                     :title      ::merge/not-found
                                                     :child      ::merge/not-found}}))
    (let [union-query {:union-a [:b] :union-b [:c]}
          state       (atom {})]
      (when-mocking
        (comp/get-ident c d) => :ident
        (comp/get-query comp st) => union-query
        (merge/component-merge-query s comp data) => :merge-query
        (fdn/db->tree q d r) => {:ident :data}
        (merge/mark-missing d q) => (do
                                      (assertions
                                        "wraps union queries in a vector"
                                        q => [union-query])

                                      {:ident :data})
        (util/deep-merge d1 d2) => :merge-result

        (merge/-preprocess-merge state :comp :data))))
  (let [state (atom {})
        data  {}]
    (when-mocking
      (merge/-preprocess-merge s c d) => (do
                                           (assertions
                                             "Runs the data through the preprocess merge step"
                                             d => data)
                                           {:merge-data :preprocessed-data :merge-query :the-query})
      (merge/integrate-ident* s i op1 args1 op2 args2) => (do
                                                            (assertions
                                                              "Calls integrate-ident with appropriate args"
                                                              (map? s) => true
                                                              i => [:table :id]
                                                              op1 => :append
                                                              op2 => :replace)
                                                            s)

      (comp/get-ident c p) => [:table :id]
      (merge/merge! r d q) => (do
                                (assertions
                                  "merges the preprocessed data (which sweeps marked missing data)"
                                  d => :preprocessed-data)
                                :ignore)
      (app/schedule-render! a) => nil

      (merge/merge-component! (app/fulcro-app) MergeTestChild data :append [:children] :replace [:items 0]))))

(specification "integrate-ident"
  (let [state {:a    {:path [[:table 2]]}
               :b    {:path [[:table 2]]}
               :d    [:table 6]
               :many {:path [[:table 99] [:table 88] [:table 77]]}}]
    (assertions
      "Can append to an existing vector"
      (-> state
        (merge/integrate-ident* [:table 3] :append [:a :path])
        (get-in [:a :path]))
      => [[:table 2] [:table 3]]

      "Will append (create) on a non-existent vector"
      (-> state
        (merge/integrate-ident* [:table 3] :append [:a :missing])
        (get-in [:a :missing]))
      => [[:table 3]]

      "(is a no-op if the ident is already there)"
      (-> state
        (merge/integrate-ident* [:table 3] :append [:a :path])
        (get-in [:a :path]))
      => [[:table 2] [:table 3]]

      "Can prepend to an existing vector"
      (-> state
        (merge/integrate-ident* [:table 3] :prepend [:b :path])
        (get-in [:b :path]))
      => [[:table 3] [:table 2]]

      "Will prepend (create) on a non-existent vector"
      (-> state
        (merge/integrate-ident* [:table 3] :prepend [:a :missing])
        (get-in [:a :missing]))
      => [[:table 3]]

      "(is a no-op if already there)"
      (-> state
        (merge/integrate-ident* [:table 3] :prepend [:b :path])
        (get-in [:b :path]))
      => [[:table 3] [:table 2]]

      "Can create/replace a to-one ident"
      (-> state
        (merge/integrate-ident* [:table 3] :replace [:d])
        (get-in [:d]))
      => [:table 3]
      (-> state
        (merge/integrate-ident* [:table 3] :replace [:c :path])
        (get-in [:c :path]))
      => [:table 3]

      "Can replace an existing to-many element in a vector"
      (-> state
        (merge/integrate-ident* [:table 3] :replace [:many :path 1])
        (get-in [:many :path]))
      => [[:table 99] [:table 3] [:table 77]])))

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

;; Nested routing tree
;; NestedRoot
;;     |
;;     U1
;;    /  B    A = MergeRoot B = MergeB
;;    R2
;;   U2       A2
;;  X  Y

(defsc U2 [_ _]
  {:initial-state (fn [params] (comp/get-initial-state MergeX {}))
   :query         (fn [] {:x (comp/get-query MergeX) :y (comp/get-query MergeY)})})

(defsc R2 [_ _]
  {:initial-state (fn [params] {:id 1 :u2 (comp/get-initial-state U2 {})})
   :query         (fn [] [:id {:u2 (comp/get-query U2)}])})

(defsc U1 [_ _]
  {:initial-state (fn [params] (comp/get-initial-state MergeB {}))
   :query         (fn [] {:r2 (comp/get-query R2) :b (comp/get-query MergeB)})})

(defsc NestedRoot [_ _]
  {:initial-state (fn [params] {:u1 (comp/get-initial-state U1 {})})
   :query         (fn [] [{:u1 (comp/get-query U1)}])})

;; Sibling routing tree
;; SiblingRoot
;;     |   \
;;   SU1   SU2
;;  A   B  X  Y

(defsc SU1 [_ props]
  {:initial-state (fn [params] (comp/get-initial-state MergeB {}))
   :ident         (fn [] [(:type props) 1])
   :query         (fn [] {:a (comp/get-query MergeA) :b (comp/get-query MergeB)})})

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
   :ident (fn [] [:phone/by-id (:id props)])})

(defsc MPerson [_ props]
  {:query (fn [] [:id :name {:numbers (comp/get-query MPhone)}])
   :ident (fn [] [:person/by-id (:id props)])})

(defsc MPhonePM [_ _]
  {:ident     [:phone/by-id :id]
   :query     [:id :number]
   :pre-merge (fn [{:keys [current-normalized data-tree]}]
                (merge
                  {:ui/initial-flag :start}
                  current-normalized
                  data-tree))})

(defsc MPersonPM [_ props]
  {:ident [:person/by-id :id]
   :query [:id :name {:numbers (comp/get-query MPhonePM)}]})

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
        sally            {:id :sally :name "Sally" :numbers [[:phone/by-id 3]]}
        phone-3          {:id 3 :number "111-2222"}
        state-map        {:people       [[:person/by-id :sally]]
                          :phone/by-id  {3 phone-3}
                          :person/by-id {:sally sally}}
        new-state-map    (merge/merge-component state-map MPerson component-tree)
        expected-person  {:id :tony :name "Tony" :numbers [[:phone/by-id 1] [:phone/by-id 2]]}
        expected-phone-1 {:id 1 :number "555-1212"}
        expected-phone-2 {:id 2 :number "123-4555"}]
    (assertions
      "merges the top-level component with normalized links to children"
      (get-in new-state-map [:person/by-id :tony]) => expected-person
      "merges the normalized children"
      (get-in new-state-map [:phone/by-id 1]) => expected-phone-1
      (get-in new-state-map [:phone/by-id 2]) => expected-phone-2
      "leaves the original state untouched"
      (contains? new-state-map :people) => true
      (get-in new-state-map [:person/by-id :sally]) => sally
      (get-in new-state-map [:phone/by-id 3]) => phone-3))

  (assertions
    (merge/merge-component {} MPersonPM (person :mary "Mary" [(phone-number 55 "98765-4321")]))
    => {:person/by-id {:mary {:id      :mary
                              :name    "Mary"
                              :numbers [[:phone/by-id 55]]}}
        :phone/by-id  {55 {:id              55
                           :number          "98765-4321"
                           :ui/initial-flag :start}}}

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

    (merge/merge-component {} MPersonPM (person :mary "Mary" [(phone-number 55 "98765-4321")]) :replace [:main-person])
    => {:person/by-id {:mary {:id      :mary
                              :name    "Mary"
                              :numbers [[:phone/by-id 55]]}}
        :phone/by-id  {55 {:id              55
                           :number          "98765-4321"
                           :ui/initial-flag :start}}
        :main-person  [:person/by-id :mary]}

    (do
      (reset! id-counter 0)
      (merge/merge-component {} UiLoadedItem
        {::loaded-id 1
         ::name      "a"}))
    => {::loaded-id {1 {::loaded-id 1
                        ::name      "a"
                        :ui/item    [::id 1]}}
        ::id        {1 {::id 1}}}

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
