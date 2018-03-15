(ns fulcro.client.alpha.localized-dom-spec
  (:require
    [fulcro-spec.core :refer [specification assertions behavior provided when-mocking]]
    [fulcro.client.alpha.dom-server :refer [render-to-str]]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.alpha.localized-dom-server :as ldom :refer [div p span]]
    [clojure.string :as str])
  (:import (cljs.tagged_literals JSValue)))

(defmacro check-kw-processing [name component expected-classes]
  `(let [real-create# fulcro.client.alpha.dom/macro-create-element*]
     (provided ~name
       ~'(fulcro.client.alpha.dom/macro-create-element* args) ~'=1x=> (do
                                                                        (assertions
                                                                          "passes the tag name"
                                                                          (~'aget ~'args 0) ~'=> "div"
                                                                          "passes the props with the expected classes"
                                                                          (~'js->clj (~'aget ~'args 1)) ~'=> {"className" ~expected-classes
                                                                                                              "id"        "y"}
                                                                          "passes the children"
                                                                          (~'aget ~'args 2) ~'=> "Hello")
                                                                        (real-create# ~'args))

       (fulcro.client.alpha.dom/render-to-str ((fulcro.client.primitives/factory ~component) {})))))

(defsc NoPropsComponent [this props] (ldom/div :.a#y "Hello"))
(defsc NilPropsComponent [this props] (ldom/div :.a#y nil "Hello"))
(defsc EmptyPropsComponent [this props] (ldom/div :.a#y {} "Hello"))
(defsc EmptyJSPropsComponent [this props] (ldom/div :#y.a #js {} "Hello"))
(defsc CLJPropsComponent [this props] (ldom/div :.a#y {:className "x"} "Hello"))
(defsc CLJPropsWithIDComponent [this props] (ldom/div :.a#y {:id 1 :className "x"} "Hello"))
(defsc JSPropsWithIDComponent [this props] (ldom/div :.a#y #js {:id 1 :className "x"} "Hello"))
(defsc SymbolicClassPropComponent [this props] (let [x "x"] (ldom/div :.a#y #js {:id 1 :className x} "Hello")))
(defsc ExtendedCSSComponent [this props] (ldom/div :.a$b#y {:className "x"} "Hello"))
(defsc NoKWComponent [this props] (ldom/div #js {:id "y" :className "x"} "Hello"))
(defsc NoKWCLJComponent [this props] (ldom/div {:id "y" :className "x"} "Hello"))
(defsc DynamicClassesComponent [this props] (ldom/div {:id "y" :classes [:.a :$b]} "Hello"))
(defsc DynamicSymClassesComponent [this props] (let [classes [:.a :$b]] (ldom/div {:id "y" :classes classes} "Hello")))
(defsc DynamicSymPropsComponent [this props] (let [props {:id "y" :classes [:.a :$b]}] (ldom/div props "Hello")))
(defsc DynamicSymPropsWithNilEntryComponent [this props] (let [props {:id "y" :classes [:$b nil]}] (ldom/div props "Hello")))
(defsc DynamicSymPropsWithKWComponent [this props] (let [props {:id "y" :classes [:.a :$b]}] (ldom/div :$x.z props "Hello")))
(defsc SymbolicClassPropsComponent [this props] (let [props {:className "x"}] (ldom/div :.a#y props "Hello")))
(defsc SymbolicClassJSPropsComponent [this props] (let [props #js {:className "x"}] (ldom/div :.a#y props "Hello")))
(defsc SymbolicClassNilPropsComponent [this props] (let [props nil] (ldom/div :.a#y props "Hello")))

(defn jsvalue->map
  "Converts a data structure (recursively) that contains JSValues, replacing any JSValue with
  a map {:jsvalue val} where val is the original val in the JSValue."
  [v]
  (cond
    (instance? JSValue v) {:jsvalue (jsvalue->map (.-val v))}
    (map? v) (clojure.walk/prewalk (fn [n] (if (instance? JSValue n) (jsvalue->map n) n)) v)
    (vector? v) (mapv jsvalue->map v)
    (seq? v) (doall (map jsvalue->map v))
    :else v))

(specification "Server-side Rendering without parent context" :focused
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
    => "<div class=\"a b\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"1768960473\"><p data-reactid=\"2\">P</p><p class=\"x\" data-reactid=\"3\"><span data-reactid=\"4\">PS2</span></p></div>"))

(defn render-component [component]
  (render-to-str ((prim/factory component) {})))

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
    (render-component SymbolicClassNilPropsComponent) =fn=> #(str/includes? % " class=\"fulcro_client_alpha_localized-dom-spec_SymbolicClassNilPropsComponent__a\" id=\"y\" ")))
