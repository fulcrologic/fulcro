(ns fulcro.client.util-spec
  (:require
    clojure.pprint
    [fulcro-spec.core :refer [specification when-mocking assertions behavior]]
    [fulcro.util :as primutil]
    [fulcro.client.util :as util]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]
    [clojure.test :refer [is]]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defui defsc]]))

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
  static prim/Ident
  (ident [this props] [:a/by-id (:db/id props)])
  Object
  (render [this] (dom/div nil "")))

(defui B
  Object
  (render [this] (dom/div nil "")))

(def ui-a (prim/factory A))
(def ui-b (prim/factory B))

(specification "get-ident"
  (assertions
    "Can pull the ident from a class when given props"
    (prim/get-ident A {:db/id 1}) => [:a/by-id 1]))

(specification "unique-key"
  (let [a (primutil/unique-key)
        b (primutil/unique-key)]
    (assertions
      "Returns a different value every time"
      (= a b) => false)))

(specification "atom?"
  (assertions
    "Detects the atom type"
    (primutil/atom? (atom {})) => true))

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

(specification "join-entry"
  (assertions
    (primutil/join-entry {:foo [:bar]}) => [:foo [:bar]]
    (primutil/join-entry '({:foo [:bar]} {:param "bar"})) => [:foo [:bar]]
    (primutil/join-entry '{(:foo {:param "bar"}) [:bar]}) => [:foo [:bar]]))

(specification "join-key"
  (assertions
    (primutil/join-key {:foo [:bar]}) => :foo
    (primutil/join-key '({:foo [:bar]} {:param "bar"})) => :foo
    (primutil/join-key '{(:foo {:param "bar"}) [:bar]}) => :foo))
