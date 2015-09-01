(ns dom-tools.query
  (:require [clojure.string :as str]
            [dom-tools.test-utils :as tu]
            [goog.dom :as gd]))

(defn is-button? [dom-node] (= "button" (str/lower-case (.-nodeName dom-node))))

;(defn is-button-with-label? [string dom-node] (let [regex (js/RegExp. string)]
;                                                (and (is-button? dom-node)
;                                                     (text-matches regex dom-node))))
;
;(defn find-button-with-label [string dom-node] (let [predicate (partial is-button-with-label? string)]
;                                                 (gd/findNode dom-node predicate)))

(defn get-dom-element [component]
  (.getDOMNode (tu/renderIntoDocument component)))

(defn find-element
  "
  Finds an HTML element inside a React component or HTML element based on keyword

  Parameters:
  *`keyword`: defines search type and can be one of:
    *`:key`: the :key hash of the React component, this will look for your `value` as a substring within a data-reactid
             attribute
    *`:text`: look for `value` as a substring of the user-visible text of an element
    *`:selector`: any arbitrary CSS selector
    * any attribute name passed as a keyword, i.e. :class will look for your `value` in the class attribute
  *`value`: a string used to find the element based on your :keyword search type
  *`obj`: should be either a React component or rendered HTML element

  Returns a rendered HTML element or nil if no match is found.
  "
  [keyword value obj]
  (let [elem (if (gd/isElement obj) obj (get-dom-element obj))]
    (cond
      (= keyword :key) (.querySelector elem (str/join ["[data-reactid$='$" value "'"]))
      (= keyword :text) (gd/findNode elem (partial tu/text-matches value elem))
      (= keyword :selector) (or (.querySelector elem value) nil)
      :else (let [attr (name keyword)
                  selector (str/join ["[" attr "=" value "]"])]
              (or (.querySelector elem selector) nil)))))
