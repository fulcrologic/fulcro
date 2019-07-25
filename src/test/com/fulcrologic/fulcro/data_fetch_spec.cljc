(ns com.fulcrologic.fulcro.data-fetch-spec
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [clojure.test :refer [is are]]
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]))


(defsc Person [this props]
  {:query [:db/id :username :name]
   :ident [:person/id :db/id]})

(defsc Comment [this props]
  {:query [:db/id :title {:author (comp/get-query Person)}]
   :ident [:comments/id :db/id]})

(defsc Item [this props]
  {:query [:db/id :name {:comments (comp/get-query Comment)}]
   :ident [:items/id :db/id]})

(defsc InitTestChild [this props]
  {:query         [:y]
   :ident         [:child/by-id :y]
   :initial-state {:y 2}})

(defsc InitTestComponent [this props]
  {:initial-state {:x 1 :z :param/z :child {}}
   :ident         [:parent/by-id :x]
   :query         [:x :z {:child (comp/get-query InitTestChild)}]})

(def app (app/fulcro-app))

(specification "Load parameters"
  (let [
        query-with-params       (:query (df/load-params* app :prop Person {:params {:n 1}}))
        ident-query-with-params (:query (df/load-params* app [:person/by-id 1] Person {:params {:n 1}}))]
    (assertions
      "Accepts nil for subquery and params"
      (:query (df/load-params* app [:person/by-id 1] nil {})) => [[:person/by-id 1]]
      "Constructs query with parameters when subquery is nil"
      (:query (df/load-params* app [:person/by-id 1] nil {:params {:x 1}})) => '[([:person/by-id 1] {:x 1})]
      "Constructs a JOIN query (without params)"
      (:query (df/load-params* app :prop Person {})) => [{:prop (comp/get-query Person)}]
      (:query (df/load-params* app [:person/by-id 1] Person {})) => [{[:person/by-id 1] (comp/get-query Person)}]
      "Honors target for property-based join"
      (:target (df/load-params* app :prop Person {:target [:a :b]})) => [:a :b]
      "Constructs a JOIN query (with params on join and prop)"
      query-with-params => `[({:prop ~(comp/get-query Person)} {:n 1})]
      ident-query-with-params => `[({[:person/by-id 1] ~(comp/get-query Person)} {:n 1})]))
  (behavior "can focus the query"
    (assertions
      (:query (df/load-params* app [:item/by-id 1] Item {:focus [:name {:comments [:title]}]}))
      => [{[:item/by-id 1] [:name {:comments [:title]}]}]))
  (behavior "can update the query with custom processing"
    (assertions
      (:query (df/load-params* app [:item/by-id 1] Item {:focus        [:name]
                                                         :update-query #(conj % :extra)}))
      => [{[:item/by-id 1] [:name :extra]}])))
