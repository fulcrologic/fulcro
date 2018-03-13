(ns fulcro.client.alpha.localized-dom-spec
  #?(:cljs (:require-macros fulcro.client.alpha.localized-dom-spec))
  (:require
    [fulcro-spec.core :refer [specification assertions behavior provided when-mocking]]
    [fulcro.client.alpha.dom :as adom]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.alpha.localized-dom :as dom :refer [div p span]]
    [clojure.string :as str])
  #?(:clj
     (:import (cljs.tagged_literals JSValue))))

#?(:clj
   (defmacro check-kw-processing [name component expected-classes]
     `(let [real-create# adom/macro-create-element*]
        (provided ~name
          ~'(adom/macro-create-element* args) ~'=1x=> (do
                                                        (assertions
                                                          "passes the tag name"
                                                          (~'aget ~'args 0) ~'=> "div"
                                                          "passes the props with the expected classes"
                                                          (~'js->clj (~'aget ~'args 1)) ~'=> {"className" ~expected-classes
                                                                                              "id"        "y"}
                                                          "passes the children"
                                                          (~'aget ~'args 2) ~'=> "Hello")
                                                        (real-create# ~'args))

          (adom/render-to-str ((fulcro.client.primitives/factory ~component) {}))))))

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
(defsc DynamicSymPropsWithNilEntryComponent [this props] (let [props {:id "y" :classes [:$b nil]}] (dom/div props "Hello")))
(defsc DynamicSymPropsWithKWComponent [this props] (let [props {:id "y" :classes [:.a :$b]}] (dom/div :$x.z props "Hello")))
(defsc SymbolicClassPropsComponent [this props] (let [props {:className "x"}] (dom/div :.a#y props "Hello")))
(defsc SymbolicClassJSPropsComponent [this props] (let [props #js {:className "x"}] (dom/div :.a#y props "Hello")))
(defsc SymbolicClassNilPropsComponent [this props] (let [props nil] (dom/div :.a#y props "Hello")))

;; NOTE: There are some pathological cases that I'm just not bothering to support. E.g. a #js {:fulcro.client.css/classes #js [:.a]} as props

#?(:cljs
   (specification "Contextual rendering with localized CSS" :focused
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed a style kw and no props:" NoPropsComponent "fulcro_client_alpha_localized-dom-spec_NoPropsComponent__a")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed a style kw and nil props:" NilPropsComponent "fulcro_client_alpha_localized-dom-spec_NilPropsComponent__a")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed a style kw and empty cljs props:" EmptyPropsComponent "fulcro_client_alpha_localized-dom-spec_EmptyPropsComponent__a")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed a style kw and empty js props:" EmptyJSPropsComponent "fulcro_client_alpha_localized-dom-spec_EmptyJSPropsComponent__a")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed a style kw and cljs props with class:" CLJPropsComponent "fulcro_client_alpha_localized-dom-spec_CLJPropsComponent__a x")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed a style kw and cljs props with class and ID:" CLJPropsWithIDComponent "fulcro_client_alpha_localized-dom-spec_CLJPropsWithIDComponent__a x")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed a style kw and js props with class and ID:" JSPropsWithIDComponent "fulcro_client_alpha_localized-dom-spec_JSPropsWithIDComponent__a x")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed a style kw and cljs props with symbolic class and ID:" SymbolicClassPropComponent "fulcro_client_alpha_localized-dom-spec_SymbolicClassPropComponent__a x")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed a style kw and cljs binding for props" SymbolicClassPropsComponent "fulcro_client_alpha_localized-dom-spec_SymbolicClassPropsComponent__a x")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed a style kw and js binding for props" SymbolicClassJSPropsComponent "fulcro_client_alpha_localized-dom-spec_SymbolicClassJSPropsComponent__a x")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed a style kw and a nil binding for props" SymbolicClassNilPropsComponent "fulcro_client_alpha_localized-dom-spec_SymbolicClassNilPropsComponent__a")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed a style kw with global marker:" ExtendedCSSComponent "fulcro_client_alpha_localized-dom-spec_ExtendedCSSComponent__a b x")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed js props with class and ID:" NoKWComponent "x")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed cljjs props with class and ID:" NoKWCLJComponent "x")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed props with css/classes:" DynamicClassesComponent "fulcro_client_alpha_localized-dom-spec_DynamicClassesComponent__a b")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed props with symbolic css/classes:" DynamicSymClassesComponent "fulcro_client_alpha_localized-dom-spec_DynamicSymClassesComponent__a b")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed symbolic props that have css/classes:" DynamicSymPropsComponent "fulcro_client_alpha_localized-dom-spec_DynamicSymPropsComponent__a b")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed symbolic props that have css/classes:" DynamicSymPropsWithNilEntryComponent "b ")
     (fulcro.client.alpha.localized-dom-spec/check-kw-processing "It is passed symbolic props with css/classes and kw:" DynamicSymPropsWithKWComponent "fulcro_client_alpha_localized-dom-spec_DynamicSymPropsWithKWComponent__z x fulcro_client_alpha_localized-dom-spec_DynamicSymPropsWithKWComponent__a b")))

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
       (render-component NoPropsComponent) =fn=> #(str/includes? % "class=\"fulcro_client_alpha_localized-dom-spec_NoPropsComponent__a\" id=\"y\"") "kw + nil props"
       (render-component NilPropsComponent) =fn=> #(str/includes? % "class=\"fulcro_client_alpha_localized-dom-spec_NilPropsComponent__a\" id=\"y\"")
       "kw + empty cljs props"
       (render-component EmptyPropsComponent) =fn=> #(str/includes? % "class=\"fulcro_client_alpha_localized-dom-spec_EmptyPropsComponent__a\" id=\"y\"")
       "kw + empty js props"
       (render-component EmptyJSPropsComponent) =fn=> #(str/includes? % " class=\"fulcro_client_alpha_localized-dom-spec_EmptyJSPropsComponent__a\" id=\"y\"")
       "kw + cljs props"
       (render-component CLJPropsComponent) =fn=> #(str/includes? % " class=\"fulcro_client_alpha_localized-dom-spec_CLJPropsComponent__a x\" id=\"y\"")
       "kw + cljs props + id override"
       (render-component CLJPropsWithIDComponent) =fn=> #(str/includes? % " id=\"y\" class=\"fulcro_client_alpha_localized-dom-spec_CLJPropsWithIDComponent__a x\"")
       "kw + js props + id override"
       (render-component JSPropsWithIDComponent) =fn=> #(str/includes? % " id=\"y\" class=\"fulcro_client_alpha_localized-dom-spec_JSPropsWithIDComponent__a x\" ")
       "symbolic class name in props"
       (render-component SymbolicClassPropComponent) =fn=> #(str/includes? % " id=\"y\" class=\"fulcro_client_alpha_localized-dom-spec_SymbolicClassPropComponent__a x\"")
       "global + localized kw + cljs props"
       (render-component ExtendedCSSComponent) =fn=> #(str/includes? % " class=\"fulcro_client_alpha_localized-dom-spec_ExtendedCSSComponent__a b x\" id=\"y\"")
       "just js props"
       (render-component NoKWComponent) =fn=> #(str/includes? % " id=\"y\" class=\"x\" ")
       "just cljs props"
       (render-component NoKWCLJComponent) =fn=> #(str/includes? % " id=\"y\" class=\"x\"")
       ":classes in props"
       (render-component DynamicClassesComponent) =fn=> #(str/includes? % " id=\"y\" class=\"fulcro_client_alpha_localized-dom-spec_DynamicClassesComponent__a b\" ")
       ":classes as symbol in props"
       (render-component DynamicSymClassesComponent) =fn=> #(str/includes? % " id=\"y\" class=\"fulcro_client_alpha_localized-dom-spec_DynamicSymClassesComponent__a b\"")
       ":classes in props as symbol"
       (render-component DynamicSymPropsComponent) =fn=> #(str/includes? % " id=\"y\" class=\"fulcro_client_alpha_localized-dom-spec_DynamicSymPropsComponent__a b\" ")
       ":classes in props as symbol + kw"
       (render-component DynamicSymPropsWithKWComponent) =fn=> #(str/includes? % " id=\"y\" class=\"fulcro_client_alpha_localized-dom-spec_DynamicSymPropsWithKWComponent__z x fulcro_client_alpha_localized-dom-spec_DynamicSymPropsWithKWComponent__a b\" ")
       "cljs props as symbol"
       (render-component SymbolicClassPropsComponent) =fn=> #(str/includes? % " class=\"fulcro_client_alpha_localized-dom-spec_SymbolicClassPropsComponent__a x\" id=\"y\"")
       "js props as symbol"
       (render-component SymbolicClassJSPropsComponent) =fn=> #(str/includes? % " class=\"fulcro_client_alpha_localized-dom-spec_SymbolicClassJSPropsComponent__a x\" id=\"y\"")
       "nil props as symbol"
       (render-component SymbolicClassNilPropsComponent) =fn=> #(str/includes? % " class=\"fulcro_client_alpha_localized-dom-spec_SymbolicClassNilPropsComponent__a\" id=\"y\" "))))
