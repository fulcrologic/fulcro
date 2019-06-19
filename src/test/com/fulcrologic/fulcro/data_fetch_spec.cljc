(ns com.fulcrologic.fulcro.data-fetch-spec
  (:require
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [clojure.test :refer [is are]]
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; SETUP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defsc Person [_ _]
  {:query [:db/id :username :name]
   :ident [:person/id :db/id]})

(defsc Comment [_ _]
  {:query [:db/id :title {:author (comp/get-query Person)}]
   :ident [:comments/id :db/id]})

(defsc Item [_ _]
  {:query [:db/id :name {:comments (comp/get-query Comment)}]
   :ident [:items/id :db/id]})

(defsc Panel [_ _]
  {:query [:db/id {:items (comp/get-query Item)}]
   :ident [:panel/id :db/id]})

(defsc PanelRoot [_ _]
  {:query [{:panel (comp/get-query Panel)}]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;  TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defsc InitTestChild [this props]
  {:query         [:y]
   :ident         [:child/by-id :y]
   :initial-state {:y 2}})

(defsc InitTestComponent [this props]
  {:initial-state {:x 1 :z :param/z :child {}}
   :ident         [:parent/by-id :x]
   :query         [:x :z {:child (comp/get-query InitTestChild)}]})

(comment
  #?(:cljs
     (specification "Load parameters"
       (let [query-with-params       (:query (df/load-params* {} :prop Person {:params {:n 1}}))
             ident-query-with-params (:query (df/load-params* {} [:person/id 1] Person {:params {:n 1}}))]
         (assertions
           "Always include a vector for refresh"
           (df/load-params* {} :prop Person {}) =fn=> #(vector? (:refresh %))
           (df/load-params* {} [:person/id 1] Person {}) =fn=> #(vector? (:refresh %))
           "Accepts nil for subquery and params"
           (:query (df/load-params* {} [:person/id 1] nil {})) => [[:person/id 1]]
           "Constructs query with parameters when subquery is nil"
           (:query (df/load-params* {} [:person/id 1] nil {:params {:x 1}})) => '[([:person/id 1] {:x 1})]
           "Constructs a JOIN query (without params)"
           (:query (df/load-params* {} :prop Person {})) => [{:prop (comp/get-query Person)}]
           (:query (df/load-params* {} [:person/id 1] Person {})) => [{[:person/id 1] (comp/get-query Person)}]
           "Honors target for property-based join"
           (:target (df/load-params* {} :prop Person {:target [:a :b]})) => [:a :b]
           "Constructs a JOIN query (with params)"
           query-with-params =fn=> (fn [q] (= q `[({:prop ~(comp/get-query Person)} {:n 1})]))
           ident-query-with-params =fn=> (fn [q] (= q `[({[:person/id 1] ~(comp/get-query Person)} {:n 1})])))
         (provided "uses computed-refresh to augment the refresh list"
           (df/computed-refresh explicit k t) =1x=> :computed-refresh

           (let [params (df/load-params* {} :k Person {})]
             (assertions
               "includes the computed refresh list as refresh"
               (:refresh params) => :computed-refresh))))
       (let [state-marker-legacy (df/ready-state {:query [{:x [:a]}] :field :x :ident [:thing/by-id 1] :params {:x {:p 2}}})
             state-marker-new    (df/ready-state {:query [{:x [:a]}] :field :x :ident [:thing/by-id 1] :params {:p 2}})]
         (assertions
           "Honors legacy way of specifying parameters"
           (-> state-marker-legacy ::comp/query) => '[({:x [:a]} {:p 2})]
           "Honors simpler way of specifying parameters"
           (-> state-marker-new ::comp/query) => '[({:x [:a]} {:p 2})]))
       (behavior "When initialize is:"
         (let [params                  (df/load-params* {} :root/comp InitTestComponent {:initialize true})
               params-with-init-params (df/load-params* {} :root/comp InitTestComponent {:initialize {:z 42}})]
           (assertions
             "true: load params get initial state for component"
             (:initialize params) => {:root/comp {:x 1 :child {:y 2}}}
             "a map: load params get that map as the initial state of the component"
             (:initialize params-with-init-params) => {:root/comp {:z 42}})))
       (behavior "can focus the query"
         (assertions
           (:query (df/load-params* {} [:item/by-id 1] Item {:focus [:name {:comments [:title]}]}))
           => [{[:item/by-id 1] [:name {:comments [:title]}]}]))
       (behavior "can update the query with custom processing"
         (assertions
           (:query (df/load-params* {} [:item/by-id 1] Item {:focus        [:name]
                                                             :update-query #(conj % :extra)}))
           => [{[:item/by-id 1] [:name :extra]}])))))

