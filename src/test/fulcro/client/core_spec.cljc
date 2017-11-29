(ns fulcro.client.core-spec
  (:require
    [fulcro.client.primitives :as prim :refer [defui]]
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
  static fc/InitialAppState
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
  static fc/InitialAppState
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
                                                          state => (fc/get-initial-state RootWithState nil))

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
                                                              state => (fc/get-initial-state RootWithState nil)))

               (fc/mount* mock-app RootWithState :dom-id))))))))


(defui ^:once MergeX
  static fc/InitialAppState
  (initial-state [this params] {:type :x :n :x})
  static prim/IQuery
  (query [this] [:n :type]))

(defui ^:once MergeY
  static fc/InitialAppState
  (initial-state [this params] {:type :y :n :y})
  static prim/IQuery
  (query [this] [:n :type]))


(defui ^:once MergeAChild
  static fc/InitialAppState
  (initial-state [this params] {:child :merge-a})
  static prim/Ident
  (ident [this props] [:mergea :child])
  static prim/IQuery
  (query [this] [:child]))

(defui ^:once MergeA
  static fc/InitialAppState
  (initial-state [this params] {:type :a :n :a :child (fc/get-initial-state MergeAChild nil)})
  static prim/IQuery
  (query [this] [:type :n {:child (prim/get-query MergeAChild)}]))

(defui ^:once MergeB
  static fc/InitialAppState
  (initial-state [this params] {:type :b :n :b})
  static prim/IQuery
  (query [this] [:n]))

(defui ^:once MergeUnion
  static fc/InitialAppState
  (initial-state [this params] (fc/get-initial-state MergeA {}))
  static prim/Ident
  (ident [this props] [:mergea-or-b :at-union])
  static prim/IQuery
  (query [this] {:a (prim/get-query MergeA) :b (prim/get-query MergeB)}))

(defui ^:once MergeRoot
  static fc/InitialAppState
  (initial-state [this params] {:a 1 :b (fc/get-initial-state MergeUnion {})})
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
  static fc/InitialAppState
  (initial-state [this params] (fc/get-initial-state MergeX {}))
  static prim/IQuery
  (query [this] {:x (prim/get-query MergeX) :y (prim/get-query MergeY)}))

(defui ^:once R2
  static fc/InitialAppState
  (initial-state [this params] {:id 1 :u2 (fc/get-initial-state U2 {})})
  static prim/IQuery
  (query [this] [:id {:u2 (prim/get-query U2)}]))

(defui ^:once U1
  static fc/InitialAppState
  (initial-state [this params] (fc/get-initial-state MergeB {}))
  static prim/IQuery
  (query [this] {:r2 (prim/get-query R2) :b (prim/get-query MergeB)}))

(defui ^:once NestedRoot
  static fc/InitialAppState
  (initial-state [this params] {:u1 (fc/get-initial-state U1 {})})
  static prim/IQuery
  (query [this] [{:u1 (prim/get-query U1)}]))

;; Sibling routing tree
;; SiblingRoot
;;     |   \
;;   SU1   SU2
;;  A   B  X  Y

(defui ^:once SU1
  static fc/InitialAppState
  (initial-state [this params] (fc/get-initial-state MergeB {}))
  static prim/Ident
  (ident [this props] [(:type props) 1])
  static prim/IQuery
  (query [this] {:a (prim/get-query MergeA) :b (prim/get-query MergeB)}))

(defui ^:once SU2
  static fc/InitialAppState
  (initial-state [this params] (fc/get-initial-state MergeX {}))
  static prim/Ident
  (ident [this props] [(:type props) 2])
  static prim/IQuery
  (query [this] {:x (prim/get-query MergeX) :y (prim/get-query MergeY)}))


(defui ^:once SiblingRoot
  static fc/InitialAppState
  (initial-state [this params] {:su1 (fc/get-initial-state SU1 {}) :su2 (fc/get-initial-state SU2 {})})
  static prim/IQuery
  (query [this] [{:su1 (prim/get-query SU1)} {:su2 (prim/get-query SU2)}]))

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
  static fc/InitialAppState
  (initial-state [c p] table-1)
  static prim/IQuery
  (query [this] [:type :id :rows]))

(def graph-1 {:type :graph :id 1 :data [1 2 3]})
(defui Graph
  static fc/InitialAppState
  (initial-state [c p] graph-1)
  static prim/IQuery
  (query [this] [:type :id :data]))

