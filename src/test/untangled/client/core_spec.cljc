(ns untangled.client.core-spec
  (:require
    [om.next :as om :refer [defui]]
    [untangled.client.core :as uc]
    [untangled-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [om.next.protocols :as omp]
    [clojure.core.async :as async]
    [untangled.client.logging :as log]
    [untangled.client.impl.om-plumbing :as plumbing]
    [untangled.client.util :as util]))

(defui Child
  static om/Ident
  (ident [this props] [:child/by-id (:id props)])
  static om/IQuery
  (query [this] [:id :label]))

(defui Parent
  static uc/InitialAppState
  (uc/initial-state [this params] {:ui/checked true})
  static om/Ident
  (ident [this props] [:parent/by-id (:id props)])
  static om/IQuery
  (query [this] [:ui/checked :id :title {:child (om/get-query Child)}]))

#?(:cljs
   (specification "merge-state!"
     (assertions
       "merge-query is the component query joined on it's ident"
       (#'uc/component-merge-query Parent {:id 42}) => [{[:parent/by-id 42] [:ui/checked :id :title {:child (om/get-query Child)}]}])
     (component "preprocessing the object to merge"
       (let [no-state             (atom {:parent/by-id {}})
             no-state-merge-data  (:merge-data (#'uc/preprocess-merge no-state Parent {:id 42}))
             state-with-old       (atom {:parent/by-id {42 {:ui/checked true :id 42 :title "Hello"}}})
             id                   [:parent/by-id 42]
             old-state-merge-data (-> (#'uc/preprocess-merge state-with-old Parent {:id 42}) :merge-data :untangled/merge)]
         (assertions
           "Uses the existing object in app state as base for merge when present"
           (get-in old-state-merge-data [id :ui/checked]) => true
           "Marks fields that were queried but are not present as plumbing/not-found"
           old-state-merge-data => {[:parent/by-id 42] {:id         42
                                                        :ui/checked true
                                                        :title      :untangled.client.impl.om-plumbing/not-found
                                                        :child      :untangled.client.impl.om-plumbing/not-found}}))
       (let [union-query {:union-a [:b] :union-b [:c]}
             state       (atom {})]
         (when-mocking
           (util/get-ident c d) => :ident
           (om/get-query comp) => union-query
           (uc/component-merge-query comp data) => :merge-query
           (om/db->tree q d r) => {:ident :data}
           (plumbing/mark-missing d q) => (do
                                            (assertions
                                              "wraps union queries in a vector"
                                              q => [union-query])

                                            {:ident :data})
           (util/deep-merge d1 d2) => :merge-result

           (#'uc/preprocess-merge state :comp :data))))
     (let [state (atom {})
           data  {}]
       (when-mocking
         (uc/preprocess-merge s c d) => {:merge-data :the-data :merge-query :the-query}
         (uc/integrate-ident! s i op args op args) => :ignore
         (util/get-ident c p) => [:table :id]
         (om/merge! r d q) => :ignore
         (om/app-state r) => state
         (omp/queue! r kw) => (assertions
                                "schedules re-rendering of all affected paths"
                                kw => [:children :items])

         (uc/merge-state! :reconciler :component data :append [:children] :replace [:items 0])))))

(specification "integrate-ident!"
  (let [state (atom {:a    {:path [[:table 2]]}
                     :b    {:path [[:table 2]]}
                     :d    [:table 6]
                     :many {:path [[:table 99] [:table 88] [:table 77]]}})]
    (behavior "Can append to an existing vector"
      (uc/integrate-ident! state [:table 3] :append [:a :path])
      (assertions
        (get-in @state [:a :path]) => [[:table 2] [:table 3]])
      (uc/integrate-ident! state [:table 3] :append [:a :path])
      (assertions
        "(is a no-op if the ident is already there)"
        (get-in @state [:a :path]) => [[:table 2] [:table 3]]))
    (behavior "Can prepend to an existing vector"
      (uc/integrate-ident! state [:table 3] :prepend [:b :path])
      (assertions
        (get-in @state [:b :path]) => [[:table 3] [:table 2]])
      (uc/integrate-ident! state [:table 3] :prepend [:b :path])
      (assertions
        "(is a no-op if already there)"
        (get-in @state [:b :path]) => [[:table 3] [:table 2]]))
    (behavior "Can create/replace a to-one ident"
      (uc/integrate-ident! state [:table 3] :replace [:c :path])
      (uc/integrate-ident! state [:table 3] :replace [:d])
      (assertions
        (get-in @state [:d]) => [:table 3]
        (get-in @state [:c :path]) => [:table 3]
        ))
    (behavior "Can replace an existing to-many element in a vector"
      (uc/integrate-ident! state [:table 3] :replace [:many :path 1])
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
        (uc/integrate-ident [:table 3] :append [:a :path])
        (get-in [:a :path]))
      => [[:table 2] [:table 3]]

      "(is a no-op if the ident is already there)"
      (-> state
        (uc/integrate-ident [:table 3] :append [:a :path])
        (get-in [:a :path]))
      => [[:table 2] [:table 3]]

      "Can prepend to an existing vector"
      (-> state
        (uc/integrate-ident [:table 3] :prepend [:b :path])
        (get-in [:b :path]))
      => [[:table 3] [:table 2]]

      "(is a no-op if already there)"
      (-> state
        (uc/integrate-ident [:table 3] :prepend [:b :path])
        (get-in [:b :path]))
      => [[:table 3] [:table 2]]

      "Can create/replace a to-one ident"
      (-> state
        (uc/integrate-ident [:table 3] :replace [:d])
        (get-in [:d]))
      => [:table 3]
      (-> state
        (uc/integrate-ident [:table 3] :replace [:c :path])
        (get-in [:c :path]))
      => [:table 3]

      "Can replace an existing to-many element in a vector"
      (-> state
        (uc/integrate-ident [:table 3] :replace [:many :path 1])
        (get-in [:many :path]))
      => [[:table 99] [:table 3] [:table 77]])))

#?(:cljs
   (specification "Untangled Application -- clear-pending-remote-requests!"
     (let [channel  (async/chan 1000)
           mock-app (uc/map->Application {:send-queues {:remote channel}})]
       (async/put! channel 1 #(async/put! channel 2 (fn [] (async/put! channel 3 (fn [] (async/put! channel 4))))))

       (uc/clear-pending-remote-requests! mock-app nil)

       (assertions
         "Removes any pending items in the network queue channel"
         (async/poll! channel) => nil))))

(defui BadResetAppRoot
  Object
  (render [this] nil))

(defui ResetAppRoot
  static uc/InitialAppState
  (initial-state [this params] {:x 1}))

#?(:cljs
   (specification "Untangled Application -- reset-app!"
     (let [scb-calls        (atom 0)
           custom-calls     (atom 0)
           mock-app         (uc/map->Application {:send-queues      {:remote :fake-queue}
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
           (uc/reset-app! mock-app BadResetAppRoot nil)))

       (behavior "On a proper app root"
         (when-mocking
           (uc/clear-queue t) => (reset! cleared-network? true)
           (om/app-state r) => state
           (uc/merge-alternate-union-elements! app r) => (reset! merged-unions? true)
           (uc/reset-history-impl a) => (reset! history-reset? true)
           (util/force-render a) => (reset! re-rendered? true)

           (uc/reset-app! mock-app ResetAppRoot nil)
           (uc/reset-app! mock-app ResetAppRoot :original)
           (uc/reset-app! mock-app ResetAppRoot (fn [a] (swap! custom-calls inc))))

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
   (specification "Mounting an Untangled Application"
     (let [mounted-mock-app {:mounted? true :initial-state {}}]
       (provided "When it is already mounted"
         (uc/refresh* a) =1x=> (do
                                 (assertions
                                   "Refreshes the UI"
                                   1 => 1)
                                 a)

         (uc/mount* mounted-mock-app :fake-root :dom-id)))
     (behavior "When is is not already mounted"
       (behavior "and root does NOT implement InitialAppState"
         (let [mock-app {:mounted? false :initial-state {:a 1} :reconciler-options :OPTIONS}]
           (when-mocking
             (uc/initialize app state root dom opts) => (do
                                                          (assertions
                                                            "Initializes the app with a plain map"
                                                            state => {:a 1}
                                                            ))

             (uc/mount* mock-app :fake-root :dom-id)))
         (let [supplied-atom (atom {:a 1})
               mock-app      {:mounted? false :initial-state supplied-atom :reconciler-options :OPTIONS}]
           (when-mocking
             (uc/initialize app state root dom opts) => (do
                                                          (assertions
                                                            "Initializes the app with a supplied atom"
                                                            {:a 1} => @state))

             (uc/mount* mock-app :fake-root :dom-id))))
       (behavior "and root IMPLEMENTS InitialAppState"
         (let [mock-app {:mounted? false :initial-state {:a 1} :reconciler-options :OPTIONS}]
           (when-mocking
             (log/warn msg) =1x=> (do (assertions "warns about duplicate initialization"
                                        msg =fn=> (partial re-matches #"^You supplied.*")))
             (uc/initialize app state root dom opts) => (do
                                                          (assertions
                                                            "Initializes the app with the InitialAppState"
                                                            state => (uc/get-initial-state Parent nil)))

             (uc/mount* mock-app Parent :dom-id)))
         (let [mock-app {:mounted? false :initial-state (atom {:a 1}) :reconciler-options :OPTIONS}]
           (behavior "When both atom and InitialAppState are present:"
             (when-mocking
               (log/warn msg) =1x=> true
               (om/tree->db c d merge-idents) => (do
                                                   (behavior "Normalizes InitialAppState:"
                                                     (assertions
                                                       "includes Om tables"
                                                       merge-idents => true
                                                       "uses the Root UI component query"
                                                       c => Parent
                                                       "uses InitialAppState as the data"
                                                       d => (uc/get-initial-state Parent nil)))
                                                   :NORMALIZED-STATE)
               (uc/initialize app state root dom opts) => (do
                                                            (assertions
                                                              "Overwrites the supplied atom with the normalized InitialAppState"
                                                              @state => :NORMALIZED-STATE))

               (uc/mount* mock-app Parent :dom-id))))
         (let [mock-app {:mounted? false :reconciler-options :OPTIONS}]
           (behavior "When only InitialAppState is present:"
             (when-mocking
               (untangled.client.core/initial-state root-component nil) => :INITIAL-UI-STATE
               (uc/initialize app state root dom opts) => (do
                                                            (assertions
                                                              "Supplies the raw InitialAppState to internal initialize"
                                                              state => :INITIAL-UI-STATE))

               (uc/mount* mock-app Parent :dom-id))))))))


(defui MergeX
  static uc/InitialAppState
  (initial-state [this params] {:n :x})
  static om/IQuery
  (query [this] [:n]))

(defui MergeY
  static uc/InitialAppState
  (initial-state [this params] {:n :y})
  static om/IQuery
  (query [this] [:n]))


(defui MergeA
  static uc/InitialAppState
  (initial-state [this params] {:n :a})
  static om/IQuery
  (query [this] [:n]))

(defui MergeB
  static uc/InitialAppState
  (initial-state [this params] {:n :b})
  static om/IQuery
  (query [this] [:n]))

(defui MergeUnion
  static uc/InitialAppState
  (initial-state [this params] (uc/get-initial-state MergeA {}))
  static om/IQuery
  (query [this] {:a (om/get-query MergeA) :b (om/get-query MergeB)}))

(defui MergeRoot
  static uc/InitialAppState
  (initial-state [this params] {:a 1 :b (uc/get-initial-state MergeUnion {})})
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

(defui U2
  static uc/InitialAppState
  (initial-state [this params] (uc/get-initial-state MergeX {}))
  static om/IQuery
  (query [this] {:x (om/get-query MergeX) :y (om/get-query MergeY)}))

(defui R2
  static uc/InitialAppState
  (initial-state [this params] {:id 1 :u2 (uc/get-initial-state U2 {})})
  static om/IQuery
  (query [this] [:id {:u2 (om/get-query U2)}]))

(defui U1
  static uc/InitialAppState
  (initial-state [this params] (uc/get-initial-state MergeB {}))
  static om/IQuery
  (query [this] {:r2 (om/get-query R2) :b (om/get-query MergeB)}))

(defui NestedRoot
  static uc/InitialAppState
  (initial-state [this params] {:u1 (uc/get-initial-state U1 {})})
  static om/IQuery
  (query [this] [{:u1 (om/get-query U1)}]))

;; Sibling routing tree
;; SiblingRoot
;;     |   \
;;   SU1   SU2
;;  A   B  X  Y

(defui SU1
  static uc/InitialAppState
  (initial-state [this params] (uc/get-initial-state MergeB {}))
  static om/IQuery
  (query [this] {:a (om/get-query MergeA) :b (om/get-query MergeB)}))

(defui SU2
  static uc/InitialAppState
  (initial-state [this params] (uc/get-initial-state MergeX {}))
  static om/IQuery
  (query [this] {:x (om/get-query MergeX) :y (om/get-query MergeY)}))


(defui SiblingRoot
  static uc/InitialAppState
  (initial-state [this params] {:su1 (uc/get-initial-state SU1 {}) :su2 (uc/get-initial-state SU2 {})})
  static om/IQuery
  (query [this] [{:su1 (om/get-query SU1)} {:su2 (om/get-query SU2)}]))


(specification "merge-alternate-union-elements!"
  (behavior "For applications with sibling unions"
    (when-mocking
      (uc/merge-state! app comp state) =1x=> (do
                                               (assertions
                                                 "Merges level one elements"
                                                 state => (uc/get-initial-state MergeA {})))
      (uc/merge-state! app comp state) =1x=> (do
                                               (assertions
                                                 "Merges only the state of branches that are not already initialized"
                                                 state => (uc/get-initial-state MergeY {})))

      (uc/merge-alternate-union-elements! :app SiblingRoot)))

  (behavior "For applications with nested unions"
    (when-mocking
      (uc/merge-state! app comp state) =1x=> (do
                                               (assertions
                                                 "Merges level one elements"
                                                 state => (uc/get-initial-state R2 {})))
      (uc/merge-state! app comp state) =1x=> (do
                                               (assertions
                                                 "Merges only the state of branches that are not already initialized"
                                                 state => (uc/get-initial-state MergeY {})))

      (uc/merge-alternate-union-elements! :app NestedRoot)))
  (behavior "For applications with non-nested unions"
    (when-mocking
      (uc/merge-state! app comp state) => (do
                                            (assertions
                                              "Merges only the state of branches that are not already initialized"
                                              state => (uc/get-initial-state MergeB {})))

      (uc/merge-alternate-union-elements! :app MergeRoot))))

