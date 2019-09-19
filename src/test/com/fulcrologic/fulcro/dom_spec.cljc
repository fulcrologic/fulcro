(ns com.fulcrologic.fulcro.dom-spec
  (:require
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    [com.fulcrologic.fulcro.dom-common :as cdom]
    com.fulcrologic.fulcro.dom
    [clojure.string :as str]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom :refer [div p span]]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])
    #?@(:cljs [[goog.object :as gobj]
               [goog.dom :as gdom]]))
  #?(:clj
     (:import (cljs.tagged_literals JSValue))))

(specification "Conversion of keywords to CSS IDs and Classes"
  (assertions
    "classnames are given as a vector"
    (#'cdom/parse :.a) => {:classes ["a"]}
    (#'cdom/parse :.a.b) => {:classes ["a" "b"]}
    (#'cdom/parse :.a.b.hello-world) => {:classes ["a" "b" "hello-world"]}
    "converts class and ID combos"
    (#'cdom/parse :.a#j) => {:id "j" :classes ["a"]}
    "order doesn't matter"
    (#'cdom/parse :#j.a) => {:id "j" :classes ["a"]}
    "multiple classes are allowed"
    (#'cdom/parse :#j.a.b) => {:id "j" :classes ["a" "b"]}
    (#'cdom/parse :.a#j.b) => {:id "j" :classes ["a" "b"]}
    "throws an exception for invalid keywords"
    (#'cdom/parse :a) =throws=> #"Invalid style"
    (#'cdom/parse :.a#.j) =throws=> #"Invalid style"))

(specification "Combining keywords on CLJ(s) property maps"
  (let [props         {:className "c1"}
        props-with-id {:id 1 :className "c1"}]
    (assertions
      "adds the given keyword classes to any existing props"
      (cdom/add-kwprops-to-props props :.a) => {:className "a c1"}
      "leaves an existing id when kw does not have an ID"
      (cdom/add-kwprops-to-props props-with-id :.a) => {:id 1 :className "a c1"}
      "overrides existing id when kw has an ID"
      (cdom/add-kwprops-to-props props-with-id :.a#2) => {:id "2" :className "a c1"})
    ;; Need to run these because the cljs version of these emit JS maps
    #?(:clj
       (component "On the server:"
         (assertions
           "a nil props and nil kw results in an empty js map"
           (cdom/add-kwprops-to-props nil nil) => {}
           "a nil props and real kw results in js props"
           (cdom/add-kwprops-to-props nil :.a.b#2) => {:className "a b"
                                                       :id        "2"}
           "a kw with multiple classes combines properly"
           (cdom/add-kwprops-to-props props :.a.some-class.other-class) => {:className "a some-class other-class c1"})))))

#?(:cljs
   (specification "Combining keywords on JS property maps"
     (let [js-props         #js {:className "c1"}
           js-props-with-id #js {:id 1 :className "c1"}]
       (assertions
         "maintains the result as a js-object"
         (object? (cdom/add-kwprops-to-props js-props :.a)) => true
         "adds the given keyword classes to any existing props"
         (js->clj (cdom/add-kwprops-to-props js-props :.a)) => {"className" "a c1"}
         "leaves existing id in place when kw does not have an ID"
         (js->clj (cdom/add-kwprops-to-props js-props-with-id :.a)) => {"id"        1
                                                                        "className" "a c1"}
         "overrides existing id when kw has an ID"
         (js->clj (cdom/add-kwprops-to-props js-props-with-id :.a#2)) => {"id"        "2"
                                                                          "className" "a c1"}
         "a nil kw leaves classes alone"
         (js->clj (cdom/add-kwprops-to-props js-props-with-id nil)) => {"id"        1
                                                                        "className" "c1"}
         "a nil props and nil kw results in an empty js map"
         (object? (cdom/add-kwprops-to-props nil nil)) => true
         (js->clj (cdom/add-kwprops-to-props nil nil)) => {}
         "a nil props and real kw results in js props"
         (object? (cdom/add-kwprops-to-props nil :.a.b#2)) => true
         (js->clj (cdom/add-kwprops-to-props nil :.a.b#2)) => {"className" "a b"
                                                               "id"        "2"}
         "a kw with multiple classes combines properly"
         (js->clj (cdom/add-kwprops-to-props js-props :.a.some-class.other-class)) => {"className" "a some-class other-class c1"}))))

#?(:clj
   (defn jsvalue->map
     "Converts a data structure (recursively) that contains JSValues, replacing any JSValue with
     a map {:jsvalue val} where val is the original val in the JSValue."
     [v]
     (cond
       (instance? JSValue v) {:jsvalue (jsvalue->map (.-val v))}
       (map? v) (clojure.walk/prewalk (fn [n] (if (instance? JSValue n) (jsvalue->map n) n)) v)
       (vector? v) (mapv jsvalue->map v)
       (seq? v) (doall (map jsvalue->map v))
       :else v)))

#?(:clj
   (specification "Macro processing"
     (assertions
       "kw + nil props converts to a runtime js obj"
       (jsvalue->map (#'com.fulcrologic.fulcro.dom/emit-tag "div" [:.a nil "Hello"]))
       => `(com.fulcrologic.fulcro.dom/macro-create-element*
             {:jsvalue ["div"
                        {:jsvalue {:className "a"}}
                        "Hello"]})
       "kw + CLJS data converts to a runtime js obj"
       (jsvalue->map (#'com.fulcrologic.fulcro.dom/emit-tag "div" [:.a {:data-x 1} "Hello"]))
       => `(com.fulcrologic.fulcro.dom/macro-create-element*
             {:jsvalue ["div"
                        {:jsvalue {:data-x    1
                                   :className "a"}}
                        "Hello"]})
       "kw + CLJS data with symbols embeds runtime conversion on the symbols"
       (jsvalue->map (#'com.fulcrologic.fulcro.dom/emit-tag "div" [:.a {:data-x 'some-var} "Hello"]))
       => `(com.fulcrologic.fulcro.dom/macro-create-element "div" [{:data-x ~'some-var} "Hello"] :.a)
       "kw + JS data emits a runtime combine operation on the JS data without embedded processing."
       (jsvalue->map (#'com.fulcrologic.fulcro.dom/emit-tag "div" [:.a (JSValue. {:data-x 'some-var}) "Hello"]))
       => `(com.fulcrologic.fulcro.dom/macro-create-element*
             {:jsvalue ["div"
                        (cdom/add-kwprops-to-props {:jsvalue {:data-x ~'some-var}} :.a)
                        "Hello"]})
       "Plain JS maps are passed through as props"
       (jsvalue->map (#'com.fulcrologic.fulcro.dom/emit-tag "div" [(JSValue. {:data-x 1}) "Hello"]))
       => `(com.fulcrologic.fulcro.dom/macro-create-element* {:jsvalue ["div"
                                                                        {:jsvalue {:data-x 1}}
                                                                        "Hello"]})
       "kw + symbol emits runtime conversion"
       (jsvalue->map (#'com.fulcrologic.fulcro.dom/emit-tag "div" [:.a 'props "Hello"]))
       => `(com.fulcrologic.fulcro.dom/macro-create-element "div" [~'props "Hello"] :.a)

       "expression emits runtime conversion"
       (jsvalue->map (#'com.fulcrologic.fulcro.dom/emit-tag "div" [:.a '(props-map) "Hello"]))
       => `(com.fulcrologic.fulcro.dom/macro-create-element "div" [~'(props-map) "Hello"] :.a)

       "embedded code in props is passed through"
       (jsvalue->map (#'com.fulcrologic.fulcro.dom/emit-tag "div" [:.a '{:onClick (fn [] (do-it))} "Hello"]))
       => `(com.fulcrologic.fulcro.dom/macro-create-element "div" [{:onClick (~'fn [] (~'do-it))} "Hello"] :.a))))

#?(:cljs
   (specification "DOM Tag Macros (CLJS)"
     (provided "It is passed no arguments"
       (dom/macro-create-element* args) => (do
                                             (assertions
                                               "passes the tag name"
                                               (aget args 0) => "div"
                                               "passes the an empty map"
                                               (js->clj (aget args 1)) => {}
                                               "passes the children"
                                               (aget args 2) => "Hello"))

       (div "Hello"))

     (provided "It is passed a CLJ map:"
       (dom/macro-create-element* args) => (do
                                             (assertions
                                               "passes the map in JS"
                                               (js->clj (aget args 1)) => {"className" "a"}))

       (div {:className "a"} "Hello"))

     (let [some-class "x"]
       (provided "It is passed a JS map:"
         (dom/macro-create-element* args) => (do
                                               (assertions
                                                 "passes the map in JS, resolving any bindings"
                                                 (js->clj (aget args 1)) => {"className" "x"}))

         (div #js {:className some-class} "Hello")))

     (provided "It is passed nil params:"
       (dom/macro-create-element* args) => (do
                                             (assertions
                                               "passes an empty JS map"
                                               (js->clj (aget args 1)) => {}))

       (div nil "Hello"))

     (provided "It is passed ONLY a class kw:"
       (dom/macro-create-element* args) => (do
                                             (assertions
                                               "then only the class is set"
                                               (js->clj (aget args 1)) => {"className" "a"}))

       (div :.a "Hello"))

     (provided "It is passed an id/class kw:"
       (dom/macro-create-element* args) => (do
                                             (assertions
                                               "passes the map in JS"
                                               (js->clj (aget args 1)) => {"id"        "j"
                                                                           "className" "a"}))

       (div :.a#j "Hello"))

     (provided "there is code embedded in literal props:"
       (dom/macro-create-element* args) =1x=> (do
                                                (assertions
                                                  "cljs props combined with class specifier results in updated cljs props"
                                                  (js->clj (aget args 1)) => {"id"        "y"
                                                                              "className" "x a"}))


       (div :.x#y {:className (cond-> ""
                                true (str "a"))} "Hello"))

     (provided "nesting is done via threading macro:"
       (dom/macro-create-element* args) =1x=> (do
                                                (assertions
                                                  "cljs props combined with class specifier results in updated cljs props"
                                                  (js->clj (aget args 0)) => "p"))
       (dom/macro-create-element* args) =1x=> (do
                                                (assertions
                                                  "cljs props combined with class specifier results in updated cljs props"
                                                  (js->clj (aget args 0)) => "div"))


       (->> (p nil "Hello")
         (div :.x#y {:className (cond-> ""
                                  true (str "a"))})))

     (let [some-class      "x"
           some-js-props   #js {:className "a" :id 1}
           some-cljs-props {:className "a" :id 1}]
       (provided "It is passed a variety of alternate arguments for props:"
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "kw + cljs -> merges the classes. The ID from the keyword overrides the ID"
                                                    (js->clj (aget args 1)) => {"id"        "j"
                                                                                "className" "a c e b"}))
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "kw-based class and ID order doesn't matter"
                                                    (js->clj (aget args 1)) => {"id"        "j"
                                                                                "className" "a c e b"}))
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "classnames can be combined in from a binding in env"
                                                    (js->clj (aget args 1)) => {"id"        "j"
                                                                                "className" "a c e x"}))
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "cljs props passed as a symbol result in runtime conversion to js props"
                                                    (js->clj (aget args 1)) => {"id"        1
                                                                                "className" "a"}))
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "js props passed as a symbol result in runtime pass-through as js props"
                                                    (js->clj (aget args 1)) => {"id"        1
                                                                                "className" "a"}))
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "js props combined with class specifier results in updated js props"
                                                    (js->clj (aget args 1)) => {"id"        "y"
                                                                                "className" "x a"}))
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "cljs props combined with class specifier results in updated cljs props"
                                                    (js->clj (aget args 1)) => {"id"        "y"
                                                                                "className" "x a"}))

         (div :.a.c.e#j {:id 1 :className "b"} "Hello")
         (div :#j.a.c.e {:id 1 :className "b"} "Hello")
         (div :.a#j.c.e {:id 1 :className some-class} "Hello")
         (div some-cljs-props "Hello")
         (div some-js-props "Hello")
         (div :.x#y some-js-props "Hello")
         (div :.x#y some-cljs-props "Hello")))

     (provided "It is used as a lambda with pct child"
       (dom/macro-create-element* args) =1x=> (assertions
                                                "The runtime version of the processing is called "
                                                (aget args 0) => "div"
                                                (js->clj (aget args 1)) => {"className" "x"
                                                                            "data-x"    22})

       (mapv #(div :.x {:data-x 22} %) ["Hello"]))

     (provided "It is used as a lambda with pct attrs"
       (dom/macro-create-element* args) =1x=> (assertions
                                                "The runtime version of the processing is called "
                                                (aget args 0) => "div"
                                                (js->clj (aget args 1)) => {"className" "x"
                                                                            "data-x"    22})

       (mapv #(div :.x % "Hello") [{:data-x 22}]))

     (let [real-mce dom/macro-create-element*]
       (provided "There are nested elements as children (no props)"
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "The child is evaluated first"
                                                    (aget args 0) => "p"
                                                    "The missing parameters are mapped to empty js map"
                                                    (js->clj (aget args 1)) => {})
                                                  (real-mce args))
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "The parent is evaluated next"
                                                    (aget args 0) => "div"
                                                    "The missing params are mapped as an empty js map"
                                                    (js->clj (aget args 1)) => {})
                                                  (real-mce args))

         (div (p "Hello")))
       (provided "There are nested elements as children (keyword props)"
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "The child is evaluated first"
                                                    (aget args 0) => "p"
                                                    "The parameters are mapped to a js map"
                                                    (js->clj (aget args 1)) => {"className" "b"})
                                                  (real-mce args))
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "The parent is evaluated next"
                                                    (aget args 0) => "div"
                                                    "The params are mapped to a js map"
                                                    (js->clj (aget args 1)) => {"className" "a"})
                                                  (real-mce args))

         (div :.a (p :.b "Hello"))))))

#?(:cljs
   (specification "DOM elements are usable as functions"
     (assertions
       "The functions exist and are defined as functions"
       (fn? div) => true
       (fn? span) => true
       (fn? p) => true)
     (provided "It is used in a functional context"
       (dom/macro-create-element t args) => (assertions
                                              "The runtime version of the processing is called"
                                              t => "div")

       (apply div {} ["Hello"]))))

(specification "Interpretaion of :classes"
  (assertions
    "Converts keywords to strings"
    (cdom/classes->str [:.a :.b]) => "a b"
    "Allows strings"
    (cdom/classes->str [:.a "b"]) => "a b"
    "Allows expressions that short-circuit entries"
    (cdom/classes->str [:.a (when true "b")]) => "a b"
    (cdom/classes->str [:.a (when false "b")]) => "a"
    "Allows complex keywords"
    (cdom/classes->str [:.a.b]) => "a b"
    "Combines existing className in props with :classes (removing :classes)"
    (cdom/interpret-classes {:className "x y" :classes [:.a (when false "b") "c"]}) => {:className "x y a c"}))

(specification "Lazy Sequences of Children"
  (component "Macro expansion"
    (let [a (atom 0)
          _ (dom/div :.x {}
              (map (fn [_]
                     (swap! a inc)
                     (dom/div "Hi")) (range 0 5)))]
      (assertions
        "Forces children on element creation"
        @a => 5)))
  (component "Macro emitting function"
    (let [a (atom 0)
          _ (dom/div :.x (map (fn [_]
                                (swap! a inc)
                                (dom/div "Hi")) (range 0 5)))]
      (assertions
        "Forces children on element creation"
        @a => 5)))
  (component "Higher-order function"
    (let [a (atom 0)
          d dom/div
          _ (d :.x (map (fn [_]
                          (swap! a inc)
                          (dom/div "Hi")) (range 0 5)))]
      (assertions
        "Forces children on element creation"
        @a => 5))))

(comment
  (macroexpand-1 '(dom/div :.x (map (fn [_]
                                      (swap! a inc)
                                      (dom/div "Hi")) (range 0 5))
                    (dom/div "Hi")
                    (dom/div "Hi")
                    (dom/div "Hi"))))
