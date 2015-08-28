(ns dom-tools.query
  (:require [clojure.string :as str]
            [goog.dom :as gd]))

; TODO: this needs to take a react component
;(defn dom-frag [] (.getDOMNode (js/React.addons.TestUtils.renderIntoDocument (calendar))))

(defn is-button? [dom-node] (= "button" (str/lower-case (.-nodeName dom-node))))

(defn text-matches [regex dom-node] (.test regex (gd/getTextContent dom-node)))

(defn is-button-with-label? [string dom-node] (let [regex (js/RegExp. string)]
                                                (and (is-button? dom-node)
                                                     (text-matches regex dom-node))))

(defn find-button-with-label [string dom-node] (let [predicate (partial is-button-with-label? string)]
                                                 (gd/findNode dom-node predicate)))

(defn find-element [keyword value elem]
  (let [attr (name keyword)
        selector (str/join ["[" attr "=" value "]"])]
    (.querySelector elem selector)))

(defn get-dom-element [component]
  ; TODO: ask tony how to shorten calls to TestUtils...
  (let [element (js/React.addons.TestUtils.renderIntoDocument component)]
    (when (js/React.addons.TestUtils.isDOMComponent element) (.getDOMNode element))))

;(defn first-in-dom [search-kind pattern component]
;  nil)
;
;(first-in-dom :key "Today" component)
; => <dom-node>
