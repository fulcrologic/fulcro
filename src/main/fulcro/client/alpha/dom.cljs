(ns fulcro.client.alpha.dom
  (:refer-clojure :exclude [map mask meta time select])
  (:require-macros [fulcro.client.alpha.dom :as dom])
  (:require [cljsjs.react]
            [cljsjs.react.dom]
            [fulcro.client.util :as util]
            [goog.object :as gobj]))

;; (dom/gen-react-dom-fns)

(defn render
  "Equivalent to React.render"
  [component el]
  (js/ReactDOM.render component el))

(defn render-to-str
  "Equivalent to React.renderToString"
  [c]
  (js/ReactDOMServer.renderToString c))

(defn node
  "Returns the dom node associated with a component's React ref."
  ([component]
   (js/ReactDOM.findDOMNode component))
  ([component name]
   (some-> (.-refs component) (gobj/get name) (js/ReactDOM.findDOMNode))))

(defn create-element
  "Create a DOM element for which there exists no corresponding function.
   Useful to create DOM elements not included in React.DOM. Equivalent
   to calling `js/React.createElement`"
  ([tag]
   (create-element tag nil))
  ([tag opts]
   (js/React.createElement tag opts))
  ([tag opts & children]
   (js/React.createElement tag opts children)))

;;; Dom Macros helpers
;;; Copied from Thomas Heller's work
;;; https://github.com/thheller/shadow

(def ^{:private true} element-marker
  (-> (js/React.createElement "div" nil)
      (gobj/get "$$typeof")))

(defn element? [x]
  (and (object? x)
       (= element-marker (gobj/get x "$$typeof"))))

(defn convert-props [props]
  (cond
    (nil? props)
    #js {}
    (map? props)
    (clj->js props)
    :else
    props))

;; called from macro
;; react v16 is really picky, the old direct .children prop trick no longer works
(defn macro-create-element* [arr]
  {:pre [(array? arr)]}
  (.apply js/React.createElement nil arr))

(defn arr-append* [arr x]
  (.push arr x)
  arr)

(defn arr-append [arr tail]
  (reduce arr-append* arr tail))

(defn add-css [attr-map {:keys [id className]}]
  (let [classes-in-map (or (:class attr-map)
                           (:className attr-map))
        id-in-map      (:id attr-map)]
    (assoc attr-map
           :className (str classes-in-map " " className)
           :id (or id id-in-map))))

;; fallback if the macro didn't do this
(defn macro-create-element
  ([type args] (macro-create-element type args {}))
  ([type args {:keys [id className] :as css}]
   (let [[head & tail] args]
     (cond
       (map? head)
       (macro-create-element*
        (doto #js [type (clj->js (add-css head css))]
          (arr-append tail)))

       (nil? head)
       (macro-create-element*
        (doto #js [type #js {:id id :className className}]
          (arr-append tail)))

       (element? head)
       (macro-create-element*
        (doto #js [type #js {:id id :className className}]
          (arr-append args)))

       (object? head)
       (macro-create-element*
        (doto #js [type head]
          (arr-append tail)))

       :else
       (macro-create-element*
        (doto #js [type #js {:id id :className className}]
          (arr-append args)))))))
