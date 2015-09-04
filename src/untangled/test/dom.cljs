(ns untangled.test.dom
  (:require [clojure.string :as str]
            [goog.dom :as gd]))

;; def these long-named React functions to a convenient symbol, to make our other code more readable
(defn isDOMComponent [x] (js/React.addons.TestUtils.isDOMComponent x))
(defn renderIntoDocument [x] (js/React.addons.TestUtils.renderIntoDocument x))

(defn node-contains-text? [string dom-node]
  (if (or (nil? string) (nil? dom-node))
    false
    (let [regex (js/RegExp. string)
          text (gd/getTextContent dom-node)
          ]
      (.test regex text))))

(defn render-as-dom
  "Creates a DOM element from a React component."
  [component]
  (.getDOMNode (renderIntoDocument component)))

(defn find-element
  "
  Finds an HTML element inside a React component or HTML element based on keyword

  Parameters:
  *`keyword`: defines search type and can be one of:
    *`:key`: the :key hash of the React component, this will look for your `value` as a substring within a data-reactid
             attribute
    *`:{tagname}-text`: look for visible string (as a regex) on a specific tag. E.g. (find-element :button-text \"OK\" dom)
    *`:selector`: any arbitrary CSS selector
    * any attribute name passed as a keyword, i.e. :class will look for your `value` in the class attribute
  *`value`: a string used to find the element based on your :keyword search type
  *`obj`: should be either a React component or rendered HTML element. If a React component, it will first be rendered
  into a detached dom fragment.

  Returns a rendered HTML element or nil if no match is found.
  "
  [keyword value obj]
  (let [elem (if (gd/isElement obj) obj (render-as-dom obj))
        strkw (name keyword)]
    (cond
      (re-find #"-text$" strkw) (let [tagname (str/lower-case (re-find #"^\w+" strkw))]
                                  (gd/findNode elem (fn [e] (and (node-contains-text? value e) (= tagname (str/lower-case (.-tagName e))))))
                                           )
      (= keyword :key) (.querySelector elem (str/join ["[data-reactid$='$" value "'"]))
      (= keyword :class) (.querySelector elem (str "." value))
      (= keyword :selector) (or (.querySelector elem value) nil)
      :else (let [attr (name keyword)
                  selector (str/join ["[" attr "=" value "]"])]
              (or (.querySelector elem selector) nil)))))
