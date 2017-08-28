(ns fulcro.client.util-spec
  (:require
    [fulcro-spec.core :refer [specification when-mocking assertions behavior]]
    [fulcro.client.util :as util]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]
    [clojure.test :refer [is]]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]))

#?(:cljs
   (specification "Log app state"
     (let [state (atom {:foo        {:a :b
                                     12 {:c         ["hello" "world"]
                                         [:wee :ha] {:e [{:e :g}
                                                         {:a [1 2 3 4]}
                                                         {:t :k}]
                                                     :g :h
                                                     :i :j}}}
                        {:map :key} {:other :data}
                        [1 2 3]     :data})]

       (when-mocking
         (om/app-state _) => state
         (cljs.pprint/pprint data) => data

         (assertions
           "Handle non-sequential keys"
           (util/log-app-state state {:map :key}) => {:other :data}

           "Handles sequential keys"
           (util/log-app-state state [[1 2 3]]) => :data

           "Handles non-sequential and sequential keys together"
           (util/log-app-state state [:foo :a] {:map :key}) => {:foo        {:a :b}
                                                                {:map :key} {:other :data}}

           "Handles distinct paths"
           (util/log-app-state state [:foo 12 [:wee :ha] :g] [{:map :key}]) => {:foo        {12 {[:wee :ha] {:g :h}}}
                                                                                {:map :key} {:other :data}}

           "Handles shared paths"
           (util/log-app-state state [:foo 12 [:wee :ha] :g] [:foo :a]) => {:foo {12 {[:wee :ha] {:g :h}}
                                                                                  :a :b}}

           "Handles keys and paths together"
           (util/log-app-state state {:map :key} [:foo 12 :c 1]) => {:foo        {12 {:c {1 "world"}}}
                                                                     {:map :key} {:other :data}})))))

(specification "strip-parameters"
  (behavior "removes all parameters from"
    (assertions
      "parameterized prop reads"
      (util/strip-parameters `[(:some/key {:arg :foo})]) => [:some/key]

      "parameterized join reads"
      (util/strip-parameters `[({:some/key [:sub/key]} {:arg :foo})]) => [{:some/key [:sub/key]}]

      "nested parameterized join reads"
      (util/strip-parameters
        `[{:some/key [({:sub/key [:sub.sub/key]} {:arg :foo})]}]) => [{:some/key [{:sub/key [:sub.sub/key]}]}]

      "multiple parameterized reads"
      (util/strip-parameters
        `[(:some/key {:arg :foo})
          :another/key
          {:non-parameterized [:join]}
          {:some/other [{:nested [(:parameterized {:join :just-for-fun})]}]}])
      =>
      [:some/key :another/key {:non-parameterized [:join]} {:some/other [{:nested [:parameterized]}]}]

      ;TODO: Uncomment these once om-beta2 ships
      ;"parameterized mutations"
      ;(util/strip-parameters ['(fire-missiles! {:arg :foo})]) => '[(fire-missiles!)]

      ;"multiple parameterized mutations"
      ;(util/strip-parameters ['(fire-missiles! {:arg :foo}) '(walk-the-plank! {:right :now})]) => '[(fire-missiles!) (walk-the-plank!)]
      )))

(defui A
  static om/Ident
  (ident [this props] [:a/by-id (:db/id props)])
  Object
  (render [this] (dom/div nil "")))

(defui B
  Object
  (render [this] (dom/div nil "")))

(def ui-a (om/factory A))
(def ui-b (om/factory B))

(specification "get-ident"
  (assertions
    "Can pull the ident from a class when given props"
    (util/get-ident A {:db/id 1}) => [:a/by-id 1]))

(specification "unique-key"
  (let [a (util/unique-key)
        b (util/unique-key)]
    (assertions
      "Returns a different value every time"
      (= a b) => false)))

(specification "atom?"
  (assertions
    "Detects the atom type"
    (util/atom? (atom {})) => true))

(specification "react-instance?"
  (let [instance (ui-a {})]
    (assertions
      "detects that a given renderered instance has a certain class"
      (util/react-instance? B instance) => nil
      (util/react-instance? A instance) => instance)))

(specification "first-node"
  (let [a1 (ui-a {})
        b  (ui-b {})
        a2 (ui-a {})]
    (assertions
      "returns the first node of a given type in a sequence of react instances"
      (util/first-node A [a1 b a2]) => a1
      (util/first-node A [b a1 a2]) => a1
      (util/first-node A [b a2 a1]) => a2
      (util/first-node B [b a2 a1]) => b
      (util/first-node B [a2 b a1]) => b
      (util/first-node B [a2 a1 b]) => b)))

(def compound (fn [inner-gen]
                (gen/one-of [(gen/list inner-gen)
                             (gen/map inner-gen inner-gen)])))
(def scalars (gen/one-of [gen/int gen/boolean]))
(def my-json-like-thing (gen/recursive-gen compound scalars))

(specification "Transit string helpers"
  (assertions
    "clj->str returns a string"
    (string? (util/transit-clj->str {})) => true
    "str->clj returns a data structure"
    (util/transit-str->clj "[]") => [])
  (behavior "Encode and decode arbitrary data structures"
    (let [samples (gen/sample my-json-like-thing 20)]
      (doseq [sample samples]
        (is (= sample (util/transit-str->clj (util/transit-clj->str sample))))))))

