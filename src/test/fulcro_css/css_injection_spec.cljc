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

(defsc D [_ _]
  {:query ['*]
   :css   []})

(defsc C [_ _]
  {:query ['*]
   :css   []
   :css-include [D]})

(defsc B [_ _]
  {:query ['*]
   :css   []})

(defsc A [_ _]
  {:query [:x]
   :css   [[:a {:color         "red"
                :border-radius "2px"}]]})

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

(defsc JoinAndInclude [_ _]
  {:query [{:a (prim/get-query A)}]
   :css-include [C]})

(specification "find-css-nodes"
  (behavior "Scans the query for components with css"
    (assertions
      "Removes duplicates"
      (injection/find-css-nodes {:component DuplicateCSS}) => [A]
      "Defaults to depth-first order"
      (injection/find-css-nodes {:component NestedRootWithCSS}) => [A B NestedRootWithCSS]
      (injection/find-css-nodes {:component NestedRoot2}) => [B A NestedRoot2]
      "Can find in breadth-first order"
      (injection/find-css-nodes {:component NestedRootWithCSS :order :breadth-first}) => [NestedRootWithCSS A B]
      "Includes items from css-include"
      (injection/find-css-nodes {:component JoinAndInclude}) => [D C A JoinAndInclude])))

(specification "Computing CSS"
  (behavior "When auto-include? is false"
    (when-mocking
      (css/get-css c) => (do
                           (assertions
                             "Uses legacy get-css-rules"
                             c => NestedRoot2)
                           :rules)
      (g/css garden-flags rules) => (do
                                      (assertions
                                       "Uses garden to compute the css"
                                       rules => :rules
                                       garden-flags => {})
                                      ".boo {}")

      (#'injection/compute-css {:component     NestedRoot2
                                :auto-include? false})))
  (behavior "When auto-include? is true"
    (when-mocking
      (injection/find-css-nodes props) =1x=> (do
                                                 (assertions
                                                   "Searches the component supplied"
                                                   (:component props) => A)
                                                 [A])
      (css/get-css-rules c) =1x=> (do
                                    (assertions
                                      "Gets the rules for that component"
                                      c => A)
                                    [:rules])
      (g/css garden-flags rules) => (do
                         (assertions
                           "Uses garden to compute the css"
                           rules => [:rules]
                           garden-flags => {})
                         ".boo {}")

      (#'injection/compute-css {:component     A
                                :auto-include? true})))
  (behavior "When garden compler flags are provided"
    (when-mocking
      (css/get-css c) => (do
                           (assertions
                             "Uses legacy get-css-rules"
                             c => NestedRoot2)
                           :rules)
      (g/css garden-flags rules) => (do
                                      (assertions
                                       "Uses garden to compute the css"
                                       rules => :rules
                                       garden-flags => {:pretty-print? false})
                                      ".boo {}")

      (#'injection/compute-css {:component     NestedRoot2
                                :garden-flags  {:pretty-print? false}
                                :auto-include? false}))))

#?(:clj
   (specification "Style element CSS render"
     (assertions
       "Renders via render-to-str for the server"
       (str/includes? (dom/render-to-str (injection/style-element {:component A})) "<style") => true
       (str/includes? (dom/render-to-str (injection/style-element {:component A})) "color: red") => true
       (str/includes? (dom/render-to-str (injection/style-element {:component    A
                                                                   :garden-flags {:vendors     ["webkit"]
                                                                                  :auto-prefix #{:border-radius}}}))
                      "-webkit-border-radius: 2px;") => true)))
