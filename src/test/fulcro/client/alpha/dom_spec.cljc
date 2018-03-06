(ns fulcro.client.alpha.dom-spec
  (:require
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [fulcro.client.alpha.dom :as dom]))

#?(:cljs
   (let [real-macro-create-element dom/macro-create-element*]
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

         (dom/div "Hello"))
       (provided "It is passed a CLJ map:"
         (dom/macro-create-element* args) => (do
                                               (assertions
                                                 "passes the map in JS"
                                                 (js->clj (aget args 1)) => {"className" "a"}))

         (dom/div {:className "a"} "Hello"))

       (provided "It is passed a JS map:"
         (dom/macro-create-element* args) => (do
                                               (assertions
                                                 "passes the map in JS"
                                                 (js->clj (aget args 1)) => {"className" "a"}))

         (dom/div #js {:className "a"} "Hello"))

       (provided "It is passed nil params:"
         (dom/macro-create-element* args) => (do
                                               (assertions
                                                 "passes an empty JS map"
                                                 (js->clj (aget args 1)) => {}))

         (dom/div nil "Hello"))

       (provided "It is passed ONLY a class kw:"
         (dom/macro-create-element* args) => (do
                                               (assertions
                                                 "then only the class is set"
                                                 (js->clj (aget args 1)) => {"className" "a"}))

         (dom/div :.a "Hello"))

       (provided "It is passed an id/class kw:"
         (dom/macro-create-element* args) => (do
                                               (assertions
                                                 "passes the map in JS"
                                                 (js->clj (aget args 1)) => {"id"        "j"
                                                                             "className" "a"}))

         (dom/div :.a#j "Hello"))
       (provided "It is passed an id/class kw AND CLJ properties:"
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "merges the classes. The ID from the keyword overrides the ID"
                                                    (js->clj (aget args 1)) => {"id"        "j"
                                                                                "className" "b a c e"}))
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "order doesn't matter"
                                                    (js->clj (aget args 1)) => {"id"        "j"
                                                                                "className" "b a c e"}))
         (dom/macro-create-element* args) =1x=> (do
                                                  (assertions
                                                    "order doesn't matter"
                                                    (js->clj (aget args 1)) => {"id"        "j"
                                                                                "className" "b a c e"}))

         (dom/div :.a.c.e#j {:id 1 :className "b"} "Hello")
         (dom/div :#j.a.c.e {:id 1 :className "b"} "Hello")
         (dom/div :.a#j.c.e {:id 1 :className "b"} "Hello"))

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

         (dom/div (dom/p "Hello")))
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

         (dom/div :.a (dom/p :.b "Hello"))))))
