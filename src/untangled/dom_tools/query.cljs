(ns untangled.dom-tools.query
  (:require [clojure.string :as str]
            [untangled.dom-tools.test-utils :as tu]
            [goog.dom :as gd]))

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
  (let [elem (if (gd/isElement obj) obj (tu/render-as-dom obj))]
    (cond
      (= keyword :key) (.querySelector elem (str/join ["[data-reactid$='$" value "'"]))
      (= keyword :text) (gd/findNode elem (partial tu/text-matches value elem))
      (= keyword :selector) (or (.querySelector elem value) nil)
      :else (let [attr (name keyword)
                  selector (str/join ["[" attr "=" value "]"])]
              (or (.querySelector elem selector) nil)))))
