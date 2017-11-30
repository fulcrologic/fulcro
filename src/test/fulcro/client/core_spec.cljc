(ns fulcro.client.core-spec
  (:require
    [fulcro.client.primitives :as prim :refer [defui defsc]]
    [fulcro.client.core :as fc]
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [fulcro.client.impl.protocols :as omp]
    [clojure.core.async :as async]
    [fulcro.client.logging :as log]
    [fulcro.client.util :as fcu]
    [fulcro.util :as util])
  #?(:clj
     (:import (clojure.lang ExceptionInfo))))

(defui ^:once Child
  static prim/Ident
  (ident [this props] [:child/by-id (:id props)])
  static prim/IQuery
  (query [this] [:id :label]))

(defui ^:once Parent
  static prim/InitialAppState
  (fc/initial-state [this params] {:ui/checked true})
  static prim/Ident
  (ident [this props] [:parent/by-id (:id props)])
  static prim/IQuery
  (query [this] [:ui/checked :id :title {:child (prim/get-query Child)}]))

#?(:cljs
   (specification "merge-state!"
     (assertions
       "merge-query is the component query joined on it's ident"
       (#'fc/component-merge-query Parent {:id 42}) => [{[:parent/by-id 42] [:ui/checked :id :title {:child (prim/get-query Child)}]}])
     (component "preprocessing the object to merge"
       (let [no-state             (atom {:parent/by-id {}})
             no-state-merge-data  (:merge-data (#'fc/preprocess-merge no-state Parent {:id 42}))
             state-with-old       (atom {:parent/by-id {42 {:ui/checked true :id 42 :title "Hello"}}})
             id                   [:parent/by-id 42]
             old-state-merge-data (-> (#'fc/preprocess-merge state-with-old Parent {:id 42}) :merge-data :fulcro/merge)]
         (assertions
           "Uses the existing object in app state as base for merge when present"
           (get-in old-state-merge-data [id :ui/checked]) => true
           "Marks fields that were queried but are not present as prim/not-found"
           old-state-merge-data => {[:parent/by-id 42] {:id         42
                                                        :ui/checked true
                                                        :title      ::prim/not-found
                                                        :child      ::prim/not-found}}))
       (let [union-query {:union-a [:b] :union-b [:c]}
             state       (atom {})]
         (when-mocking
           (prim/get-ident c d) => :ident
           (prim/get-query comp) => union-query
           (fc/component-merge-query comp data) => :merge-query
           (prim/db->tree q d r) => {:ident :data}
           (prim/mark-missing d q) => (do
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
         (prim/get-ident c p) => [:table :id]
         (prim/merge! r d q) => :ignore
         (prim/app-state r) => state
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
      "Can set to a given path"
      (-> state
          (fc/integrate-ident [:table 3] :set [:d])
          (get-in [:d]))
      => [:table 3]

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
  static prim/InitialAppState
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
           (prim/app-state r) => state
           (fc/merge-alternate-union-elements! app r) => (reset! merged-unions? true)
           (fc/reset-history-impl a) => (reset! history-reset? true)
           (fcu/force-render a) => (reset! re-rendered? true)

           (fc/reset-app! mock-app ResetAppRoot nil)
           (fc/reset-app! mock-app ResetAppRoot :original)
           (fc/reset-app! mock-app ResetAppRoot (fn [a] (swap! custom-calls inc))))

         (assertions
           "Clears the network queue"
           @cleared-network? => true
           "Resets app history"
           @history-reset? => true
           "Sets the base state from component"
           @state => {:x 1}
           "Attempts to merge alternate union branches into state"
           @merged-unions? => true
           "Re-renders the app"
           @re-rendered? => true
           "Calls the original started-callback when callback is :original"
           @scb-calls => 1
           "Calls the supplied started-callback when callback is a function"
           @custom-calls => 1)))))

(defui RootNoState Object (render [this]))
(defui RootWithState
  static prim/InitialAppState
  (initial-state [c p] {:a 1})
  Object
  (render [this]))

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
         (let [supplied-state       {:a 1}
               app-with-initial-map {:mounted? false :initial-state supplied-state :reconciler-options :OPTIONS}]
           (when-mocking
             (fc/initialize app state root dom opts) => (do
                                                          (assertions
                                                            "Initializes the app with a plain map"
                                                            state => supplied-state))

             (fc/mount* app-with-initial-map RootNoState :dom-id)))
         (let [supplied-atom         (atom {:a 1})
               app-with-initial-atom {:mounted? false :initial-state supplied-atom :reconciler-options :OPTIONS}]
           (when-mocking
             (fc/initialize app state root dom opts) => (do
                                                          (assertions
                                                            "Initializes the app with a supplied atom"
                                                            (identical? state supplied-atom) => true))

             (fc/mount* app-with-initial-atom RootNoState :dom-id))))
       (behavior "and root IMPLEMENTS InitialAppState"
         (let [mock-app {:mounted? false :initial-state {} :reconciler-options :OPTIONS}]
           (when-mocking
             (fc/initialize app state root dom opts) => (assertions
                                                          "Initializes the app with the InitialAppState if the supplied state is empty"
                                                          state => (prim/get-initial-state RootWithState nil))

             (fc/mount* mock-app RootWithState :dom-id)))
         (let [explicit-non-empty-map {:a 1}
               mock-app               {:mounted? false :initial-state explicit-non-empty-map :reconciler-options :OPTIONS}]
           (behavior "When an explicit non-empty map and InitialAppState are present:"
             (when-mocking
               (fc/initialize app state root dom opts) => (do
                                                            (assertions
                                                              "Prefers the *explicit* state"
                                                              (identical? state explicit-non-empty-map) => true))

               (fc/mount* mock-app RootWithState :dom-id))))
         (let [supplied-atom (atom {})
               mock-app      {:mounted? false :initial-state supplied-atom :reconciler-options :OPTIONS}]
           (behavior "When an explicit atom and InitialAppState are present:"
             (when-mocking
               (fc/initialize app state root dom opts) => (do
                                                            (assertions
                                                              "Prefers the *explicit* state"
                                                              (identical? state supplied-atom) => true))

               (fc/mount* mock-app RootWithState :dom-id))))
         (let [mock-app {:mounted? false :reconciler-options :OPTIONS}]
           (behavior "When only InitialAppState is present:"
             (when-mocking
               (fc/initialize app state root dom opts) => (do
                                                            (assertions
                                                              "Supplies the raw InitialAppState to internal initialize"
                                                              state => (prim/get-initial-state RootWithState nil)))

               (fc/mount* mock-app RootWithState :dom-id))))))))


(defui ^:once MergeX
  static prim/InitialAppState
  (initial-state [this params] {:type :x :n :x})
  static prim/IQuery
  (query [this] [:n :type]))

(defui ^:once MergeY
  static prim/InitialAppState
  (initial-state [this params] {:type :y :n :y})
  static prim/IQuery
  (query [this] [:n :type]))


(defui ^:once MergeAChild
  static prim/InitialAppState
  (initial-state [this params] {:child :merge-a})
  static prim/Ident
  (ident [this props] [:mergea :child])
  static prim/IQuery
  (query [this] [:child]))

(defui ^:once MergeA
  static prim/InitialAppState
  (initial-state [this params] {:type :a :n :a :child (prim/get-initial-state MergeAChild nil)})
  static prim/IQuery
  (query [this] [:type :n {:child (prim/get-query MergeAChild)}]))

(defui ^:once MergeB
  static prim/InitialAppState
  (initial-state [this params] {:type :b :n :b})
  static prim/IQuery
  (query [this] [:n]))

(defui ^:once MergeUnion
  static prim/InitialAppState
  (initial-state [this params] (prim/get-initial-state MergeA {}))
  static prim/Ident
  (ident [this props] [:mergea-or-b :at-union])
  static prim/IQuery
  (query [this] {:a (prim/get-query MergeA) :b (prim/get-query MergeB)}))

(defui ^:once MergeRoot
  static prim/InitialAppState
  (initial-state [this params] {:a 1 :b (prim/get-initial-state MergeUnion {})})
  static prim/IQuery
  (query [this] [:a {:b (prim/get-query MergeUnion)}]))

;; Nested routing tree
;; NestedRoot
;;     |
;;     U1
;;    /  B    A = MergeRoot B = MergeB
;;    R2
;;   U2       A2
;;  X  Y

(defui ^:once U2
  static prim/InitialAppState
  (initial-state [this params] (prim/get-initial-state MergeX {}))
  static prim/IQuery
  (query [this] {:x (prim/get-query MergeX) :y (prim/get-query MergeY)}))

(defui ^:once R2
  static prim/InitialAppState
  (initial-state [this params] {:id 1 :u2 (prim/get-initial-state U2 {})})
  static prim/IQuery
  (query [this] [:id {:u2 (prim/get-query U2)}]))

(defui ^:once U1
  static prim/InitialAppState
  (initial-state [this params] (prim/get-initial-state MergeB {}))
  static prim/IQuery
  (query [this] {:r2 (prim/get-query R2) :b (prim/get-query MergeB)}))

(defui ^:once NestedRoot
  static prim/InitialAppState
  (initial-state [this params] {:u1 (prim/get-initial-state U1 {})})
  static prim/IQuery
  (query [this] [{:u1 (prim/get-query U1)}]))

;; Sibling routing tree
;; SiblingRoot
;;     |   \
;;   SU1   SU2
;;  A   B  X  Y

(defui ^:once SU1
  static prim/InitialAppState
  (initial-state [this params] (prim/get-initial-state MergeB {}))
  static prim/Ident
  (ident [this props] [(:type props) 1])
  static prim/IQuery
  (query [this] {:a (prim/get-query MergeA) :b (prim/get-query MergeB)}))

(defui ^:once SU2
  static prim/InitialAppState
  (initial-state [this params] (prim/get-initial-state MergeX {}))
  static prim/Ident
  (ident [this props] [(:type props) 2])
  static prim/IQuery
  (query [this] {:x (prim/get-query MergeX) :y (prim/get-query MergeY)}))


(defui ^:once SiblingRoot
  static prim/InitialAppState
  (initial-state [this params] {:su1 (prim/get-initial-state SU1 {}) :su2 (prim/get-initial-state SU2 {})})
  static prim/IQuery
  (query [this] [{:su1 (prim/get-query SU1)} {:su2 (prim/get-query SU2)}]))

(specification "merge-alternate-union-elements!"
  (behavior "For applications with sibling unions"
    (when-mocking
      (fc/merge-state! app comp state) =1x=> (do
                                               (assertions
                                                 "Merges level one elements"
                                                 comp => SU1
                                                 state => (prim/get-initial-state MergeA {})))
      (fc/merge-state! app comp state) =1x=> (do
                                               (assertions
                                                 "Merges only the state of branches that are not already initialized"
                                                 comp => SU2
                                                 state => (prim/get-initial-state MergeY {})))

      (fc/merge-alternate-union-elements! :app SiblingRoot)))

  (behavior "For applications with nested unions"
    (when-mocking
      (fc/merge-state! app comp state) =1x=> (do
                                               (assertions
                                                 "Merges level one elements"
                                                 comp => U1
                                                 state => (prim/get-initial-state R2 {})))
      (fc/merge-state! app comp state) =1x=> (do
                                               (assertions
                                                 "Merges only the state of branches that are not already initialized"
                                                 comp => U2
                                                 state => (prim/get-initial-state MergeY {})))

      (fc/merge-alternate-union-elements! :app NestedRoot)))
  (behavior "For applications with non-nested unions"
    (when-mocking
      (fc/merge-state! app comp state) => (do
                                            (assertions
                                              "Merges only the state of branches that are not already initialized"
                                              comp => MergeUnion
                                              state => (prim/get-initial-state MergeB {})))

      (fc/merge-alternate-union-elements! :app MergeRoot))))

(defn phone-number [id n] {:id id :number n})
(defn person [id name numbers] {:id id :name name :numbers numbers})

(defui MPhone
  static prim/IQuery
  (query [this] [:id :number])
  static prim/Ident
  (ident [this props] [:phone/by-id (:id props)]))

(defui MPerson
  static prim/IQuery
  (query [this] [:id :name {:numbers (prim/get-query MPhone)}])
  static prim/Ident
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
  static prim/InitialAppState
  (initial-state [c p] table-1)
  static prim/IQuery
  (query [this] [:type :id :rows]))

(def graph-1 {:type :graph :id 1 :data [1 2 3]})
(defui Graph
  static prim/InitialAppState
  (initial-state [c p] graph-1)
  static prim/IQuery
  (query [this] [:type :id :data]))

(defui Reports
  static prim/InitialAppState
  (initial-state [c p] (prim/get-initial-state Graph nil))    ; initial state will already include Graph
  static prim/Ident
  (ident [this props] [(:type props) (:id props)])
  static prim/IQuery
  (query [this] {:graph (prim/get-query Graph) :table (prim/get-query Table)}))

(defui MRRoot
  static prim/InitialAppState
  (initial-state [c p] {:reports (prim/get-initial-state Reports nil)})
  static prim/IQuery
  (query [this] [{:reports (prim/get-query Reports)}]))

(specification "merge-alternate-union-elements"
  (let [initial-state (merge (prim/get-initial-state MRRoot nil) {:a 1})
        state-map     (prim/tree->db MRRoot initial-state true)
        new-state     (fc/merge-alternate-union-elements state-map MRRoot)]
    (assertions
      "can be used to merge alternate union elements to raw state"
      (get-in new-state [:table 1]) => table-1
      "(existing state isn't touched)"
      (get new-state :a) => 1
      (get new-state :reports) => [:graph 1]
      (get-in new-state [:graph 1]) => graph-1)))


