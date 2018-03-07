(ns fulcro.client.alpha.css-keywords-spec
  (:require
    [fulcro.client.alpha.css-keywords :as css]
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]))

(specification "Conversion of keywords to CSS IDs and Classes" :focused
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

(specification "Combining keywords on CLJ(s) property maps" :focused
  (let [props         {:className "c1"}
        props-with-id {:id 1 :className "c1"}]
    (assertions
      "adds the given keyword classes to any existing props"
      (css/combine :.a props) => {:className "a c1"}
      "leaves an existing id when kw does not have an ID"
      (css/combine :.a props-with-id) => {:id 1 :className "a c1"}
      "overrides existing id when kw has an ID"
      (css/combine :.a#2 props-with-id) => {:id "2" :className "a c1"})
    #?(:clj (component "On the server:"
              (assertions
                "a nil props and nil kw results in an empty js map"
                (css/combine nil nil) => {}
                "a nil props and real kw results in js props"
                (css/combine :.a.b#2 nil) => {:className "a b"
                                              :id        "2"}
                "a kw with multiple classes combines properly"
                (css/combine :.a.some-class.other-class props) => {:className "a some-class other-class c1"})

              ))))

#?(:cljs
   (specification "Combining keywords on JS property maps" :focused
     (let [js-props         #js {:className "c1"}
           js-props-with-id #js {:id 1 :className "c1"}]
       (assertions
         "maintains the result as a js-object"
         (object? (css/combine :.a js-props)) => true
         "adds the given keyword classes to any existing props"
         (js->clj (css/combine :.a js-props)) => {"className" "a c1"}
         "leaves existing id in place when kw does not have an ID"
         (js->clj (css/combine :.a js-props-with-id)) => {"id"        1
                                                          "className" "a c1"}
         "overrides existing id when kw has an ID"
         (js->clj (css/combine :.a#2 js-props-with-id)) => {"id"        "2"
                                                            "className" "a c1"}
         "a nil kw leaves classes alone"
         (js->clj (css/combine nil js-props-with-id)) => {"id"        1
                                                          "className" "c1"}
         "a nil props and nil kw results in an empty js map"
         (object? (css/combine nil nil)) => true
         (js->clj (css/combine nil nil)) => {}
         "a nil props and real kw results in js props"
         (object? (css/combine :.a.b#2 nil)) => true
         (js->clj (css/combine :.a.b#2 nil)) => {"className" "a b"
                                                 "id"        "2"}
         "a kw with multiple classes combines properly"
         (js->clj (css/combine :.a.some-class.other-class js-props)) => {"className" "a some-class other-class c1"})
       )))
