(ns untangled.client.core-spec
  (:require
    [om.next :as om :refer-macros [defui]]
    [untangled.client.core :as uc]
    [untangled-spec.core :refer-macros
     [specification behavior assertions provided component when-mocking]]
    [om.next.protocols :as omp]))

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

(specification "merge-state!"
  (assertions
    "merge-query is the component query joined on it's ident"
    (#'uc/component-merge-query Parent {:id 42}) => [{[:parent/by-id 42] [:ui/checked :id :title {:child (om/get-query Child)}]}])
  (component "preprocessing the object to merge"
    (let [no-state (atom {:parent/by-id {}})
          no-state-merge-data (:merge-data (#'uc/preprocess-merge no-state Parent {:id 42}))
          state-with-old (atom {:parent/by-id {42 {:ui/checked true :id 42 :title "Hello"}}})
          id [:parent/by-id 42]
          old-state-merge-data (-> (#'uc/preprocess-merge state-with-old Parent {:id 42}) :merge-data :untangled/merge)]
      (assertions
        "Uses the existing object in app state as base for merge when present"
        (get-in old-state-merge-data [id :ui/checked]) => true
        "Marks fields that were queried but are not present as plumbing/not-found"
        old-state-merge-data => {[:parent/by-id 42] {:id         42
                                                     :ui/checked true
                                                     :title      :untangled.client.impl.om-plumbing/not-found
                                                     :child      :untangled.client.impl.om-plumbing/not-found}})))
  (let [state (atom {})
        data {}]
    (when-mocking
      (uc/preprocess-merge s c d) => {:merge-data :the-data :merge-query :the-query}
      (uc/integrate-ident! s i op args op args) => :ignore
      (uc/get-class-ident c p) => [:table :id]
      (om/merge! r d q) => :ignore
      (om/app-state r) => state
      (omp/queue! r kw) => (assertions
                             "schedules re-rendering of all affected paths"
                             kw => [:children :items])

      (uc/merge-state! :reconciler :component data :append [:children] :replace [:items 0]))))

(specification "integrate-ident!"
  (let [state (atom {:a    {:path [[:table 2]]}
                     :b    {:path [[:table 2]]}
                     :d    [:table 6]
                     :many {:path [[:table 99] [:table 88] [:table 77]]}
                     })]
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

