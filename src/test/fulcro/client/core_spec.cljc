(ns fulcro.client.core-spec
  (:require
    [om.next :as om :refer [defui]]
    [fulcro.client.core :as fc]
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [om.next.protocols :as omp]
    [clojure.core.async :as async]
    [fulcro.client.logging :as log]
    [fulcro.client.impl.om-plumbing :as plumbing]
    [fulcro.client.util :as util]))

(defui ^:once Child
  static om/Ident
  (ident [this props] [:child/by-id (:id props)])
  static om/IQuery
  (query [this] [:id :label]))

(defui ^:once Parent
  static fc/InitialAppState
  (fc/initial-state [this params] {:ui/checked true})
  static om/Ident
  (ident [this props] [:parent/by-id (:id props)])
  static om/IQuery
  (query [this] [:ui/checked :id :title {:child (om/get-query Child)}]))

#?(:cljs
   (specification "merge-state!"
     (assertions
       "merge-query is the component query joined on it's ident"
       (#'fc/component-merge-query Parent {:id 42}) => [{[:parent/by-id 42] [:ui/checked :id :title {:child (om/get-query Child)}]}])
     (component "preprocessing the object to merge"
       (let [no-state             (atom {:parent/by-id {}})
             no-state-merge-data  (:merge-data (#'fc/preprocess-merge no-state Parent {:id 42}))
             state-with-old       (atom {:parent/by-id {42 {:ui/checked true :id 42 :title "Hello"}}})
             id                   [:parent/by-id 42]
             old-state-merge-data (-> (#'fc/preprocess-merge state-with-old Parent {:id 42}) :merge-data :fulcro/merge)]
         (assertions
           "Uses the existing object in app state as base for merge when present"
           (get-in old-state-merge-data [id :ui/checked]) => true
           "Marks fields that were queried but are not present as plumbing/not-found"
           old-state-merge-data => {[:parent/by-id 42] {:id         42
                                                        :ui/checked true
                                                        :title      :fulcro.client.impl.om-plumbing/not-found
                                                        :child      :fulcro.client.impl.om-plumbing/not-found}}))
       (let [union-query {:union-a [:b] :union-b [:c]}
             state       (atom {})]
         (when-mocking
           (util/get-ident c d) => :ident
           (om/get-query comp) => union-query
           (fc/component-merge-query comp data) => :merge-query
           (om/db->tree q d r) => {:ident :data}
           (plumbing/mark-missing d q) => (do
                                            (assertions
                                              "wraps union queries in a vector"
                                              q => [union-query])

                                            {:ident :data})
           (util/deep-merge d1 d2) => :merge-result

           (#'fc/preprocess-merge state :comp :data))))
     (let [state (atom {})
           data  {}]
       (when-mocking
         (fc/preprocess-merge s c d) => {:merge-data :the-data :merge-query :the-query}
         (fc/integrate-ident! s i op args op args) => :ignore
         (util/get-ident c p) => [:table :id]
         (om/merge! r d q) => :ignore
         (om/app-state r) => state
         (omp/queue! r kw) => (assertions
                                "schedules re-rendering of all affected paths"
                                kw => [:children :items])

         (fc/merge-state! :reconciler :component data :append [:children] :replace [:items 0])))))

(specification "integrate-ident!"
  (let [state (atom {:a    {:path [[:table 2]]}
                     :b    {:path [[:table 2]]}
                     :d    [:table 6]
                     :many {:path [[:table 99] [:table 88] [:table 77]]}})]
    (behavior "Can append to an existing vector"
      (fc/integrate-ident! state [:table 3] :append [:a :path])
      (assertions
        (get-in @state [:a :path]) => [[:table 2] [:table 3]])
      (fc/integrate-ident! state [:table 3] :append [:a :path])
      (assertions
        "(is a no-op if the ident is already there)"
        (get-in @state [:a :path]) => [[:table 2] [:table 3]]))
    (behavior "Can prepend to an existing vector"
      (fc/integrate-ident! state [:table 3] :prepend [:b :path])
      (assertions
        (get-in @state [:b :path]) => [[:table 3] [:table 2]])
      (fc/integrate-ident! state [:table 3] :prepend [:b :path])
      (assertions
        "(is a no-op if already there)"
        (get-in @state [:b :path]) => [[:table 3] [:table 2]]))
    (behavior "Can create/replace a to-one ident"
      (fc/integrate-ident! state [:table 3] :replace [:c :path])
      (fc/integrate-ident! state [:table 3] :replace [:d])
      (assertions
        (get-in @state [:d]) => [:table 3]
        (get-in @state [:c :path]) => [:table 3]
        ))
    (behavior "Can replace an existing to-many element in a vector"
      (fc/integrate-ident! state [:table 3] :replace [:many :path 1])
      (assertions
        (get-in @state [:many :path]) => [[:table 99] [:table 3] [:table 77]]))))

(specification "integrate-ident"
  (let [state {:a    {:path [[:table 2]]}
               :b    {:path [[:table 2]]}
               :d    [:table 6]
               :many {:path [[:table 99] [:table 88] [:table 77]]}}]
    (assertions
      "Can append to an existing vector"
      (-> state
        (fc/integrate-ident [:table 3] :append [:a :path])
        (get-in [:a :path]))
      => [[:table 2] [:table 3]]

      "(is a no-op if the ident is already there)"
      (-> state
        (fc/integrate-ident [:table 3] :append [:a :path])
        (get-in [:a :path]))
      => [[:table 2] [:table 3]]

      "Can prepend to an existing vector"
      (-> state
        (fc/integrate-ident [:table 3] :prepend [:b :path])
        (get-in [:b :path]))
      => [[:table 3] [:table 2]]

      "(is a no-op if already there)"
      (-> state
        (fc/integrate-ident [:table 3] :prepend [:b :path])
        (get-in [:b :path]))
      => [[:table 3] [:table 2]]

      "Can create/replace a to-one ident"
      (-> state
        (fc/integrate-ident [:table 3] :replace [:d])
        (get-in [:d]))
      => [:table 3]
      (-> state
        (fc/integrate-ident [:table 3] :replace [:c :path])
        (get-in [:c :path]))
      => [:table 3]

      "Can replace an existing to-many element in a vector"
      (-> state
        (fc/integrate-ident [:table 3] :replace [:many :path 1])
        (get-in [:many :path]))
      => [[:table 99] [:table 3] [:table 77]])))

#?(:cljs
   (specification "Fulcro Application -- clear-pending-remote-requests!"
     (let [channel  (async/chan 1000)
           mock-app (fc/map->Application {:send-queues {:remote channel}})]
       (async/put! channel 1 #(async/put! channel 2 (fn [] (async/put! channel 3 (fn [] (async/put! channel 4))))))

       (fc/clear-pending-remote-requests! mock-app nil)

       (assertions
         "Removes any pending items in the network queue channel"
         (async/poll! channel) => nil))))

(defui ^:once BadResetAppRoot
  Object
  (render [this] nil))

(defui ^:once ResetAppRoot
  static fc/InitialAppState
  (initial-state [this params] {:x 1}))

#?(:cljs
   (specification "Fulcro Application -- reset-app!"
     (let [scb-calls        (atom 0)
           custom-calls     (atom 0)
           mock-app         (fc/map->Application {:send-queues      {:remote :fake-queue}
                                                  :started-callback (fn [] (swap! scb-calls inc))})
           cleared-network? (atom false)
           merged-unions?   (atom false)
           history-reset?   (atom false)
           re-rendered?     (atom false)
           state            (atom {})]
       (behavior "Logs an error if the supplied component does not implement InitialAppState"
         (when-mocking
           (log/error e) => (assertions
                              e => "The specified root component does not implement InitialAppState!")
           (fc/reset-app! mock-app BadResetAppRoot nil)))

       (behavior "On a proper app root"
         (when-mocking
           (fc/clear-queue t) => (reset! cleared-network? true)
           (om/app-state r) => state
           (fc/merge-alternate-union-elements! app r) => (reset! merged-unions? true)
           (fc/reset-history-impl a) => (reset! history-reset? true)
           (util/force-render a) => (reset! re-rendered? true)

           (fc/reset-app! mock-app ResetAppRoot nil)
           (fc/reset-app! mock-app ResetAppRoot :original)
           (fc/reset-app! mock-app ResetAppRoot (fn [a] (swap! custom-calls inc))))

         (assertions
           "Clears the network queue"
           @cleared-network? => true
           "Resets Om's app history"
           @history-reset? => true
           "Sets the base state from component"
           @state => {:x 1 :om.next/tables #{}}
           "Attempts to merge alternate union branches into state"
           @merged-unions? => true
           "Re-renders the app"
           @re-rendered? => true
           "Calls the original started-callback when callback is :original"
           @scb-calls => 1
           "Calls the supplied started-callback when callback is a function"
           @custom-calls => 1)))))

#?(:cljs
   (specification "Mounting a Fulcro Application"
     (let [mounted-mock-app {:mounted? true :initial-state {}}]
       (provided "When it is already mounted"
         (fc/refresh* a r t) =1x=> (do
                                     (assertions
                                       "Refreshes the UI"
                                       1 => 1)
                                     a)

         (fc/mount* mounted-mock-app :fake-root :dom-id)))
     (behavior "When is is not already mounted"
       (behavior "and root does NOT implement InitialAppState"
         (let [mock-app {:mounted? false :initial-state {:a 1} :reconciler-options :OPTIONS}]
           (when-mocking
             (fc/initialize app state root dom opts) => (do
                                                          (assertions
                                                            "Initializes the app with a plain map"
                                                            state => {:a 1}
                                                            ))

             (fc/mount* mock-app :fake-root :dom-id)))
         (let [supplied-atom (atom {:a 1})
               mock-app      {:mounted? false :initial-state supplied-atom :reconciler-options :OPTIONS}]
           (when-mocking
             (fc/initialize app state root dom opts) => (do
                                                          (assertions
                                                            "Initializes the app with a supplied atom"
                                                            {:a 1} => @state))

             (fc/mount* mock-app :fake-root :dom-id))))
       (behavior "and root IMPLEMENTS InitialAppState"
         (let [mock-app {:mounted? false :reconciler-options :OPTIONS}]
           (when-mocking
             (fc/initialize app state root dom opts) => (assertions
                                                          "Initializes the app with the InitialAppState"
                                                          state => (fc/get-initial-state Parent nil))

             (fc/mount* mock-app Parent :dom-id)))
         (let [mock-app {:mounted? false :initial-state {:EXPLICIT-STATE 1} :reconciler-options :OPTIONS}]
           (behavior "When both atom and InitialAppState are present:"
             (when-mocking
               (log/warn msg) =1x=> (assertions "warns about duplicate initialization"
                                      msg =fn=> (partial re-matches #"^You supplied.*"))
               (fc/initialize app state root dom opts) => (do
                                                            (assertions
                                                              "Prefers the *explicit* state"
                                                              state => {:EXPLICIT-STATE 1}))

               (fc/mount* mock-app Parent :dom-id))))
         (let [mock-app {:mounted? false :reconciler-options :OPTIONS}]
           (behavior "When only InitialAppState is present:"
             (when-mocking
               (fulcro.client.core/initial-state root-component nil) => :INITIAL-UI-STATE
               (fc/initialize app state root dom opts) => (do
                                                            (assertions
                                                              "Supplies the raw InitialAppState to internal initialize"
                                                              state => :INITIAL-UI-STATE))

               (fc/mount* mock-app Parent :dom-id))))))))


(defui ^:once MergeX
  static fc/InitialAppState
  (initial-state [this params] {:type :x :n :x})
  static om/IQuery
  (query [this] [:n :type]))

(defui ^:once MergeY
  static fc/InitialAppState
  (initial-state [this params] {:type :y :n :y})
  static om/IQuery
  (query [this] [:n :type]))


(defui ^:once MergeAChild
  static fc/InitialAppState
  (initial-state [this params] {:child :merge-a})
  static om/Ident
  (ident [this props] [:mergea :child])
  static om/IQuery
  (query [this] [:child]))

(defui ^:once MergeA
  static fc/InitialAppState
  (initial-state [this params] {:type :a :n :a :child (fc/get-initial-state MergeAChild nil)})
  static om/IQuery
  (query [this] [:type :n {:child (om/get-query MergeAChild)}]))

(defui ^:once MergeB
  static fc/InitialAppState
  (initial-state [this params] {:type :b :n :b})
  static om/IQuery
  (query [this] [:n]))

(defui ^:once MergeUnion
  static fc/InitialAppState
  (initial-state [this params] (fc/get-initial-state MergeA {}))
  static om/Ident
  (ident [this props] [:mergea-or-b :at-union])
  static om/IQuery
  (query [this] {:a (om/get-query MergeA) :b (om/get-query MergeB)}))

(defui ^:once MergeRoot
  static fc/InitialAppState
  (initial-state [this params] {:a 1 :b (fc/get-initial-state MergeUnion {})})
  static om/IQuery
  (query [this] [:a {:b (om/get-query MergeUnion)}]))

;; Nested routing tree
;; NestedRoot
;;     |
;;     U1
;;    /  B    A = MergeRoot B = MergeB
;;    R2
;;   U2       A2
;;  X  Y

(defui ^:once U2
  static fc/InitialAppState
  (initial-state [this params] (fc/get-initial-state MergeX {}))
  static om/IQuery
  (query [this] {:x (om/get-query MergeX) :y (om/get-query MergeY)}))

(defui ^:once R2
  static fc/InitialAppState
  (initial-state [this params] {:id 1 :u2 (fc/get-initial-state U2 {})})
  static om/IQuery
  (query [this] [:id {:u2 (om/get-query U2)}]))

(defui ^:once U1
  static fc/InitialAppState
  (initial-state [this params] (fc/get-initial-state MergeB {}))
  static om/IQuery
  (query [this] {:r2 (om/get-query R2) :b (om/get-query MergeB)}))

(defui ^:once NestedRoot
  static fc/InitialAppState
  (initial-state [this params] {:u1 (fc/get-initial-state U1 {})})
  static om/IQuery
  (query [this] [{:u1 (om/get-query U1)}]))

;; Sibling routing tree
;; SiblingRoot
;;     |   \
;;   SU1   SU2
;;  A   B  X  Y

(defui ^:once SU1
  static fc/InitialAppState
  (initial-state [this params] (fc/get-initial-state MergeB {}))
  static om/Ident
  (ident [this props] [(:type props) 1])
  static om/IQuery
  (query [this] {:a (om/get-query MergeA) :b (om/get-query MergeB)}))

(defui ^:once SU2
  static fc/InitialAppState
  (initial-state [this params] (fc/get-initial-state MergeX {}))
  static om/Ident
  (ident [this props] [(:type props) 2])
  static om/IQuery
  (query [this] {:x (om/get-query MergeX) :y (om/get-query MergeY)}))


(defui ^:once SiblingRoot
  static fc/InitialAppState
  (initial-state [this params] {:su1 (fc/get-initial-state SU1 {}) :su2 (fc/get-initial-state SU2 {})})
  static om/IQuery
  (query [this] [{:su1 (om/get-query SU1)} {:su2 (om/get-query SU2)}]))

(specification "merge-alternate-union-elements!"
  (behavior "For applications with sibling unions"
    (when-mocking
      (fc/merge-state! app comp state) =1x=> (do
                                               (assertions
                                                 "Merges level one elements"
                                                 comp => SU1
                                                 state => (fc/get-initial-state MergeA {})))
      (fc/merge-state! app comp state) =1x=> (do
                                               (assertions
                                                 "Merges only the state of branches that are not already initialized"
                                                 comp => SU2
                                                 state => (fc/get-initial-state MergeY {})))

      (fc/merge-alternate-union-elements! :app SiblingRoot)))

  (behavior "For applications with nested unions"
    (when-mocking
      (fc/merge-state! app comp state) =1x=> (do
                                               (assertions
                                                 "Merges level one elements"
                                                 comp => U1
                                                 state => (fc/get-initial-state R2 {})))
      (fc/merge-state! app comp state) =1x=> (do
                                               (assertions
                                                 "Merges only the state of branches that are not already initialized"
                                                 comp => U2
                                                 state => (fc/get-initial-state MergeY {})))

      (fc/merge-alternate-union-elements! :app NestedRoot)))
  (behavior "For applications with non-nested unions"
    (when-mocking
      (fc/merge-state! app comp state) => (do
                                            (assertions
                                              "Merges only the state of branches that are not already initialized"
                                              comp => MergeUnion
                                              state => (fc/get-initial-state MergeB {})))

      (fc/merge-alternate-union-elements! :app MergeRoot))))

(defn phone-number [id n] {:id id :number n})
(defn person [id name numbers] {:id id :name name :numbers numbers})

(defui MPhone
  static om/IQuery
  (query [this] [:id :number])
  static om/Ident
  (ident [this props] [:phone/by-id (:id props)]))

(defui MPerson
  static om/IQuery
  (query [this] [:id :name {:numbers (om/get-query MPhone)}])
  static om/Ident
  (ident [this props] [:person/by-id (:id props)]))

(specification "merge-component"
  (let [component-tree   (person :tony "Tony" [(phone-number 1 "555-1212") (phone-number 2 "123-4555")])
        sally            {:id :sally :name "Sally" :numbers [[:phone/by-id 3]]}
        phone-3          {:id 3 :number "111-2222"}
        state-map        {:people       [[:person/by-id :sally]]
                          :phone/by-id  {3 phone-3}
                          :person/by-id {:sally sally}}
        new-state-map    (fc/merge-component state-map MPerson component-tree)
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
      (get-in new-state-map [:phone/by-id 3]) => phone-3)))

(def table-1 {:type :table :id 1 :rows [1 2 3]})
(defui Table
  static fc/InitialAppState
  (initial-state [c p] table-1)
  static om/IQuery
  (query [this] [:type :id :rows]))

(def graph-1 {:type :graph :id 1 :data [1 2 3]})
(defui Graph
  static fc/InitialAppState
  (initial-state [c p] graph-1)
  static om/IQuery
  (query [this] [:type :id :data]))

(defui Reports
  static fc/InitialAppState
  (initial-state [c p] (fc/get-initial-state Graph nil))    ; initial state will already include Graph
  static om/Ident
  (ident [this props] [(:type props) (:id props)])
  static om/IQuery
  (query [this] {:graph (om/get-query Graph) :table (om/get-query Table)}))

(defui MRRoot
  static fc/InitialAppState
  (initial-state [c p] {:reports (fc/get-initial-state Reports nil)})
  static om/IQuery
  (query [this] [{:reports (om/get-query Reports)}]))

(specification "merge-alternate-union-elements"
  (let [initial-state (merge (fc/get-initial-state MRRoot nil) {:a 1})
        state-map     (om/tree->db MRRoot initial-state true)
        new-state     (fc/merge-alternate-union-elements state-map MRRoot)]
    (assertions
      "can be used to merge alternate union elements to raw state"
      (get-in new-state [:table 1]) => table-1
      "(existing state isn't touched)"
      (get new-state :a) => 1
      (get new-state :reports) => [:graph 1]
      (get-in new-state [:graph 1]) => graph-1)))
