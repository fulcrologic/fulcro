(ns fulcro.client.alpha.css-keywords-spec
  (:require
    [fulcro.client.alpha.css-keywords :as css]
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]))

(specification "Conversion of keywords to CSS IDs and Classes"
  (assertions
    "classnames are given as a vector"
    (css/parse :.a) => {:classes ["a"]}
    (css/parse :.a.b) => {:classes ["a" "b"]}
    (css/parse :.a.b.hello-world) => {:classes ["a" "b" "hello-world"]}
    "converts class and ID combos"
    (css/parse :.a#j) => {:id "j" :classes ["a"]}
    "order doesn't matter"
    (css/parse :#j.a) => {:id "j" :classes ["a"]}
    "multiple classes are allowed"
    (css/parse :#j.a.b) => {:id "j" :classes ["a" "b"]}
    (css/parse :.a#j.b) => {:id "j" :classes ["a" "b"]}
    "throws an exception for invalid keywords"
    (css/parse :a) =throws=> {:regex #"Invalid style"}
    (css/parse :.a#.j) =throws=> {:regex #"Invalid style"}))

(specification "Combining keywords on CLJ(s) property maps"
  (let [props         {:className "c1"}
        props-with-id {:id 1 :className "c1"}]
    (assertions
      "adds the given keyword classes to any existing props"
      (css/combine props :.a) => {:className "a c1"}
      "leaves an existing id when kw does not have an ID"
      (css/combine props-with-id :.a) => {:id 1 :className "a c1"}
      "overrides existing id when kw has an ID"
      (css/combine props-with-id :.a#2) => {:id "2" :className "a c1"})
    ;; Need to run these because the cljs version of these emit JS maps
    #?(:clj
       (component "On the server:"
         (assertions
           "a nil props and nil kw results in an empty js map"
           (css/combine nil nil) => {}
           "a nil props and real kw results in js props"
           (css/combine nil :.a.b#2) => {:className "a b"
                                         :id        "2"}
           "a kw with multiple classes combines properly"
           (css/combine props :.a.some-class.other-class) => {:className "a some-class other-class c1"})))))

#?(:cljs
   (specification "Combining keywords on JS property maps"
     (let [js-props         #js {:className "c1"}
           js-props-with-id #js {:id 1 :className "c1"}]
       (assertions
         "maintains the result as a js-object"
         (object? (css/combine js-props :.a)) => true
         "adds the given keyword classes to any existing props"
         (js->clj (css/combine js-props :.a)) => {"className" "a c1"}
         "leaves existing id in place when kw does not have an ID"
         (js->clj (css/combine js-props-with-id :.a)) => {"id"        1
                                                          "className" "a c1"}
         "overrides existing id when kw has an ID"
         (js->clj (css/combine js-props-with-id :.a#2)) => {"id"        "2"
                                                            "className" "a c1"}
         "a nil kw leaves classes alone"
         (js->clj (css/combine js-props-with-id nil)) => {"id"        1
                                                          "className" "c1"}
         "a nil props and nil kw results in an empty js map"
         (object? (css/combine nil nil)) => true
         (js->clj (css/combine nil nil)) => {}
         "a nil props and real kw results in js props"
         (object? (css/combine nil :.a.b#2)) => true
         (js->clj (css/combine nil :.a.b#2)) => {"className" "a b"
                                                 "id"        "2"}
         "a kw with multiple classes combines properly"
         (js->clj (css/combine js-props :.a.some-class.other-class)) => {"className" "a some-class other-class c1"}))))
