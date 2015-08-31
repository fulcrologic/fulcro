(ns dom-tools.query
  (:require [clojure.string :as str]
            [goog.dom :as gd]))

; TODO: this needs to take a react component
;(defn dom-frag [] (.getDOMNode (js/React.addons.TestUtils.renderIntoDocument (calendar))))

(defn is-button? [dom-node] (= "button" (str/lower-case (.-nodeName dom-node))))

(defn text-matches [string dom-node]
  (let [regex (js/RegExp. string)
        text (gd/getTextContent dom-node)]
    (.test regex text)))

;(defn is-button-with-label? [string dom-node] (let [regex (js/RegExp. string)]
;                                                (and (is-button? dom-node)
;                                                     (text-matches regex dom-node))))
;
;(defn find-button-with-label [string dom-node] (let [predicate (partial is-button-with-label? string)]
;                                                 (gd/findNode dom-node predicate)))


(defn get-dom-element [component]
  ; TODO: ask tony how to shorten calls to TestUtils...
  (let [element (js/React.addons.TestUtils.renderIntoDocument component)]
    (when (js/React.addons.TestUtils.isDOMComponent element) (.getDOMNode element))))

(defn find-element
  "
  Finds an HTML element inside a React component or HTML element based on keyword

  Parameters:
  *`keyword`: defines search type and can be one of:
    *`:key`: the :key hash of the React component
    *`:text`: the user-visible text of an element
    *`:selector`: any arbitrary CSS selector
    * any attribute name passed as a keyword, i.e. :class
  *`value`: a string used to find the element based on your :keyword search type
  *`obj`: should be either a React component or rendered HTML element

  Returns a rendered HTML element or nil if no match is found.
  "
  [keyword value obj]
  (let [elem (if (gd/isElement obj) obj (get-dom-element obj))]
    (cond
      (= keyword :key) (.querySelector elem (str/join ["[data-reactid$='$" value "'"]))
      (= keyword :text) (gd/findNode elem (partial text-matches value elem))
      (= keyword :selector) (or (.querySelector elem value) nil)
      :else (let [attr (name keyword)
                  selector (str/join ["[" attr "=" value "]"])]
              (or (.querySelector elem selector) nil)))))


;(defn first-in-dom [search-kind pattern component]
;  nil)
;
;(first-in-dom :key "Today" component)
; => <dom-node>
