(ns com.fulcrologic.fulcro.algorithms.normalize-spec
  (:require
    #?(:clj  [clojure.test :refer :all]
       :cljs [clojure.test :refer [deftest]])
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [fulcro-spec.core :refer [assertions specification component when-mocking behavior]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    [com.fulcrologic.fulcro.application :as app]
    [clojure.string :as str]))

(defsc A [this props])
(defsc AQuery [this props] {:query [:x]})
(defsc AState [this props] {:initial-state (fn [params] {})})
(defsc AIdent [this props] {:ident (fn [] [:x 1])})
(defsc APreMerge [this props] {:pre-merge (fn [_])})

(defsc AUIChild [_ _] {:ident     [:ui/id :ui/id]
                       :query     [:ui/id :ui/name]
                       :pre-merge (fn [{:keys [current-normalized data-tree]}]
                                    (merge
                                      {:ui/id   "child-id"
                                       :ui/name "123"}
                                      current-normalized data-tree))})

(defsc AUIChildWithoutPreMerge [_ _]
  {:ident [:ui/id :ui/id]
   :query [:ui/id :ui/name]})

(defsc AUIParent [_ _] {:ident     [:id :id]
                        :query     [:id {:ui/child (comp/get-query AUIChild)}]
                        :pre-merge (fn [{:keys [current-normalized data-tree]}]
                                     (merge
                                       {:ui/child {}}
                                       current-normalized
                                       data-tree))})

(defn- build-simple-ident [ident props]
  (if (fn? ident)
    (ident props)
    [ident (get props ident)]))

(defn- quick-ident-class [ident]
  #?(:cljs (comp/configure-component! (fn []) :A
             {:ident (fn [_ props] (build-simple-ident ident props))})
     :clj  (comp/configure-component! {} :A
             {:ident (fn [_ props] (build-simple-ident ident props))})))

(defn- genc [ident query]
  (with-meta query
    {:component (quick-ident-class ident)}))

(defn- ident-from-prop [available]
  (fn [props]
    (or (some #(if-let [x (get props %)] [% x]) available)
      [:unknown nil])))


(specification "tree->db"
  (assertions
    "[*]"
    (fnorm/tree->db ['*] {:foo "bar"})
    => {:foo "bar"})

  (assertions
    "reading properties"
    (fnorm/tree->db [:a] {:a 1 :z 10})
    => {:a 1, :z 10})

  (assertions
    "union case"
    (fnorm/tree->db [{:multi (genc
                               (ident-from-prop [:a/id :b/id])
                               {:a (genc :a/id [:a/id :a/name])
                                :b (genc :b/id [:b/id :a/name])})}]
      {:multi {:a/id 3}})
    => {:multi [:a/id 3]}

    (fnorm/tree->db [{:multi (genc
                               (ident-from-prop [:a/id :b/id])
                               {:a (genc :a/id [:a/id :a/name])
                                :b (genc :b/id [:b/id :a/name])})}]
      {:multi {:b/id 5}} true)
    => {:multi [:b/id 5]
        :b/id  {5 #:b{:id 5}}}

    (fnorm/tree->db [{:multi (genc
                               (ident-from-prop [:a/id :b/id])
                               {:a (genc :a/id [:a/id :a/name])
                                :b (genc :b/id [:b/id :a/name])})}]
      {:multi [{:b/id 3}
               {:c/id 5}
               {:a/id 42}]} true)
    => {:multi   [[:b/id 3] [:unknown nil] [:a/id 42]]
        :b/id    {3 #:b{:id 3}}
        :unknown {nil #:c{:id 5}}
        :a/id    {42 #:a{:id 42}}})

  (let [last-log (volatile! nil)]
    (with-redefs [taoensso.timbre/-log! (fn fake-log [_config level _ _ _ _ _ vargs & _]
                                          (when (= :error level)
                                            (vreset! last-log (str/join " " @vargs))))]
      (assertions
        "union case missing ident"
        (fnorm/tree->db [{:multi {:a (genc :a/id [:a/id :a/name])
                                  :b (genc :b/id [:b/id :a/name])}}]
                        {:multi {:a/id 3}})
        => {}

        @last-log =fn=> #(re-find #"Union components must have an ident" %))))

  (assertions
    "normalized data"
    (fnorm/tree->db [{:foo (genc :id [:id])}] {:foo [:id 123]} true)
    => {:foo [:id 123]})

  (assertions
    "to one join"
    (fnorm/tree->db [{:foo (genc :id [:id])}] {:foo {:id 123 :x 42}} true)
    => {:foo [:id 123]
        :id  {123 {:id 123, :x 42}}}

    (fnorm/tree->db [{:foo (genc :id [:id])}] {:foo {:x 42}} true)
    => {:foo [:id nil],
        :id  {nil {:x 42}}}

    (fnorm/tree->db [{:foo (genc :id [:id])}] {:bar {:id 123 :x 42}} true)
    => {:bar {:id 123, :x 42}})

  (assertions
    "to many join"
    (fnorm/tree->db [{:foo (genc :id [:id])}] {:foo [{:id 1 :x 42}
                                                     {:id 2}]} true)
    => {:foo [[:id 1] [:id 2]], :id {1 {:id 1, :x 42}, 2 {:id 2}}})

  (assertions
    "bounded recursive query"
    (fnorm/tree->db [{:root (genc :id [:id {:p 2}])}]
      {:root {:id 1 :p {:id 2 :p {:id 3 :p {:id 4 :p {:id 5}}}}}} true)
    => {:root [:id 1]
        :id   {5 {:id 5}
               4 {:id 4, :p [:id 5]}
               3 {:id 3, :p [:id 4]}
               2 {:id 2, :p [:id 3]}
               1 {:id 1, :p [:id 2]}}})

  (assertions
    "unbounded recursive query"
    (fnorm/tree->db [{:root (genc :id [:id {:p '...}])}]
      {:root {:id 1 :p {:id 2 :p {:id 3 :p {:id 4 :p {:id 5}}}}}} true)
    => {:root [:id 1]
        :id   {5 {:id 5}
               4 {:id 4, :p [:id 5]}
               3 {:id 3, :p [:id 4]}
               2 {:id 2, :p [:id 3]}
               1 {:id 1, :p [:id 2]}}})

  (behavior "using with pre-merge-transform"
    (assertions
      (fnorm/tree->db AUIParent {:id 123} true (merge/pre-merge-transform {}))
      => {:id       123
          :ui/child [:ui/id "child-id"]
          :ui/id    {"child-id" {:ui/id "child-id", :ui/name "123"}}}

      "to one idents"
      (fnorm/tree->db AUIParent {:id 123} true
        (merge/pre-merge-transform {:id    {123 {:id       123
                                                 :ui/child [:ui/id "child-id"]}}
                                    :ui/id {"child-id" {:ui/id "child-id", :ui/name "123"}}}))
      => {:id       123
          :ui/child [:ui/id "child-id"]}

      "to many idents"
      (fnorm/tree->db AUIParent {:id 123} true
        (merge/pre-merge-transform {:id    {123 {:id       123
                                                 :ui/child [[:ui/id "child-id"]
                                                            [:ui/id "child-id2"]]}}
                                    :ui/id {"child-id"  {:ui/id "child-id", :ui/name "123"}
                                            "child-id2" {:ui/id "child-id2", :ui/name "456"}}}))
      => {:id       123
          :ui/child [[:ui/id "child-id"]
                     [:ui/id "child-id2"]]})))
