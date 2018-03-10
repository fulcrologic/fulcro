(ns fulcro-css.dom-spec
  #?(:cljs (:require-macros fulcro-css.dom-spec))
  (:require
    [fulcro-spec.core :refer [specification assertions behavior provided when-mocking]]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro-css.dom :as dom :refer [div p span]])
  #?(:clj
     (:import (cljs.tagged_literals JSValue))))

#?(:clj
   (defmacro check-kw-processing [name component expected-classes]
     `(let [real-create# fulcro-css.dom/macro-create-element*]
        (provided ~name
          ~'(dom/macro-create-element* args) ~'=> (do
                                                    (assertions
                                                      "passes the tag name"
                                                      (~'aget ~'args 0) ~'=> "div"
                                                      "passes the props with the expected classes"
                                                      (~'js->clj (~'aget ~'args 1)) ~'=> {"className" ~expected-classes
                                                                                          "id"        "y"}
                                                      "passes the children"
                                                      (~'aget ~'args 2) ~'=> "Hello")
                                                    (real-create# ~'args))

          (fulcro-css.dom/render-to-str ((fulcro.client.primitives/factory ~component) {}))))))

(defsc NoPropsComponent [this props] (dom/div :.a#y "Hello"))
(defsc NilPropsComponent [this props] (dom/div :.a#y nil "Hello"))
(defsc EmptyPropsComponent [this props] (dom/div :.a#y {} "Hello"))
(defsc EmptyJSPropsComponent [this props] (dom/div :#y.a #js {} "Hello"))
(defsc CLJPropsComponent [this props] (dom/div :.a#y {:className "x"} "Hello"))
(defsc CLJPropsWithIDComponent [this props] (dom/div :.a#y {:id 1 :className "x"} "Hello"))
(defsc JSPropsWithIDComponent [this props] (dom/div :.a#y #js {:id 1 :className "x"} "Hello"))
(defsc SymbolicClassPropComponent [this props] (let [x "x"] (dom/div :.a#y #js {:id 1 :className x} "Hello")))
(defsc ExtendedCSSComponent [this props] (dom/div :.a$b#y {:className "x"} "Hello"))
(defsc NoKWComponent [this props] (dom/div #js {:id "y" :className "x"} "Hello"))
(defsc NoKWCLJComponent [this props] (dom/div {:id "y" :className "x"} "Hello"))
(defsc DynamicClassesComponent [this props] (dom/div {:id "y" :classes [:.a :$b]} "Hello"))
(defsc DynamicSymClassesComponent [this props] (let [classes [:.a :$b]] (dom/div {:id "y" :classes classes} "Hello")))
(defsc DynamicSymPropsComponent [this props] (let [props {:id "y" :classes [:.a :$b]}] (dom/div props "Hello")))
(defsc DynamicSymPropsWithKWComponent [this props] (let [props {:id "y" :classes [:.a :$b]}] (dom/div :$x.z props "Hello")))
(defsc SymbolicClassPropsComponent [this props] (let [props {:className "x"}] (dom/div :.a#y props "Hello")))
(defsc SymbolicClassJSPropsComponent [this props] (let [props #js {:className "x"}] (dom/div :.a#y props "Hello")))
(defsc SymbolicClassNilPropsComponent [this props] (let [props nil] (dom/div :.a#y props "Hello")))

;; NOTE: There are some pathological cases that I'm just not bothering to support. E.g. a #js {:fulcro-css.css/classes #js [:.a]} as props

#?(:cljs
   (specification "Contextual rendering with localized CSS" :focused
     (fulcro-css.dom-spec/check-kw-processing "It is passed a style kw and no props:" NoPropsComponent "fulcro-css_dom-spec_NoPropsComponent__a")
     (fulcro-css.dom-spec/check-kw-processing "It is passed a style kw and nil props:" NilPropsComponent "fulcro-css_dom-spec_NilPropsComponent__a")
     (fulcro-css.dom-spec/check-kw-processing "It is passed a style kw and empty cljs props:" EmptyPropsComponent "fulcro-css_dom-spec_EmptyPropsComponent__a")
     (fulcro-css.dom-spec/check-kw-processing "It is passed a style kw and empty js props:" EmptyJSPropsComponent "fulcro-css_dom-spec_EmptyJSPropsComponent__a")
     (fulcro-css.dom-spec/check-kw-processing "It is passed a style kw and cljs props with class:" CLJPropsComponent "fulcro-css_dom-spec_CLJPropsComponent__a x")
     (fulcro-css.dom-spec/check-kw-processing "It is passed a style kw and cljs props with class and ID:" CLJPropsWithIDComponent "fulcro-css_dom-spec_CLJPropsWithIDComponent__a x")
     (fulcro-css.dom-spec/check-kw-processing "It is passed a style kw and js props with class and ID:" JSPropsWithIDComponent "fulcro-css_dom-spec_JSPropsWithIDComponent__a x")
     (fulcro-css.dom-spec/check-kw-processing "It is passed a style kw and cljs props with symbolic class and ID:" SymbolicClassPropComponent "fulcro-css_dom-spec_SymbolicClassPropComponent__a x")
     (fulcro-css.dom-spec/check-kw-processing "It is passed a style kw and cljs binding for props" SymbolicClassPropsComponent "fulcro-css_dom-spec_SymbolicClassPropsComponent__a x")
     (fulcro-css.dom-spec/check-kw-processing "It is passed a style kw and js binding for props" SymbolicClassJSPropsComponent "fulcro-css_dom-spec_SymbolicClassJSPropsComponent__a x")
     (fulcro-css.dom-spec/check-kw-processing "It is passed a style kw and a nil binding for props" SymbolicClassNilPropsComponent "fulcro-css_dom-spec_SymbolicClassNilPropsComponent__a")
     (fulcro-css.dom-spec/check-kw-processing "It is passed a style kw with global marker:" ExtendedCSSComponent "fulcro-css_dom-spec_ExtendedCSSComponent__a b x")
     (fulcro-css.dom-spec/check-kw-processing "It is passed js props with class and ID:" NoKWComponent "x")
     (fulcro-css.dom-spec/check-kw-processing "It is passed cljjs props with class and ID:" NoKWCLJComponent "x")
     (fulcro-css.dom-spec/check-kw-processing "It is passed props with css/classes:" DynamicClassesComponent "fulcro-css_dom-spec_DynamicClassesComponent__a b")
     (fulcro-css.dom-spec/check-kw-processing "It is passed props with symbolic css/classes:" DynamicSymClassesComponent "fulcro-css_dom-spec_DynamicSymClassesComponent__a b")
     (fulcro-css.dom-spec/check-kw-processing "It is passed symbolic props that have css/classes:" DynamicSymPropsComponent "fulcro-css_dom-spec_DynamicSymPropsComponent__a b")
     (fulcro-css.dom-spec/check-kw-processing "It is passed symbolic props with css/classes and kw:" DynamicSymPropsWithKWComponent "fulcro-css_dom-spec_DynamicSymPropsWithKWComponent__z x fulcro-css_dom-spec_DynamicSymPropsWithKWComponent__a b")))

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
   (specification "Server-side Rendering without parent context" :focused
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
       => "<div class=\"a b\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"1768960473\"><p data-reactid=\"2\">P</p><p class=\"x\" data-reactid=\"3\"><span data-reactid=\"4\">PS2</span></p></div>")))

(defn render-component [component]
  (dom/render-to-str ((prim/factory component) {})))

#?(:clj
   (specification "SSR With Extended classes" :focused
     (assertions
       "kw + no props"
       (render-component NoPropsComponent) => "<div class=\"fulcro-css_dom-spec_NoPropsComponent__a\" id=\"y\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"211625318\">Hello</div>"
       "kw + nil props"
       (render-component NilPropsComponent) => "<div class=\"fulcro-css_dom-spec_NilPropsComponent__a\" id=\"y\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"911615436\">Hello</div>"
       "kw + empty cljs props"
       (render-component EmptyPropsComponent) => "<div class=\"fulcro-css_dom-spec_EmptyPropsComponent__a\" id=\"y\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-1809570120\">Hello</div>"
       "kw + empty js props"
       (render-component EmptyJSPropsComponent) => "<div class=\"fulcro-css_dom-spec_EmptyJSPropsComponent__a\" id=\"y\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-584505515\">Hello</div>"
       "kw + cljs props"
       (render-component CLJPropsComponent) => "<div class=\"fulcro-css_dom-spec_CLJPropsComponent__a x\" id=\"y\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"1774134810\">Hello</div>"
       "kw + cljs props + id override"
       (render-component CLJPropsWithIDComponent) => "<div id=\"y\" class=\"fulcro-css_dom-spec_CLJPropsWithIDComponent__a x\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"955459651\">Hello</div>"
       "kw + js props + id override"
       (render-component JSPropsWithIDComponent) => "<div id=\"y\" class=\"fulcro-css_dom-spec_JSPropsWithIDComponent__a x\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"423634951\">Hello</div>"
       "symbolic class name in props"
       (render-component SymbolicClassPropComponent) => "<div id=\"y\" class=\"fulcro-css_dom-spec_SymbolicClassPropComponent__a x\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-413128186\">Hello</div>"
       "global + localized kw + cljs props"
       (render-component ExtendedCSSComponent) => "<div class=\"fulcro-css_dom-spec_ExtendedCSSComponent__a b x\" id=\"y\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"866068425\">Hello</div>"
       "just js props"
       (render-component NoKWComponent) => "<div id=\"y\" class=\"x\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"17700452\">Hello</div>"
       "just cljs props"
       (render-component NoKWCLJComponent) => "<div id=\"y\" class=\"x\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"17700452\">Hello</div>"
       ":classes in props"
       (render-component DynamicClassesComponent) => "<div id=\"y\" class=\"fulcro-css_dom-spec_DynamicClassesComponent__a b\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"1451174058\">Hello</div>"
       ":classes as symbol in props"
       (render-component DynamicSymClassesComponent) => "<div id=\"y\" class=\"fulcro-css_dom-spec_DynamicSymClassesComponent__a b\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-553899549\">Hello</div>"
       ":classes in props as symbol"
       (render-component DynamicSymPropsComponent) => "<div id=\"y\" class=\"fulcro-css_dom-spec_DynamicSymPropsComponent__a b\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-1985861335\">Hello</div>"
       ":classes in props as symbol + kw"
       (render-component DynamicSymPropsWithKWComponent) => "<div id=\"y\" class=\"fulcro-css_dom-spec_DynamicSymPropsWithKWComponent__z x fulcro-css_dom-spec_DynamicSymPropsWithKWComponent__a b\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"500908337\">Hello</div>"
       "cljs props as symbol"
       (render-component SymbolicClassPropsComponent) => "<div class=\"fulcro-css_dom-spec_SymbolicClassPropsComponent__a x\" id=\"y\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"1194928761\">Hello</div>"
       "js props as symbol"
       (render-component SymbolicClassJSPropsComponent) => "<div class=\"fulcro-css_dom-spec_SymbolicClassJSPropsComponent__a x\" id=\"y\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-1748358378\">Hello</div>"
       "nil props as symbol"
       (render-component SymbolicClassNilPropsComponent) => "<div class=\"fulcro-css_dom-spec_SymbolicClassNilPropsComponent__a\" id=\"y\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"2100374276\">Hello</div>")))
