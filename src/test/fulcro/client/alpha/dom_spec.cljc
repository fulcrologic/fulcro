(ns fulcro.client.alpha.dom-spec
  (:require
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [fulcro.client.dom :refer [render-to-str]]
    [fulcro.client.alpha.dom :as dom :refer [div p span]]
    [fulcro.client.dom :as old-dom])
  #?(:clj
     (:import (cljs.tagged_literals JSValue))))

(specification "Conversion of keywords to CSS IDs and Classes"
  (assertions
    "classnames are given as a vector"
    (#'dom/parse :.a) => {:classes ["a"]}
    (#'dom/parse :.a.b) => {:classes ["a" "b"]}
    (#'dom/parse :.a.b.hello-world) => {:classes ["a" "b" "hello-world"]}
    "converts class and ID combos"
    (#'dom/parse :.a#j) => {:id "j" :classes ["a"]}
    "order doesn't matter"
    (#'dom/parse :#j.a) => {:id "j" :classes ["a"]}
    "multiple classes are allowed"
    (#'dom/parse :#j.a.b) => {:id "j" :classes ["a" "b"]}
    (#'dom/parse :.a#j.b) => {:id "j" :classes ["a" "b"]}
    "throws an exception for invalid keywords"
    (#'dom/parse :a) =throws=> {:regex #"Invalid style"}
    (#'dom/parse :.a#.j) =throws=> {:regex #"Invalid style"}))

(specification "Combining keywords on CLJ(s) property maps"
  (let [props         {:className "c1"}
        props-with-id {:id 1 :className "c1"}]
    (assertions
      "adds the given keyword classes to any existing props"
      (dom/add-kwprops-to-props props :.a) => {:className "a c1"}
      "leaves an existing id when kw does not have an ID"
      (dom/add-kwprops-to-props props-with-id :.a) => {:id 1 :className "a c1"}
      "overrides existing id when kw has an ID"
      (dom/add-kwprops-to-props props-with-id :.a#2) => {:id "2" :className "a c1"})
    ;; Need to run these because the cljs version of these emit JS maps
    #?(:clj
       (component "On the server:"
         (assertions
           "a nil props and nil kw results in an empty js map"
           (dom/add-kwprops-to-props nil nil) => {}
           "a nil props and real kw results in js props"
           (dom/add-kwprops-to-props nil :.a.b#2) => {:className "a b"
                                                      :id        "2"}
           "a kw with multiple classes combines properly"
           (dom/add-kwprops-to-props props :.a.some-class.other-class) => {:className "a some-class other-class c1"})))))

#?(:cljs
   (specification "Combining keywords on JS property maps"
     (let [js-props         #js {:className "c1"}
           js-props-with-id #js {:id 1 :className "c1"}]
       (assertions
         "maintains the result as a js-object"
         (object? (dom/add-kwprops-to-props js-props :.a)) => true
         "adds the given keyword classes to any existing props"
         (js->clj (dom/add-kwprops-to-props js-props :.a)) => {"className" "a c1"}
         "leaves existing id in place when kw does not have an ID"
         (js->clj (dom/add-kwprops-to-props js-props-with-id :.a)) => {"id"        1
                                                                       "className" "a c1"}
         "overrides existing id when kw has an ID"
         (js->clj (dom/add-kwprops-to-props js-props-with-id :.a#2)) => {"id"        "2"
                                                                         "className" "a c1"}
         "a nil kw leaves classes alone"
         (js->clj (dom/add-kwprops-to-props js-props-with-id nil)) => {"id"        1
                                                                       "className" "c1"}
         "a nil props and nil kw results in an empty js map"
         (object? (dom/add-kwprops-to-props nil nil)) => true
         (js->clj (dom/add-kwprops-to-props nil nil)) => {}
         "a nil props and real kw results in js props"
         (object? (dom/add-kwprops-to-props nil :.a.b#2)) => true
         (js->clj (dom/add-kwprops-to-props nil :.a.b#2)) => {"className" "a b"
                                                              "id"        "2"}
         "a kw with multiple classes combines properly"
         (js->clj (dom/add-kwprops-to-props js-props :.a.some-class.other-class)) => {"className" "a some-class other-class c1"}))))

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
       (jsvalue->map (#'dom/emit-tag "div" true [:.a nil "Hello"]))
       => `(dom/macro-create-element*
             {:jsvalue ["div"
                        {:jsvalue {:className "a"}}
                        "Hello"]})
       "kw + CLJS data converts to a runtime js obj"
       (jsvalue->map (#'dom/emit-tag "div" true [:.a {:data-x 1} "Hello"]))
       => `(dom/macro-create-element*
             {:jsvalue ["div"
                        {:jsvalue {:data-x    1
                                   :className "a"}}
                        "Hello"]})
       "kw + CLJS data with symbols embeds runtime conversion on the symbols"
       (jsvalue->map (#'dom/emit-tag "div" true [:.a {:data-x 'some-var} "Hello"]))
       => `(dom/macro-create-element*
             {:jsvalue ["div"
                        (fulcro.client.alpha.dom/add-kwprops-to-props {:jsvalue {:data-x (cljs.core/clj->js ~'some-var)}} :.a)
                        "Hello"]})
       "kw + JS data emits a runtime combine operation on the JS data without embedded processing."
       (jsvalue->map (#'dom/emit-tag "div" true [:.a (JSValue. {:data-x 'some-var}) "Hello"]))
       => `(dom/macro-create-element*
             {:jsvalue ["div"
                        (fulcro.client.alpha.dom/add-kwprops-to-props {:jsvalue {:data-x ~'some-var}} :.a)
                        "Hello"]})
       "Plain JS maps are passed through as props"
       (jsvalue->map (#'dom/emit-tag "div" true [(JSValue. {:data-x 1}) "Hello"]))
       => `(dom/macro-create-element* {:jsvalue ["div"
                                                 {:jsvalue {:data-x 1}}
                                                 "Hello"]})
       "kw + symbol emits runtime conversion"
       (jsvalue->map (#'dom/emit-tag "div" true [:.a 'props "Hello"]))
       => `(dom/macro-create-element "div" [~'props "Hello"] :.a)

       "embedded code in props is passed through"
       (jsvalue->map (#'dom/emit-tag "div" true [:.a '{:onClick (fn [] (do-it))} "Hello"]))
       => `(dom/macro-create-element* {:jsvalue ["div" (fulcro.client.alpha.dom/add-kwprops-to-props {:jsvalue {:onClick (~'fn [] (~'do-it))}} :.a) "Hello"]}))))

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


     (provided "There are nested elements as children (no props)"
       (dom/macro-create-element* args) =1x=> (do
                                                (assertions
                                                  "The child is evaluated first"
                                                  (aget args 0) => "p"
                                                  "The missing parameters are mapped to empty js map"
                                                  (js->clj (aget args 1)) => {}))
       (dom/macro-create-element* args) =1x=> (do
                                                (assertions
                                                  "The parent is evaluated next"
                                                  (aget args 0) => "div"
                                                  "The missing params are mapped as an empty js map"
                                                  (js->clj (aget args 1)) => {}))

       (div (p "Hello")))
     (provided "There are nested elements as children (keyword props)"
       (dom/macro-create-element* args) =1x=> (do
                                                (assertions
                                                  "The child is evaluated first"
                                                  (aget args 0) => "p"
                                                  "The parameters are mapped to a js map"
                                                  (js->clj (aget args 1)) => {"className" "b"}))
       (dom/macro-create-element* args) =1x=> (do
                                                (assertions
                                                  "The parent is evaluated next"
                                                  (aget args 0) => "div"
                                                  "The params are mapped to a js map"
                                                  (js->clj (aget args 1)) => {"className" "a"}))

       (div :.a (p :.b "Hello")))))

#?(:clj
   (specification "Server-side Rendering"
     (assertions
       "Simple tag rendering"
       (render-to-str (div {} "Hello"))
       => "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-880209586\">Hello</div>"
       "Rendering with missing props"
       (render-to-str (div "Hello"))
       => "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-880209586\">Hello</div>"
       "Rendering with kw props"
       (render-to-str (div :.a#1 "Hello"))
       => "<div class=\"a\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-244181499\">Hello</div>"
       "Rendering with kw and props map"
       (render-to-str (div :.a#1 {:className "b"} "Hello"))
       => "<div class=\"a b\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"385685127\">Hello</div>"
       "Nested rendering"
       (render-to-str (div :.a#1 {:className "b"}
                        (p "P")
                        (p :.x (span "PS2"))))
       => "<div class=\"a b\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"1768960473\"><p data-reactid=\"2\">P</p><p class=\"x\" data-reactid=\"3\"><span data-reactid=\"4\">PS2</span></p></div>")))

(specification "DOM elements are usable as functions"
  #?(:clj
     (provided "The correct SSR function is called"
       (old-dom/element opts) => (assertions
                                   (:tag opts) => 'div)

       (apply div {} ["Hello"]))
     :cljs
     (provided ""
       (dom/macro-create-element t args) => (assertions
                                              t => "div")

       (apply div {} ["Hello"]))))
