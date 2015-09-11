(ns untangled.test.dom
  (:require [clojure.string :as str]
            [goog.dom :as gd]))


(defn get-attribute
  "
  Get the value of an HTML element's named attribute.

  Parameters:
  `element` A rendered HTML element, as created with `render-as-dom`.
  `attribute` A keyword specifying the attribute you wish to query, i.e. `:value`.

  Returns the attribute value as a string.
  "
  [element attribute]
  (.getAttribute element (name attribute)))


(defn is-rendered-element?
  "Returns a boolean indicating whether the argument is an HTML element that has
  been rendered to the DOM."
  [obj]
  (let [is-dom-element? (gd/isElement obj)
        is-react-element? (js/React.isValidElement obj)
        is-rendered? (.hasOwnProperty obj "getDOMNode")]
    (or is-dom-element? (and is-react-element? is-rendered?))))


(defn render-as-dom
  "Creates a DOM element from a React component."
  [component]
  (.getDOMNode (js/React.addons.TestUtils.renderIntoDocument component)))


(defn node-contains-text?
  "Returns a boolean indicating whether `dom-node` node (or any of its children) contains `string`."
  [string dom-node]
  (if (or (nil? string) (nil? dom-node))
    false
    (let [regex (js/RegExp. string)
          text (gd/getTextContent dom-node)]
      (.test regex text))))


(defn find-element
  "
  Finds an HTML element inside a React component or HTML element based on keyword

  Parameters:
  *`element`: A rendered HTML element, as created with `render-as-dom`.
  *`keyword`: defines search type and can be one of:
    *`:key`: the :key hash of the React component, this will look for your `value` as a substring within a data-reactid
             attribute
    *`:{tagname}-text`: look for visible string (as a regex) on a specific tag. E.g. (find-element :button-text \"OK\" dom)
    *`:selector`: any arbitrary CSS selector
    * any attribute name passed as a keyword, i.e. :class will look for your `value` in the class attribute
  *`value`: a string used to find the element based on your :keyword search type

  Returns a rendered HTML element or nil if no match is found.
  "
  [element keyword value]
  (let [keyword-str (name keyword)]
    (cond
      (re-find #"-text$" keyword-str)
      (let [tagname (str/lower-case (re-find #"^\w+" keyword-str))]
        (gd/findNode element (fn [e] (and (node-contains-text? value e) (= tagname (str/lower-case (.-tagName e)))))))
      (= keyword :key) (.querySelector element (str/join ["[data-reactid$='$" value "'"]))
      (= keyword :class) (.querySelector element (str "." value))
      (= keyword :selector) (or (.querySelector element value) nil)
      :else (let [attr (name keyword)
                  selector (str/join ["[" attr "=" value "]"])]
              (or (.querySelector element selector) nil)))))

