(ns untangled.test.assertions
  (:require [goog.dom :as gd]
            [cljs.test :refer-macros [is]]
            [clojure.string :as str]))

(defn text-matches 
  "A test assertion (like is) that checks that the text on a DOM node matches the given string 
  (which is treated as a regex pattern)."
  [string dom-node]
  (is (not (nil? string)) "STRING IS NIL")
  (is (not (nil? dom-node)) "DOM NODE IS NIL")
  (if (and string dom-node)
    (let [regex (js/RegExp. string)
          text (gd/getTextContent dom-node)
          ]
      (is (.test regex text) (str text " matches " string)))))
