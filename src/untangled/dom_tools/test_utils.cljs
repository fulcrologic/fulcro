(ns untangled.dom-tools.test-utils
  (:require [goog.dom :as gd]
            [clojure.string :as str]))


;; def these long-named React functions to a convenient symbol, to make our other code more readable
(defn isDOMComponent [x] (js/React.addons.TestUtils.isDOMComponent x))
(defn renderIntoDocument [x] (js/React.addons.TestUtils.renderIntoDocument x))

(defn text-matches [string dom-node]
  (let [regex (js/RegExp. string)
        text (gd/getTextContent dom-node)]
    (.test regex text)))

(defn render-as-dom
  "Creates a DOM element from a React component."
  [component]
  (.getDOMNode (renderIntoDocument component)))

(defn hashmap-to-js-obj
  "Converts a clojure hashmap to a javascript object."
  [hashmap]
  (->> hashmap
       (map (fn [x] [(name (first x)) (last x)]))
       (into {})
       (clj->js)))
