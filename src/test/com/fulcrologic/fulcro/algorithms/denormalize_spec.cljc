(ns com.fulcrologic.fulcro.algorithms.denormalize-spec
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test :refer [is are deftest]]
    [clojure.test.check :as tc]
    [clojure.test.check.clojure-test :as test]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as props #?@(:cljs [:refer-macros [for-all]])]
    [clojure.walk :as walk]
    [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
    [com.fulcrologic.fulcro.algorithms.legacy-db-tree :as fp]
    [com.fulcrologic.fulcro.algorithms.normalize :refer [tree->db]]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.wsscode.pathom.core :as p]
    [com.wsscode.pathom.test :as ptest]
    [edn-query-language.core :as eql]
    [edn-query-language.gen :as eqlgen]
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [fulcro-spec.diff :as diff]
    [com.fulcrologic.fulcro.algorithms.legacy-db-tree :as fpp]))

(declare =>)

;; helpers

(defn expand-meta [f]
  (walk/postwalk
    (fn [x]
      ; calls have metadata with line/column numbers in them in clj...ignore those
      (if (and (meta x) (not= #{:line :column} (-> x meta keys set)))
        {::source x ::meta (meta x)}
        x))
    f))

(defn fake-ident [ident-fn]
  (comp/configure-component! (fn [_]) ::FakeComponent {:ident ident-fn}))

(defn first-ident [this props]
  (vec (first props)))

(defn inject-components [query]
  (eql/ast->query
    (p/transduce-children
      (map (fn [{:keys [key type query] :as node}]
             (if (and (= :join type)
                   (or (map? query)
                     (not (ptest/hash-mod? key 10))))
               (assoc node :component (fake-ident first-ident))
               node)))
      (eql/query->ast query))))

(def parser
  (p/parser
    {::p/env     {::ptest/depth-limit 10
                  ::p/reader          ptest/reader
                  ::p/union-path      ptest/union-test-path}
     ::p/plugins [p/request-cache-plugin]}))

(defn query->db [query]
  (let [query' (inject-components query)
        tree   (parser {} query)]
    (tree->db query' tree)))

;; simple

(defn verify-db->tree
  "Run db->tree in both old and new implementations. Returns the result when
  they match, when they don't an assertion will fail."
  [query entity db]
  (let [new-impl (denorm/db->tree query entity db)
        old-impl (fpp/db->tree query entity db)]
    (assert (= new-impl old-impl) {:new-impl new-impl
                                   :old-impl old-impl
                                   :diff     (diff/diff old-impl new-impl)})
    new-impl))

(defn verify-db->tree-generated [query]
  (let [db (query->db query)]
    (verify-db->tree query db (meta db))))

(specification "db->tree"
  (assertions
    "simple cases"
    (verify-db->tree [] {} {}) => {}
    (verify-db->tree [:foo] {} {}) => {}
    (verify-db->tree [:foo] {:foo "bar"} {}) => {:foo "bar"}
    (verify-db->tree [:foo] {:foo "bar" :more "data"} {}) => {:foo "bar"}

    "joins"
    (verify-db->tree [{:foo [:bar]}]
      {:foo {:bar "baz" :extra "data"}} {})
    => {:foo {:bar "baz"}}

    (verify-db->tree [{:foo [:bar]}]
      {:foo [:point 123]}
      {:point {123 {:bar "baz" :extra "data"}}})
    => {:foo {:bar "baz"}}

    "join to many"
    (verify-db->tree [{:foo [:x]}]
      {:foo [[:x 1] [:x 2] [:x 3]]}
      {:x {1 {:x 1} 2 {:x 2} 3 {:x 3}}})
    => {:foo [{:x 1} {:x 2} {:x 3}]}

    "unions"
    (verify-db->tree
      [{:j {:a [:a :b]
            :c [:c :d]
            :e [:e :f]}}]
      {:j [:c 2]}
      {:a {1 {:a 1 :b "b"}}
       :c {2 {:c 2 :d "d"}}
       :e {3 {:e 3 :f "f"}}})
    => {:j {:c 2, :d "d"}}

    (verify-db->tree
      [{:j {:a [:a :b]
            :c [:c :d]
            :e [:e :f]}}]
      {:j [[:e 3]
           [:c 2]]}
      {:a {1 {:a 1 :b "b"}}
       :c {2 {:c 2 :d "d"}}
       :e {3 {:e 3 :f "f"}}})
    => {:j [{:e 3, :f "f"} {:c 2, :d "d"}]}

    "clean ident get"
    (verify-db->tree [[:point 123]]
      {:foo [:point 123]}
      {})
    => {}

    (verify-db->tree [[:point 123]]
      {:foo [:point 123]}
      {:point {123 {:bar "baz" :extra "data"}}})
    => {[:point 123] {:bar "baz", :extra "data"}}

    (verify-db->tree [{:entry [[:point 123]]}]
      {:entry {:data "foo"}}
      {:point {123 {:bar "baz" :extra "data"}}})
    => {:entry {[:point 123] {:bar "baz", :extra "data"}}}

    "ident join"
    (verify-db->tree [{[:point 123] [:bar]}]
      {:entry {:data "foo"}}
      {:point {123 {:bar "baz" :extra "data"}}})
    => {[:point 123] {:bar "baz"}}

    "recursion"
    (verify-db->tree '[{:entry [:message {:parent ...}]}]
      {:entry {:id 1 :message "foo" :parent [:entry 2]}}
      {:entry {1 {:id 1 :message "foo" :parent [:entry 2]}
               2 {:id 2 :message "foo" :parent [:entry 3]}
               3 {:id 3 :message "foo"}}})
    => {:entry {:message "foo", :parent {:message "foo", :parent {:message "foo"}}}}

    (verify-db->tree '[{:entry [:message {:parent ...}]}]
      {:entry {:id 1 :message "foo" :parent [:entry 2]}}
      {:entry {1 {:id 1 :message "foo" :parent [:entry 2]}
               2 {:id 2 :message "foo" :parent [:entry 3]}
               3 {:id 3 :message "foo" :parent nil}}})
    => {:entry {:message "foo", :parent {:message "foo", :parent {:message "foo"}}}}

    (verify-db->tree '[{:entry [:message {:parent 1}]}]
      {:entry {:id 1 :message "foo" :parent [:entry 2]}}
      {:entry {1 {:id 1 :message "foo" :parent [:entry 2]}
               2 {:id 2 :message "foo" :parent [:entry 3]}
               3 {:id 3 :message "foo"}}})
    => {:entry {:message "foo", :parent {:message "foo"}}}

    "recursion cycle"
    (verify-db->tree '[{:entry [:id :message {:parent 1}]}]
      {:entry {:id 1 :message "foo" :parent [:entry 2]}}
      {:entry {1 {:id 1 :message "foo" :parent [:entry 2]}
               2 {:id 2 :message "foo" :parent [:entry 3]}
               3 {:id 3 :message "foo" :parent [:entry 1]}}})
    => {:entry {:id 1, :message "foo", :parent {:id 2, :message "foo"}}}

    "link queries as props"
    (denorm/db->tree
      [{[:point 123] [:bar]} [:root/value '_]]
      {}
      {:root/value 42
       :point      {123 {:bar "baz" :extra "data"}}})
    => {[:point 123] {:bar "baz"} :root/value 42}

    "link queries on joins"
    (denorm/db->tree
      [{[:root/value '_] [:a]}]
      {:x 22}
      {:root/value {:a 1 :b 2}})
    => {:root/value {:a 1}}

    "wildcard"
    (verify-db->tree
      ['*]
      {:foo [:point 123] :x 42}
      {:point {123 {:bar "baz" :extra "data"}}})
    => {:foo [:point 123], :x 42}

    (denorm/db->tree
      [{:foo [:bar]} '*]
      {:foo [:point 123] :x 42}
      {:point {123 {:bar "baz" :extra "data"}}})
    => {:foo {:bar "baz"} :x 42}

    (denorm/db->tree [[:point 123] '*]
      {:foo [:point 123]}
      {:point {123 {:bar "baz" :extra "data"}}})
    => {:foo         [:point 123]
        [:point 123] {:bar "baz", :extra "data"}}))

(specification "db->tree - time"
  (binding [denorm/*denormalize-time* 42]
    (assertions
      (expand-meta (denorm/db->tree [:foo] {:foo "bar"} {}))
      => {::source {:foo "bar"}
          ::meta   {::denorm/time 42}}

      (expand-meta (denorm/db->tree [{:foo [:bar]}] {:foo {:bar "baz"}} {}))
      => {::source {:foo {::source {:bar "baz"}
                          ::meta   {::denorm/time 42}}}
          ::meta   {::denorm/time 42}})))

(specification "db->tree - from generated"
  (assertions
    "A"
    (verify-db->tree-generated [[:A/A 0]])
    => {}

    "B"
    (verify-db->tree-generated [{[:A/A 0] {:A/A []}}])
    => {}

    "C"
    (verify-db->tree-generated [{:!-n1/y!PN [{:A/A [:A/B]}]}])
    => {:!-n1/y!PN [{:A/A {:A/B ":A/B"}} {:A/A {:A/B ":A/B"}}]}

    "D"
    (verify-db->tree-generated [{:A/A {:A/A []}}])
    => {:A/A []}

    "E"
    (verify-db->tree-generated [{:a/A {:A/A [:A/A0]}}])
    => {:a/A {}}

    "F"
    (verify-db->tree-generated [{:M/*S {:A/A []}}])
    => {:M/*S [{}]}

    "G"
    (verify-db->tree-generated [{:A/A [{:A/A* {:A/A [:A/B]}}]}])
    => {:A/A {:A/A* [{}]}}))

(defsc PersonA [_ _]
  {:query (fn [] '[:person/id {:person/spouse ...}])
   :ident :person/id})

(defsc Person3 [_ _]
  {:query (fn [] '[:person/id {:person/spouse 3}])
   :ident :person/id})

(defsc SelfParentedPerson [_ _]
  {:query (fn [] '[:person/id {:person/children ...}])
   :ident :person/id})

(defsc SelfParentedPerson3 [_ _]
  {:query (fn [] '[:person/id {:person/children 3}])
   :ident :person/id})

(specification "Infinite loop detection"
  (let [db {:person/id {1 {:person/id 1 :person/spouse [:person/id 2]}
                        2 {:person/id 2 :person/spouse [:person/id 1]}
                        ;; I am my own parent...data error
                        3 {:person/id 3 :person/children [[:person/id 4]]}
                        4 {:person/id 4 :person/children [[:person/id 3]]}
                        }}]
    (component "To-many"
      ;; Not supported
      #_(component "n recursion"
        (let [tree (denorm/db->tree (comp/get-query SelfParentedPerson3) (get-in db [:person/id 3]) db)]
          (assertions
            "Stops the recursion specified depth"
            tree => {:person/id     1
                     :person/spouse {:person/id     2
                                     :person/spouse {:person/id     1
                                                     :person/spouse {:person/id 2}}}})))
      (component "... recursion"
        (let [tree (denorm/db->tree (comp/get-query SelfParentedPerson) (get-in db [:person/id 3]) db)]
          (assertions
            "Stops the recursion after items have been seen once"
            tree => {:person/id 3 :person/children [{:person/id 4 :person/children [{:person/id 3 :person/children []}]}]}))))
    (component "To-one"
      (component "n recursion"
        (let [tree (denorm/db->tree (comp/get-query Person3) (get-in db [:person/id 1]) db)]
          (assertions
            "Stops the recursion specified depth"
            tree => {:person/id     1
                     :person/spouse {:person/id     2
                                     :person/spouse {:person/id     1
                                                     :person/spouse {:person/id 2}}}})))
      (component "... recursion"
        (let [tree (denorm/db->tree (comp/get-query PersonA) (get-in db [:person/id 1]) db)]
          (assertions
            "Stops the recursion after items have been seen once"
            tree => {:person/id 1 :person/spouse {:person/id 2 :person/spouse {:person/id 1}}}))))
    ))
;; generative

(defn compare-tree-results [query]
  (let [db (query->db query)]
    (= (denorm/db->tree query db (meta db)) (fpp/db->tree query db (meta db)))))

(defn db->tree-consistency-property [query-gen]
  (props/for-all [query query-gen] (compare-tree-results query)))

(defn db->tree-consistency-property-without-db [query-gen]
  (props/for-all [query query-gen]
    (let [tree (parser {} query)]
      (= (denorm/db->tree query tree {}) (fp/db->tree query tree {})))))

(defn gen-tree-props []
  (eqlgen/make-gen
    {::eql/gen-query-expr
     (fn gen-query-expr [{::eql/keys [gen-property]
                          :as        env}]
       (gen-property env))}
    ::eql/gen-query))

(comment
  (tc/quick-check 50 (db->tree-consistency-property-without-db (gen-tree-props))))

#_(test/defspec generator-makes-valid-db-props {} (valid-db-tree-props))

(defn gen-join-no-links []
  (eqlgen/make-gen {::eql/gen-query-expr
                    (fn gen-query-expr [{::eql/keys [gen-property gen-join]
                                         :as        env}]
                      (gen/frequency [[20 (gen-property env)]
                                      [6 (gen-join env)]]))

                    ::eql/gen-join-key
                    (fn gen-join-key [{::eql/keys [gen-property] :as env}]
                      (gen-property env))

                    ::eql/gen-join-query
                    (fn gen-join-query [{::eql/keys [gen-query] :as env}]
                      (gen-query env))}
    ::eql/gen-query))

(comment
  (tc/quick-check 50 (db->tree-consistency-property-without-db (gen-join-no-links)) :max-size 12))

(defn gen-join-with-links []
  (eqlgen/make-gen
    {::eql/gen-query-expr
     (fn gen-query-expr [{::eql/keys [gen-property gen-join]
                          :as        env}]
       (gen/frequency [[20 (gen-property env)]
                       [6 (gen-join env)]]))

     ::eql/gen-join-key
     (fn gen-join-key [{::eql/keys [gen-property] :as env}]
       (gen-property env))

     ::eql/gen-join-query
     (fn gen-join-query [{::eql/keys [gen-query] :as env}]
       (gen-query env))}
    ::eql/gen-query))

(comment
  (tc/quick-check 50 (db->tree-consistency-property (gen-join-with-links)) :max-size 12))

(defn gen-links-including-ident-keys []
  (eqlgen/make-gen
    {::eql/gen-query-expr
     (fn gen-query-expr [{::eql/keys [gen-property gen-join]
                          :as        env}]
       (gen/frequency [[20 (gen-property env)]
                       [6 (gen-join env)]]))

     ::eql/gen-join-query
     (fn gen-join-query [{::eql/keys [gen-query] :as env}]
       (gen-query env))}
    ::eql/gen-query))

(comment
  (tc/quick-check 50 (db->tree-consistency-property (gen-links-including-ident-keys)) :max-size 12))

(defn gen-unions []
  (eqlgen/make-gen
    {::eql/gen-query-expr
     (fn gen-query-expr [{::eql/keys [gen-property gen-join]
                          :as        env}]
       (gen/frequency [[20 (gen-property env)]
                       [6 (gen-join env)]]))

     ::eql/gen-join-key
     (fn gen-join-key [{::eql/keys [gen-property] :as env}]
       (gen-property env))

     ::eql/gen-join-query
     (fn gen-join-query [{::eql/keys [gen-query gen-union] :as env}]
       (gen/frequency [[2 (gen-query env)]
                       [1 (gen-union env)]]))}
    ::eql/gen-query))

(comment
  (tc/quick-check 50 (db->tree-consistency-property (gen-unions)) :max-size 12))

(defn gen-recursion []
  (eqlgen/make-gen
    {::eql/gen-query-expr
     (fn gen-query-expr [{::eql/keys [gen-property gen-join]
                          :as        env}]
       (gen/frequency [[20 (gen-property env)]
                       [6 (gen-join env)]]))

     ::eql/gen-join-key
     (fn gen-join-key [{::eql/keys [gen-property] :as env}]
       (gen-property env))

     ::eql/gen-join-query
     (fn gen-join-query [{::eql/keys [gen-query gen-recursion] :as env}]
       (gen/frequency [[2 (gen-query env)]
                       [1 (gen-recursion env)]]))}
    ::eql/gen-query))

(comment
  (tc/quick-check 50 (db->tree-consistency-property (gen-recursion)) :max-size 12))

(defn gen-read-queries []
  (eqlgen/make-gen
    {::eql/gen-query-expr
     (fn gen-query-expr [{::eql/keys [gen-property gen-join gen-ident gen-param-expr gen-special-property gen-mutation]
                          :as        env}]
       (gen/frequency [[20 (gen-property env)]
                       [6 (gen-join env)]
                       [1 (gen-ident env)]
                       [2 (gen-param-expr env)]]))} ::eql/gen-query))

(comment
  (tc/quick-check 50 (db->tree-consistency-property (gen-read-queries)) :max-size 12))

(defn debug-query-case [query]
  (let [query    (inject-components query)
        tree     (parser {} query)
        db       (tree->db query tree)
        new-impl (denorm/db->tree query db (meta db))
        old-impl (fp/db->tree query db (meta db))]
    {:valid?    (= new-impl old-impl)
     :query     query
     :tree      tree
     :db-entity db
     :db        db
     :old-impl  old-impl
     :new-impl  new-impl
     :diff      (diff/diff old-impl new-impl)}))

(comment
  ; breaks old db->tree impl
  (debug-query-case [#:A{:a [#:A{:A [{[:A/A 0] []}]}]}])
  (debug-query-case [{[:A/A 0] {:A/A []}}])
  (debug-query-case [{:A/A [{[:a/A 0] []}]}]))
