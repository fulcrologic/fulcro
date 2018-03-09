(ns fulcro-css.dom-spec
  #?(:cljs (:require-macros fulcro-css.dom-spec))
  (:require
    [fulcro-spec.core :refer [specification assertions behavior provided when-mocking]]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro-css.dom :as dom :refer [div p span]])
  #?(:clj
     (:import (cljs.tagged_literals JSValue))))

(defsc NoPropsComponent [this props]
  {:css [[:.a {:color :red}]]}
  (dom/div :.a "Hello"))

(defsc EmptyPropsComponent [this props]
  {:css [[:.a {:color :red}]]}
  (dom/div :.a {} "Hello"))

#?(:clj
   (defmacro check-classes [name component expected-classes]
     `(let [real-create# fulcro-css.dom/macro-create-element*]
        (provided ~name
          ~'(dom/macro-create-element* args) ~'=> (do
                                                    (assertions
                                                      "passes the tag name"
                                                      (~'aget ~'args 0) ~'=> "div"
                                                      "passes the props with the expected classes"
                                                      (~'js->clj (~'aget ~'args 1)) ~'=> {"className" ~expected-classes}
                                                      "passes the children"
                                                      (~'aget ~'args 2) ~'=> "Hello")
                                                    (real-create# ~'args))

          (fulcro-css.dom/render-to-str ((fulcro.client.primitives/factory ~component) {}))))))

#?(:cljs
   (specification "Contextual rendering with localized CSS" :focused
     (fulcro-css.dom-spec/check-classes "It is passed a style kw and no props:" NoPropsComponent "fulcro-css_dom-spec_NoPropsComponent__a")
     (fulcro-css.dom-spec/check-classes "It is passed a style kw and empty props:" EmptyPropsComponent "fulcro-css_dom-spec_EmptyPropsComponent__a")

     ))

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
   (specification "Macro processing" :focused
     (assertions
       "kw + NO props converts to a runtime js obj"
       (jsvalue->map (dom/emit-tag "div" true [:.a "Hello"]))
       => `(dom/macro-create-element*
             {:jsvalue ["div"
                        (fulcro-css.dom/combine nil :.a)
                        "Hello"]})
       "kw + nil props converts to a runtime js obj"
       (jsvalue->map (dom/emit-tag "div" true [:.a nil "Hello"]))
       => `(dom/macro-create-element*
             {:jsvalue ["div"
                        {:jsvalue {:className "a"}}
                        "Hello"]})
       "kw + CLJS data converts to a runtime js obj"
       (jsvalue->map (dom/emit-tag "div" true [:.a {:data-x 1} "Hello"]))
       => `(dom/macro-create-element*
             {:jsvalue ["div"
                        {:jsvalue {:data-x    1
                                   :className "a"}}
                        "Hello"]})
       "kw + CLJS data with symbols embeds runtime conversion on the symbols"
       (jsvalue->map (dom/emit-tag "div" true [:.a {:data-x 'some-var} "Hello"]))
       => `(dom/macro-create-element*
             {:jsvalue ["div"
                        (fulcro-css.dom/combine {:jsvalue {:data-x (cljs.core/clj->js ~'some-var)}} :.a)
                        "Hello"]})
       "kw + JS data emits a runtime combine operation on the JS data without embedded processing."
       (jsvalue->map (dom/emit-tag "div" true [:.a (JSValue. {:data-x 'some-var}) "Hello"]))
       => `(dom/macro-create-element*
             {:jsvalue ["div"
                        (fulcro-css.dom/combine {:jsvalue {:data-x ~'some-var}} :.a)
                        "Hello"]})
       "Plain JS maps are passed through as props"
       (jsvalue->map (dom/emit-tag "div" true [(JSValue. {:data-x 1}) "Hello"]))
       => `(dom/macro-create-element* {:jsvalue ["div"
                                                 {:jsvalue {:data-x 1}}
                                                 "Hello"]})
       "kw + symbol emits runtime conversion"
       (jsvalue->map (dom/emit-tag "div" true [:.a 'props "Hello"]))
       => `(dom/macro-create-element "div" [~'props "Hello"] :.a)

       "embedded code in props is passed through"
       (jsvalue->map (dom/emit-tag "div" true [:.a '{:onClick (fn [] (do-it))} "Hello"]))
       => `(dom/macro-create-element* {:jsvalue ["div" (fulcro-css.dom/combine {:jsvalue {:onClick (~'fn [] (~'do-it))}} :.a) "Hello"]}))))

(comment
  #?(:cljs
     (specification "DOM Tag Functions (CLJS)" :focused
       (provided "It is passed no arguments it:"
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
     (specification "Server-side Rendering" :focused
       (assertions
         "Simple tag rendering"
         (dom/render-to-str (div {} "Hello"))
         => "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-880209586\">Hello</div>"
         "Rendering with missing props"
         (dom/render-to-str (div "Hello"))
         => "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-880209586\">Hello</div>"
         "Rendering with kw props"
         (dom/render-to-str (div :.a#1 "Hello"))
         => "<div class=\"a\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-244181499\">Hello</div>"
         "Rendering with kw and props map"
         (dom/render-to-str (div :.a#1 {:className "b"} "Hello"))
         => "<div class=\"a b\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"385685127\">Hello</div>"
         "Nested rendering"
         (dom/render-to-str (div :.a#1 {:className "b"}
                              (p "P")
                              (p :.x (span "PS2"))))
         => "<div class=\"a b\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"1768960473\"><p data-reactid=\"2\">P</p><p class=\"x\" data-reactid=\"3\"><span data-reactid=\"4\">PS2</span></p></div>"))))
