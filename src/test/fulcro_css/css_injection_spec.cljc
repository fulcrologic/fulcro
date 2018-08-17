(ns fulcro-css.css-injection-spec
  (:require
    [fulcro-spec.core :refer [specification assertions behavior when-mocking]]
    [fulcro-css.css-injection :as injection]
    [fulcro-css.css :as css]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [garden.core :as g]
    #?(:cljs [fulcro.client.dom :as dom]
       :clj  [fulcro.client.dom-server :as dom])
    [clojure.string :as str]))

(defsc C [_ _]
  {:query ['*]
   :css   []})

(defsc B [_ _]
  {:query ['*]
   :css   []})

(defsc A [_ _]
  {:query [:x]
   :css   [[:a {:color "red"}]]})

(defsc DuplicateCSS [_ _]
  {:query [{:a (prim/get-query A)} {:b (prim/get-query A)}]})

(defsc ASibling [_ _]
  {:query [{:x (prim/get-query A)}]})

(defsc BSibling [_ _]
  {:query [{:x (prim/get-query B)}]})

(defsc NestedRootWithCSS [_ _]
  {:css   []
   :query [{:a (prim/get-query ASibling)} {:b (prim/get-query BSibling)}]})

(defsc NestedRoot2 [_ _]
  {:css         []
   :css-include [A]
   :query       [{:a (prim/get-query A)} {:b (prim/get-query BSibling)}]})

(specification "find-css-nodes" :focused
  (behavior "Scans the query for components with css"
    (assertions
      "Removes duplicates"
      (injection/find-css-nodes DuplicateCSS) => [A]
      "Defaults to depth-first order"
      (injection/find-css-nodes NestedRootWithCSS) => [A B NestedRootWithCSS]
      (injection/find-css-nodes NestedRoot2) => [B A NestedRoot2]
      "Can find in breadth-first order"
      (injection/find-css-nodes NestedRootWithCSS :breadth-first) => [NestedRootWithCSS A B])))

(specification "Computing CSS" :focused
  (behavior "When auto-include? is false"
    (when-mocking
      (css/get-css c) => (do
                           (assertions
                             "Uses legacy get-css-rules"
                             c => NestedRoot2)
                           :rules)
      (g/css rules) => (do
                         (assertions
                           "Uses garden to compute the css"
                           rules => :rules)
                         ".boo {}")

      (#'injection/compute-css {:component     NestedRoot2
                                :auto-include? false})))
  (behavior "When auto-include? is true"
    (when-mocking
      (injection/find-css-nodes c order) =1x=> (do
                                                 (assertions
                                                   "Defaults to depth-first order"
                                                   order => :depth-first
                                                   "Searches the component supplied"
                                                   c => A)
                                                 [A])
      (css/get-css-rules c) =1x=> (do
                                    (assertions
                                      "Gets the rules for that component"
                                      c => A)
                                    [:rules])
      (g/css rules) => (do
                         (assertions
                           "Uses garden to compute the css"
                           rules => [:rules])
                         ".boo {}")

      (#'injection/compute-css {:component     A
                                :auto-include? true}))))

#?(:clj
   (specification "Style element CSS render" :focused
     (assertions
       "Renders via render-to-str for the server"
       (str/includes? (dom/render-to-str (injection/style-element {:component A})) "<style") => true
       (str/includes? (dom/render-to-str (injection/style-element {:component A})) "color: red") => true)))
