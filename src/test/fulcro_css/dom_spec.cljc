(ns fulcro-css.dom-spec
  (:require
    [fulcro-spec.core :refer [specification assertions behavior]]
    [fulcro-css.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [garden.selectors :as sel])
  #?(:clj
     (:import (cljs.tagged_literals JSValue))))

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
