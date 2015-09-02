(ns dom-tools.test-utils
  (:require [goog.dom :as gd]))


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
