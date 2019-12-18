(ns com.fulcrologic.fulcro.algorithms.merge-spec
  (:require
    [clojure.test :refer [deftest are]]
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

(defsc UiPreMergePlaceholderChild
  [_ _]
  {:pre-merge (fn [{:keys [current-normalized data-tree]}]
                (let [id (or (:id data-tree)
                           (:id current-normalized)
                           #?(:clj (java.util.UUID/randomUUID) :cljs (random-uuid)))]
                  (merge
                    {:id id}
                    current-normalized
                    data-tree)))
   :ident     :id
   :query     [:id :child/value]})

(defsc UiPreMergePlaceholderRoot
  [_ _]
  {:pre-merge (fn [{:keys [current-normalized data-tree]}]
                (let [id (or (:id data-tree)
                           (:id current-normalized)
                           #?(:clj (java.util.UUID/randomUUID) :cljs (random-uuid)))]
                  (merge
                    {:child/value 321
                     :id          id
                     :child       {:id id}}
                    current-normalized
                    data-tree)))
   :ident     :id
   :query     [:id
               {:child (comp/get-query UiPreMergePlaceholderChild)}]})

(specification "merge*"
  (assertions
    "keep data defined by root merge component."
    (merge/merge* {} [{[:id 42] (comp/get-query UiPreMergePlaceholderRoot)}]
      {[:id 42] {:id 42}}
      {:remove-missing? true})
    => {:id {42 {:id          42
                 :child/value 321
                 :child       [:id 42]}}}

    "merge parent and children new data"
    (merge/merge* {} [{[:id 42] (comp/get-query UiPreMergePlaceholderRoot)}]
      {[:id 42] {:id    42
                 :child {:id          42
                         :child/value 123}}}
      {:remove-missing? true})
    => {:id {42 {:id          42
                 :child       [:id 42]
                 :child/value 123}}}))

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
  (let [rendered (atom false)
        ;; needed because mocking cannot mock something you've closed over already
        app      (assoc-in (app/fulcro-app {})
                   [::app/algorithms :com.fulcrologic.fulcro.algorithm/schedule-render!] (fn [& args] (reset! rendered true)))]
    (when-mocking
      (merge/merge-component s c d & np) => (do
                                              (assertions
                                                "calls merge-component with the component and data"
                                                c => MPerson
                                                d => {}
                                                "includes the correct named parameters"
                                                np => [:replace [:x]]))

      (merge/merge-component! app MPerson {} :replace [:x])
      (assertions
        "schedules a render"
        @rendered => true))))

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

(specification "mark-missing"
  (behavior "correctly marks missing properties"
    (are [query ?missing-result exp]
      (= exp (merge/mark-missing ?missing-result query))
      [:a :b]
      {:a 1}
      {:a 1 :b ::merge/not-found}))

  (behavior "joins -> one"
    (are [query ?missing-result exp]
      (= exp (merge/mark-missing ?missing-result query))
      [:a {:b [:c]}]
      {:a 1}
      {:a 1 :b ::merge/not-found}

      [{:b [:c]}]
      {:b {}}
      {:b {:c ::merge/not-found}}

      [{:b [:c]}]
      {:b {:c 0}}
      {:b {:c 0}}

      [{:b [:c :d]}]
      {:b {:c 1}}
      {:b {:c 1 :d ::merge/not-found}}))

  (behavior "join -> many"
    (are [query ?missing-result exp]
      (= exp (merge/mark-missing ?missing-result query))

      [{:a [:b :c]}]
      {:a [{:b 1 :c 2} {:b 1}]}
      {:a [{:b 1 :c 2} {:b 1 :c ::merge/not-found}]}))

  (behavior "idents and ident joins"
    (are [query ?missing-result exp]
      (= exp (merge/mark-missing ?missing-result query))
      [{[:a 1] [:x]}]
      {[:a 1] {}}
      {[:a 1] {:x ::merge/not-found}}

      [{[:b 1] [:x]}]
      {[:b 1] {:x 2}}
      {[:b 1] {:x 2}}

      [{[:c 1] [:x]}]
      {}
      {[:c 1] {:x ::merge/not-found}}

      [{[:c 1] ['*]}]
      {}
      {[:c 1] {}}


      [{[:e 1] [:x :y :z]}]
      {[:e 1] {}}
      {[:e 1] {:x ::merge/not-found
               :y ::merge/not-found
               :z ::merge/not-found}}

      [[:d 1]]
      {}
      {[:d 1] {}}))

  (behavior "Ignores root link idents"
    (assertions
      "when the subquery exists"
      (merge/mark-missing {} [{[:a '_] [:x]}]) => {}
      "when it is a pure link"
      (merge/mark-missing {} [[:a '_]]) => {}))

  (behavior "parameterized"
    (are [query ?missing-result exp]
      (= exp (merge/mark-missing ?missing-result query))
      '[:z (:y {})]
      {:z 1}
      {:z 1 :y ::merge/not-found}

      '[:z (:y {})]
      {:z 1 :y 0}
      {:z 1 :y 0}

      '[:z ({:y [:x]} {})]
      {:z 1 :y {}}
      {:z 1 :y {:x ::merge/not-found}}))

  (behavior "nested"
    (are [query ?missing-result exp]
      (= exp (merge/mark-missing ?missing-result query))
      [{:b [:c {:d [:e]}]}]
      {:b {:c 1}}
      {:b {:c 1 :d ::merge/not-found}}

      [{:b [:c {:d [:e]}]}]
      {:b {:c 1 :d {}}}
      {:b {:c 1 :d {:e ::merge/not-found}}}))

  (behavior "upgrades value to maps if necessary"
    (are [query ?missing-result exp]
      (= exp (merge/mark-missing ?missing-result query))
      [{:l [:m]}]
      {:l 0}
      {:l {:m ::merge/not-found}}

      [{:b [:c]}]
      {:b nil}
      {:b {:c ::merge/not-found}}))

  (behavior "unions"
    (assertions
      "singletons"
      (merge/mark-missing {:j {:c {}}} [{:j {:a [:c] :b [:d]}}]) => {:j {:c {} :d ::merge/not-found}}

      "singleton with no result"
      (merge/mark-missing {} [{:j {:a [:c] :b [:d]}}]) => {:j ::merge/not-found}

      "list to-many with 1"
      (merge/mark-missing {:j [{:c "c"}]} [{:j {:a [:c] :b [:d]}}]) => {:j [{:c "c" :d ::merge/not-found}]}

      "list to-many with 2"
      (merge/mark-missing {:items [{:id 0 :image "img1"} {:id 1 :text "text1"}]} [{:items {:photo [:id :image] :text [:id :text]}}]) => {:items [{:id 0 :image "img1" :text ::merge/not-found} {:id 1 :image ::merge/not-found :text "text1"}]}

      "list to-many with no results"
      (merge/mark-missing {:j []} [{:j {:a [:c] :b [:d]}}]) => {:j []}))

  (behavior "if the query has a ui.*/ attribute, it should not be marked as missing"
    (are [query ?missing-result exp]
      (= exp (merge/mark-missing ?missing-result query))

      [:a :ui/b :c]
      {:a {}
       :c {}}
      {:a {}
       :c {}}

      [{:j [:ui/b :c]}]
      {:j {:c 5}}
      {:j {:c 5}}

      [{:j [{:ui/b [:d]} :c]}]
      {:j {:c 5}}
      {:j {:c 5}}))

  (behavior "mutations!"
    (are [query ?missing-result exp]
      (= exp (merge/mark-missing ?missing-result query))

      '[(f) {:j [:a]}]
      {'f {}
       :j {}}
      {'f {}
       :j {:a ::merge/not-found}}

      '[(app/add-q {:p 1}) {:j1 [:p1]} {:j2 [:p2]}]
      {'app/add-q {:tempids {}}
       :j1        {}
       :j2        [{:p2 2} {}]}
      {'app/add-q {:tempids {}}
       :j1        {:p1 ::merge/not-found}
       :j2        [{:p2 2} {:p2 ::merge/not-found}]}))

  (behavior "correctly walks recursive queries to mark missing data"
    (behavior "when the recursive target is a singleton"
      (are [query ?missing-result exp]
        (= exp (merge/mark-missing ?missing-result query))
        [:a {:b '...}]
        {:a 1 :b {:a 2}}
        {:a 1 :b {:a 2 :b ::merge/not-found}}

        [:a {:b '...}]
        {:a 1 :b {:a 2 :b {:a 3}}}
        {:a 1 :b {:a 2 :b {:a 3 :b ::merge/not-found}}}

        [:a {:b 9}]
        {:a 1 :b {:a 2 :b {:a 3 :b {:a 4}}}}
        {:a 1 :b {:a 2 :b {:a 3 :b {:a 4 :b ::merge/not-found}}}}))
    (behavior "when the recursive target is to-many"
      (are [query ?missing-result exp]
        (= exp (merge/mark-missing ?missing-result query))
        [:a {:b '...}]
        {:a 1 :b [{:a 2 :b [{:a 3}]}
                  {:a 4}]}
        {:a 1 :b [{:a 2 :b [{:a 3 :b ::merge/not-found}]}
                  {:a 4 :b ::merge/not-found}]})))
  (behavior "marks leaf data based on the query where"
    (letfn [(has-leaves [leaf-paths] (fn [result] (every? #(#'merge/leaf? (get-in result %)) leaf-paths)))]
      (assertions
        "plain data is always a leaf"
        (merge/mark-missing {:a 1 :b {:x 5}} [:a {:b [:x]}]) =fn=> (has-leaves [[:b :x] [:a] [:missing]])
        "data structures are properly marked in singleton results"
        (merge/mark-missing {:b {:x {:data 1}}} [{:b [:x :y]}]) =fn=> (has-leaves [[:b :x]])
        "data structures are properly marked in to-many results"
        (merge/mark-missing {:b [{:x {:data 1}} {:x {:data 2}}]} [{:b [:x]}]) =fn=> (has-leaves [[:b 0 :x] [:b 1 :x]])
        (merge/mark-missing {:b []} [:a {:b [:x]}]) =fn=> (has-leaves [[:b]])
        "unions are followed"
        (merge/mark-missing {:a [{:x {:data 1}} {:y {:data 2}}]} [{:a {:b [:x] :c [:y]}}]) =fn=> (has-leaves [[:a 0 :x] [:a 1 :y]])
        "unions leaves data in place when the result is empty"
        (merge/mark-missing {:a 1} [:a {:z {:b [:x] :c [:y]}}]) =fn=> (has-leaves [[:a]])))))

(specification "Sweep one"
  (assertions
    "removes not-found values from maps"
    (#'merge/sweep-one {:a 1 :b ::merge/not-found}) => {:a 1}
    "removes tempids from maps"
    (#'merge/sweep-one {:tempids {3 4}}) => {}
    "is not recursive"
    (#'merge/sweep-one {:a 1 :b {:c ::merge/not-found}}) => {:a 1 :b {:c ::merge/not-found}}
    "maps over vectors not recursive"
    (#'merge/sweep-one [{:a 1 :b ::merge/not-found}]) => [{:a 1}]
    "retains metadata"
    (-> (#'merge/sweep-one (with-meta {:a 1 :b ::merge/not-found} {:meta :data}))
      meta) => {:meta :data}
    (-> (#'merge/sweep-one [(with-meta {:a 1 :b ::merge/not-found} {:meta :data})])
      first meta) => {:meta :data}
    (-> (#'merge/sweep-one (with-meta [{:a 1 :b ::merge/not-found}] {:meta :data}))
      meta) => {:meta :data}))

(specification "Sweep merge"
  (assertions
    "recursively merges maps"
    (merge/sweep-merge {:a 1 :c {:b 2}} {:a 2 :c 5}) => {:a 2 :c 5}
    (merge/sweep-merge {:a 1 :c {:b 2}} {:a 2 :c {:x 1}}) => {:a 2 :c {:b 2 :x 1}}
    "stops recursive merging if the source element is marked as a leaf"
    (merge/sweep-merge {:a 1 :c {:d {:x 2} :e 4}} {:a 2 :c (#'merge/as-leaf {:d {:x 1}})}) => {:a 2 :c {:d {:x 1}}}
    "sweeps tempids from maps"
    (merge/sweep-merge {:a 1 :c {:b 2}} {:a 2 :tempids {} :c {:b ::merge/not-found}}) => {:a 2 :c {}}
    "Merging into a sub-map should remove the explicitly marked keys"
    (merge/sweep-merge {:a 1 :c {:x 1 :b 42}} {:a 2 :c ::merge/not-found}) => {:a 2}
    (merge/sweep-merge {:a 1 :c {:x 1 :b 42}} {:a 2 :c {:b ::merge/not-found}}) => {:a 2 :c {:x 1}}
    (merge/sweep-merge {:a 1 :c {:x 1 :b 42}} {:a 2 :c {:x ::merge/not-found}}) => {:a 2 :c {:b 42}}
    "Merging from an empty map should leave the original unmodified"
    (merge/sweep-merge {:a 1 :c {:x 1 :b 42}} {:a 2 :c {}}) => {:a 2 :c {:x 1 :b 42}}
    "removes values that are marked as not found"
    (merge/sweep-merge {:a 1 :c {:b 2}} {:a 2 :c {:b ::merge/not-found}}) => {:a 2 :c {}}
    (merge/sweep-merge {:a 1 :c 2} {:a 2 :c [{:x 1 :b ::merge/not-found}]}) => {:a 2 :c [{:x 1}]}
    (merge/sweep-merge {:a 1 :c {:data-fetch :loading}} {:a 2 :c [{:x 1 :b ::merge/not-found}]}) => {:a 2 :c [{:x 1}]}
    (merge/sweep-merge {:a 1 :c nil} {:a 2 :c [{:x 1 :b ::merge/not-found}]}) => {:a 2 :c [{:x 1}]}
    (merge/sweep-merge {:a 1 :b {:c {}}} {:a 2 :b {:c [{:x 1 :b ::merge/not-found}]}})
    => {:a 2 :b {:c [{:x 1}]}}
    "clears normalized table entries that has an id of not found"
    (merge/sweep-merge {:table {1 {:a 2}}} {:table {::merge/not-found {:db/id ::merge/not-found}}}) => {:table {1 {:a 2}}}
    "clears idents whose ids were not found"
    (merge/sweep-merge {} {:table {1 {:db/id 1 :the-thing [:table-1 ::merge/not-found]}}
                           :thing [:table-2 ::merge/not-found]}) => {:table {1 {:db/id 1}}}
    "sweeps not-found values from normalized table merges"
    (merge/sweep-merge {:subpanel  [:dashboard :panel]
                        :dashboard {:panel {:view-mode :detail :surveys {:ui/fetch-state {:post-mutation 's}}}}
                        }
      {:subpanel  [:dashboard :panel]
       :dashboard {:panel {:view-mode :detail :surveys [[:s 1] [:s 2]]}}
       :s         {
                   1 {:db/id 1, :survey/launch-date ::merge/not-found}
                   2 {:db/id 2, :survey/launch-date "2012-12-22"}
                   }}) => {:subpanel  [:dashboard :panel]
                           :dashboard {:panel {:view-mode :detail :surveys [[:s 1] [:s 2]]}}
                           :s         {
                                       1 {:db/id 1}
                                       2 {:db/id 2 :survey/launch-date "2012-12-22"}
                                       }}
    "overwrites target (non-map) value if incoming value is a map"
    (merge/sweep-merge {:a 1 :c 2} {:a 2 :c {:b 1}}) => {:a 2 :c {:b 1}}))