(defui Reports
  static fc/InitialAppState
  (initial-state [c p] (fc/get-initial-state Graph nil))    ; initial state will already include Graph
  static prim/Ident
  (ident [this props] [(:type props) (:id props)])
  static prim/IQuery
  (query [this] {:graph (prim/get-query Graph) :table (prim/get-query Table)}))

(defui MRRoot
  static fc/InitialAppState
  (initial-state [c p] {:reports (fc/get-initial-state Reports nil)})
  static prim/IQuery
  (query [this] [{:reports (prim/get-query Reports)}]))

(specification "merge-alternate-union-elements"
  (let [initial-state (merge (fc/get-initial-state MRRoot nil) {:a 1})
        state-map     (prim/tree->db MRRoot initial-state true)
        new-state     (fc/merge-alternate-union-elements state-map MRRoot)]
    (assertions
      "can be used to merge alternate union elements to raw state"
      (get-in new-state [:table 1]) => table-1
      "(existing state isn't touched)"
      (get new-state :a) => 1
      (get new-state :reports) => [:graph 1]
      (get-in new-state [:graph 1]) => graph-1)))

#?(:clj
   (specification "defsc helpers"
     (component "build-query-forms"
       (assertions
         "Support a method form"
         (#'fc/build-query-forms 'that 'props {:method '(fn [this] [:db/id])})
         => `(~'static fulcro.client.primitives/IQuery (~'query [~'this] [:db/id]))
         (#'fc/build-query-forms 'that 'props {:method '(query [this] [:db/id])})
         => `(~'static fulcro.client.primitives/IQuery (~'query [~'this] [:db/id]))
         "Honors the symbol for this that is defined by defsc"
         (#'fc/build-query-forms 'that 'props {:template '[:db/id]})
         => `(~'static fulcro.client.primitives/IQuery (~'query [~'that] [:db/id]))
         "Composes properties and joins into a proper query expression as a list of defui forms"
         (#'fc/build-query-forms 'this 'props {:template '[:db/id :person/name {:person/job (prim/get-query Job)} {:person/settings (prim/get-query Settings)}]})
         => `(~'static fulcro.client.primitives/IQuery (~'query [~'this] [:db/id :person/name {:person/job (~'prim/get-query ~'Job)} {:person/settings (~'prim/get-query ~'Settings)}]))
         "Verifies the propargs matches queries data when not a symbol"
         (#'fc/build-query-forms 'this '{:keys [db/id person/nme person/job]} {:template '[:db/id :person/name {:person/job (prim/get-query Job)}]})
         =throws=> (ExceptionInfo #"One or more destructured parameters" (fn [e]
                                                                           (-> (ex-data e) :offending-symbols (= ['person/nme]))))))
     (component "build-initial-state"
       (assertions
         "Generates nothing when there is entry"
         (#'fc/build-initial-state 'S nil #{} {:template []} false) => nil
         "Can build initial state from a method"
         (#'fc/build-initial-state 'S {:method '(fn [t p] {:x 1})} #{} {:template []} false) =>
         '(static fulcro.client.core/InitialAppState
            (initial-state [t p] {:x 1}))
         "Can build initial state from a template"
         (#'fc/build-initial-state 'S {:template {}} #{} {:template []} false) =>
         '(static fulcro.client.core/InitialAppState
            (initial-state [c params]
              (fulcro.client.core/make-state-map {} {} params)))
         "If the query is a method, so must the initial state"
         (#'fc/build-initial-state 'S {:template {:x 1}} #{} {:method '(fn [t] [])} false)
         =throws=> (ExceptionInfo #"When query is a method, initial state MUST")
         "Allows any state in initial-state method form, independent of the query form"
         (#'fc/build-initial-state 'S {:method '(fn [t p] {:x 1 :y 2})} #{} {:tempate []} false)
         => '(static fulcro.client.core/InitialAppState (initial-state [t p] {:x 1 :y 2}))
         (#'fc/build-initial-state 'S {:method '(initial-state [t p] {:x 1 :y 2})} #{} {:method '(query [t] [])} false) =>
         '(static fulcro.client.core/InitialAppState (initial-state [t p] {:x 1 :y 2}))
         "In template mode: Disallows initial state to contain items that are not in the query"
         (#'fc/build-initial-state 'S {:template {:x 1}} #{} {:template [:x]} false)
         =throws=> (ExceptionInfo #"Initial state includes keys that are not" (fn [e] (-> (ex-data e) :offending-keys (= #{:x}))))
         "Generates proper state parameters to make-state-map when data is available"
         (#'fc/build-initial-state 'S {:template {:x 1}} #{:x} {:template [:x]} false)
         => '(static fulcro.client.core/InitialAppState
               (initial-state [c params]
                 (fulcro.client.core/make-state-map {:x 1} {} params)))
         "Adds build-form around the initial state if it is a template and there are form fields"
         (#'fc/build-initial-state 'S {:template {}} #{} {:template []} true)
         => '(static fulcro.client.core/InitialAppState
               (initial-state [c params]
                 (fulcro.ui.forms/build-form S (fulcro.client.core/make-state-map {} {} params))))))
     (component "build-ident"
       (assertions
         "Generates nothing when there is no table"
         (#'fc/build-ident nil #{}) => nil
         (#'fc/build-ident nil #{:boo}) => nil
         "Requires the ID to be in the declared props"
         (#'fc/build-ident {:template [:TABLE/by-id :id]} #{}) =throws=> (ExceptionInfo #"ID property of :ident")
         "Can use a ident method to build the defui forms"
         (#'fc/build-ident {:method '(ident [this props] [:x :id])} #{})
         => '(static fulcro.client.primitives/Ident (ident [this props] [:x :id]))
         "Can use a vector template to generate defui forms"
         (#'fc/build-ident {:template [:TABLE/by-id :id]} #{:id})
         => `(~'static fulcro.client.primitives/Ident (~'ident [~'this ~'props] [:TABLE/by-id (:id ~'props)]))))
     (component "rename-and-validate-fn"
       (assertions
         "Replaces the first symbol in a method/lambda form"
         (#'fc/replace-and-validate-fn 'nm 1 '(fn [this] ...)) => '(nm [this] ...)
         "Throws an exception if the arity is wrong"
         (#'fc/replace-and-validate-fn 'nm 2 '(fn [this] ...))
         =throws=> (ExceptionInfo #"Invalid arity for nm")))
     (component "build-css"
       (assertions
         "Can take templates and turn them into the proper protocol"
         (#'fc/build-css {:template []} {:template []})
         => '(static fulcro-css.css/CSS
               (local-rules [_] [])
               (include-children [_] []))
         "Can take methods and turn them into the proper protocol"
         (#'fc/build-css {:method '(fn [t] [:rule])} {:method '(fn [this] [CrapTastic])})
         => '(static fulcro-css.css/CSS
               (local-rules [t] [:rule])
               (include-children [this] [CrapTastic]))
         "Omits the entire protocol if neiter are supplied"
         (#'fc/build-css nil nil) => nil))
     (component "build-render"
       (assertions
         "emits a list of forms for the render itself"
         (#'fc/build-render 'this {:keys ['a]} {:keys ['onSelect]} 'c '((dom/div nil "Hello")))
         => `(~'Object
               (~'render [~'this]
                 (let [{:keys [~'a]} (fulcro.client.primitives/props ~'this)
                       {:keys [~'onSelect]} (fulcro.client.primitives/get-computed ~'this)
                       ~'c (fulcro.client.primitives/children ~'this)]
                   (~'dom/div nil "Hello"))))))
     (component "make-state-map"
       (assertions
         "Can initialize plain state from scalar values"
         (fc/make-state-map {:db/id 1 :person/name "Tony"} {} nil) => {:db/id 1 :person/name "Tony"}
         "Can initialize plain scalar values using parameters"
         (fc/make-state-map {:db/id :param/id} {} {:id 1}) => {:db/id 1}
         "Will elide properties from missing parameters"
         (fc/make-state-map {:db/id :param/id :person/name "Tony"} {} nil) => {:person/name "Tony"}
         "Can substitute parameters into nested maps (non-children)"
         (fc/make-state-map {:scalar {:x :param/v}} {} {:v 1}) => {:scalar {:x 1}}
         "Can substitute parameters into nested vectors (non-children)"
         (fc/make-state-map {:scalar [:param/v]} {} {:v 1}) => {:scalar [1]}
         "Will include properties from explicit nil parameters"
         (fc/make-state-map {:db/id :param/id :person/name "Tony"} {} {:id nil}) => {:db/id nil :person/name "Tony"})
       (when-mocking
         (fc/get-initial-state c p) =1x=> (do
                                            (assertions
                                              "Obtains the child's initial state with the correct class and params"
                                              c => :JOB
                                              p => {:id 99})
                                            :job-99)
         (fc/get-initial-state c p) =1x=> (do
                                            (assertions
                                              "Obtains the child's initial state with the correct class and params"
                                              c => :JOB
                                              p => :JOB-PARAMS)
                                            :initialized-job)
         (fc/get-initial-state c p) =1x=> (do
                                            (assertions
                                              "Obtains the child's initial state with the correct class and params"
                                              c => :JOB
                                              p => {:id 4})
                                            :initialized-job)

         (assertions
           "Supports to-one initialization"
           (fc/make-state-map {:db/id 1 :person/job {:id 99}} {:person/job :JOB} nil) => {:db/id 1 :person/job :job-99}
           "Supports to-one initialization from a parameter"
           (fc/make-state-map {:db/id 1 :person/job :param/job} {:person/job :JOB} {:job :JOB-PARAMS}) => {:db/id 1 :person/job :initialized-job}
           "supports to-one initialization from a map with nested parameters"
           (fc/make-state-map {:db/id 1 :person/job {:id :param/job-id}} {:person/job :JOB} {:job-id 4})
           => {:db/id 1 :person/job :initialized-job}))
       (when-mocking
         (fc/get-initial-state c p) =1x=> (do
                                            (assertions
                                              "Uses parameters for the first element"
                                              c => :JOB
                                              p => {:id 1})
                                            :job1)
         (fc/get-initial-state c p) =1x=> (do
                                            (assertions
                                              "Uses parameters for the second element"
                                              c => :JOB
                                              p => {:id 2})
                                            :job2)

         (assertions
           "supports non-parameterized to-many initialization"
           (fc/make-state-map {:person/jobs [{:id 1} {:id 2}]}
             {:person/jobs :JOB} nil) => {:person/jobs [:job1 :job2]}))
       (when-mocking
         (fc/get-initial-state c p) =1x=> (do
                                            (assertions
                                              "Uses parameters for the first element"
                                              c => :JOB
                                              p => {:id 2})
                                            :A)
         (fc/get-initial-state c p) =1x=> (do
                                            (assertions
                                              "Uses parameters for the second element"
                                              c => :JOB
                                              p => {:id 3})
                                            :B)

         (assertions
           "supports to-many initialization with nested parameters"
           (fc/make-state-map {:db/id :param/id :person/jobs [{:id :param/id1} {:id :param/id2}]}
             {:person/jobs :JOB} {:id 1 :id1 2 :id2 3}) => {:db/id 1 :person/jobs [:A :B]}))
       (when-mocking
         (fc/get-initial-state c p) =1x=> (do
                                            (assertions
                                              "Uses parameters for the first element"
                                              c => :JOB
                                              p => {:id 1})
                                            :A)
         (fc/get-initial-state c p) =1x=> (do
                                            (assertions
                                              "Uses parameters for the second element"
                                              c => :JOB
                                              p => {:id 2})
                                            :B)
         (assertions
           "supports to-many initialization with nested parameters"
           (fc/make-state-map {:person/jobs :param/jobs}
             {:person/jobs :JOB} {:jobs [{:id 1} {:id 2}]}) => {:person/jobs [:A :B]})))))

#?(:clj
   (specification "defsc"
     (component "css"
       (let [expected-defui '(fulcro.client.primitives/defui Person
                               static
                               fulcro-css.css/CSS
                               (local-rules [_] [:rule])
                               (include-children [_] [A])
                               static
                               fulcro.client.primitives/IQuery
                               (query [this] [:db/id])
                               Object
                               (render [this]
                                 (clojure.core/let [{:keys [db/id]} (fulcro.client.primitives/props this)
                                                    _ (fulcro.client.primitives/get-computed this)
                                                    _ (fulcro.client.primitives/children this)]
                                   (dom/div nil "Boo"))))]
         (assertions
           "allows optional use of include"
           (fc/defsc* '(Person
                         [this {:keys [db/id]} _ _]
                         {:query [:db/id]
                          :css   [:rule]}
                         (dom/div nil "Boo")))
           => '(fulcro.client.primitives/defui Person
                 static
                 fulcro-css.css/CSS
                 (local-rules [_] [:rule])
                 (include-children [_] [])
                 static
                 fulcro.client.primitives/IQuery
                 (query [this] [:db/id])
                 Object
                 (render [this]
                   (clojure.core/let [{:keys [db/id]} (fulcro.client.primitives/props this)
                                      _ (fulcro.client.primitives/get-computed this)
                                      _ (fulcro.client.primitives/children this)]
                     (dom/div nil "Boo"))))
           "allows optional use of css"
           (fc/defsc* '(Person
                         [this {:keys [db/id]} _ _]
                         {:query       [:db/id]
                          :css-include [A]}
                         (dom/div nil "Boo")))
           => '(fulcro.client.primitives/defui Person
                 static
                 fulcro-css.css/CSS
                 (local-rules [_] [])
                 (include-children [_] [A])
                 static
                 fulcro.client.primitives/IQuery
                 (query [this] [:db/id])
                 Object
                 (render [this]
                   (clojure.core/let [{:keys [db/id]} (fulcro.client.primitives/props this)
                                      _ (fulcro.client.primitives/get-computed this)
                                      _ (fulcro.client.primitives/children this)]
                     (dom/div nil "Boo"))))
           "checks method arities"
           (fc/defsc* '(Person
                         [this {:keys [db/id]} _ _]
                         {:query [:db/id]
                          :css   (fn [a b] [])}
                         (dom/div nil "Boo")))
           =throws=> (ExceptionInfo #"Invalid arity for css")
           (fc/defsc* '(Person
                         [this {:keys [db/id]} _ _]
                         {:query       [:db/id]
                          :css-include (fn [a b] [])}
                         (dom/div nil "Boo")))
           =throws=> (ExceptionInfo #"Invalid arity for css-include")
           "allows method bodies"
           (fc/defsc* '(Person
                         [this {:keys [db/id]} _ _]
                         {:query       [:db/id]
                          :css         (fn [_] [:rule])
                          :css-include (fn [_] [A])}
                         (dom/div nil "Boo")))
           => expected-defui
           (fc/defsc* '(Person
                         [this {:keys [db/id]} _ _]
                         {:query       [:db/id]
                          :css         (some-random-name [_] [:rule]) ; doesn't really care what sym you use
                          :css-include (craptastic! [_] [A])}
                         (dom/div nil "Boo")))
           => expected-defui)))
     (assertions
       "works with initial state"
       (#'fc/defsc* '(Person
                       [this {:keys [person/job db/id] :as props} {:keys [onSelect] :as computed} children]
                       {:query         [:db/id {:person/job (prim/get-query Job)}]
                        :initial-state {:person/job {:x 1}
                                        :db/id      42}
                        :ident         [:PERSON/by-id :db/id]}
                       (dom/div nil "Boo")))
       => `(fulcro.client.primitives/defui ~'Person
             ~'static fulcro.client.core/InitialAppState
             (~'initial-state [~'c ~'params]
               (fulcro.client.core/make-state-map
                 {:person/job {:x 1}
                  :db/id      42}
                 {:person/job ~'Job}
                 ~'params))
             ~'static fulcro.client.primitives/Ident
             (~'ident [~'this ~'props] [:PERSON/by-id (:db/id ~'props)])
             ~'static fulcro.client.primitives/IQuery
             (~'query [~'this] [:db/id {:person/job (~'prim/get-query ~'Job)}])
             ~'Object
             (~'render [~'this]
               (let [{:keys [~'person/job ~'db/id] :as ~'props} (fulcro.client.primitives/props ~'this)
                     {:keys [~'onSelect] :as ~'computed} (fulcro.client.primitives/get-computed ~'this)
                     ~'children (fulcro.client.primitives/children ~'this)]
                 (~'dom/div nil "Boo"))))
       "allows an initial state method body"
       (fc/defsc* '(Person
                     [this {:keys [person/job db/id] :as props} {:keys [onSelect] :as computed} children]
                     {:query         [:db/id {:person/job (prim/get-query Job)}]
                      :initial-state (initial-state [this params] {:x 1})
                      :ident         [:PERSON/by-id :db/id]}
                     (dom/div nil "Boo")))
       => `(fulcro.client.primitives/defui ~'Person
             ~'static fulcro.client.core/InitialAppState
             (~'initial-state [~'this ~'params] {:x 1})
             ~'static fulcro.client.primitives/Ident
             (~'ident [~'this ~'props] [:PERSON/by-id (:db/id ~'props)])
             ~'static fulcro.client.primitives/IQuery
             (~'query [~'this] [:db/id {:person/job (~'prim/get-query ~'Job)}])
             ~'Object
             (~'render [~'this]
               (let [{:keys [~'person/job ~'db/id] :as ~'props} (fulcro.client.primitives/props ~'this)
                     {:keys [~'onSelect] :as ~'computed} (fulcro.client.primitives/get-computed ~'this)
                     ~'children (fulcro.client.primitives/children ~'this)]
                 (~'dom/div nil "Boo"))))
       "works without initial state"
       (fc/defsc* '(Person
                     [this {:keys [person/job db/id] :as props} {:keys [onSelect] :as computed} children]
                     {:query [:db/id {:person/job (prim/get-query Job)}]
                      :ident [:PERSON/by-id :db/id]}
                     (dom/div nil "Boo")))
       => `(fulcro.client.primitives/defui ~'Person
             ~'static fulcro.client.primitives/Ident
             (~'ident [~'this ~'props] [:PERSON/by-id (:db/id ~'props)])
             ~'static fulcro.client.primitives/IQuery
             (~'query [~'this] [:db/id {:person/job (~'prim/get-query ~'Job)}])
             ~'Object
             (~'render [~'this]
               (let [{:keys [~'person/job ~'db/id] :as ~'props} (fulcro.client.primitives/props ~'this)
                     {:keys [~'onSelect] :as ~'computed} (fulcro.client.primitives/get-computed ~'this)
                     ~'children (fulcro.client.primitives/children ~'this)]
                 (~'dom/div nil "Boo"))))
       "allows Object protocol"
       (fc/defsc* '(Person
                     [this props computed children]
                     {:query     [:db/id]
                      :protocols (Object (shouldComponentUpdate [this p s] false))}
                     (dom/div nil "Boo")))
       => `(fulcro.client.primitives/defui ~'Person
             ~'static fulcro.client.primitives/IQuery
             (~'query [~'this] [:db/id])
             ~'Object
             (~'render [~'this]
               (let [~'props (fulcro.client.primitives/props ~'this)
                     ~'computed (fulcro.client.primitives/get-computed ~'this)
                     ~'children (fulcro.client.primitives/children ~'this)]
                 (~'dom/div nil "Boo")))
             (~'shouldComponentUpdate [~'this ~'p ~'s] false))
       "allows other protocols"
       (fc/defsc* '(Person
                     [this props computed children]
                     {:query     [:db/id]
                      :protocols (static css/CSS
                                   (local-rules [_] [])
                                   (include-children [_] [])
                                   Object
                                   (shouldComponentUpdate [this p s] false))}
                     (dom/div nil "Boo")))
       => `(fulcro.client.primitives/defui ~'Person
             ~'static ~'css/CSS
             (~'local-rules [~'_] [])
             (~'include-children [~'_] [])
             ~'static fulcro.client.primitives/IQuery
             (~'query [~'this] [:db/id])
             ~'Object
             (~'render [~'this]
               (let [~'props (fulcro.client.primitives/props ~'this)
                     ~'computed (fulcro.client.primitives/get-computed ~'this)
                     ~'children (fulcro.client.primitives/children ~'this)]
                 (~'dom/div nil "Boo")))
             (~'shouldComponentUpdate [~'this ~'p ~'s] false))
       "works without an ident"
       (fc/defsc* '(Person
                     [this {:keys [person/job db/id] :as props} {:keys [onSelect] :as computed} children]
                     {:query [:db/id {:person/job (prim/get-query Job)}]}
                     (dom/div nil "Boo")))
       => `(fulcro.client.primitives/defui ~'Person
             ~'static fulcro.client.primitives/IQuery
             (~'query [~'this] [:db/id {:person/job (~'prim/get-query ~'Job)}])
             ~'Object
             (~'render [~'this]
               (let [{:keys [~'person/job ~'db/id] :as ~'props} (fulcro.client.primitives/props ~'this)
                     {:keys [~'onSelect] :as ~'computed} (fulcro.client.primitives/get-computed ~'this)
                     ~'children (fulcro.client.primitives/children ~'this)]
                 (~'dom/div nil "Boo")))))))

