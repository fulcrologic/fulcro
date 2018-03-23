(ns fulcro.client.dom
  (:refer-clojure :exclude [map mask meta time select])
  (:require-macros [fulcro.client.dom :as dom])
  (:require [cljsjs.react]
            [cljsjs.react.dom]
            fulcro.util
            [goog.object :as gobj]))

(defn- update-state
  "Updates the state of the wrapped input element."
  [component next-props value]
  (let [on-change  (gobj/getValueByKeys component "state" "onChange")
        next-state #js {}
        inputRef   (gobj/get next-props "inputRef")]
    (gobj/extend next-state next-props #js {:onChange on-change})
    (gobj/set next-state "value" value)
    (when inputRef
      (gobj/remove next-state "inputRef")
      (gobj/set next-state "ref" inputRef))
    (.setState component next-state)))

;; TODO: Was just about to test wrapped inputs on alpha dom via cards
;; Need to wrap wrappers to match the sigs...

(defn wrap-form-element [element]
  (let [ctor (fn [props]
               (this-as this
                 (set! (.-state this)
                   (let [state #js {:ref (gobj/get props "inputRef")}]
                     (->> #js {:onChange (goog/bind (gobj/get this "onChange") this)}
                       (gobj/extend state props))
                     (gobj/remove state "inputRef")
                     state))
                 (.apply js/React.Component this (js-arguments))))]
    (set! (.-displayName ctor) (str "wrapped-" element))
    (goog.inherits ctor js/React.Component)
    (specify! (.-prototype ctor)
      Object
      (onChange [this event]
        (when-let [handler (.-onChange (.-props this))]
          (handler event)
          (update-state
            this (.-props this)
            (gobj/getValueByKeys event "target" "value"))))

      (componentWillReceiveProps [this new-props]
        (let [state-value   (gobj/getValueByKeys this "state" "value")
              element-value (gobj/get (js/ReactDOM.findDOMNode this) "value")]
          (if (not= state-value element-value)
            (update-state this new-props element-value)
            (update-state this new-props (gobj/get new-props "value")))))

      (render [this]
        (js/React.createElement element (.-state this))))
    (let [real-factory (js/React.createFactory ctor)]
      (fn [props & children]
        (if-let [r (gobj/get props "ref")]
          (if (string? r)
            (apply real-factory props children)
            (let [p #js{}]
              (gobj/extend p props)
              (gobj/set p "inputRef" r)
              (gobj/remove p "ref")
              (real-factory p)))
          (apply real-factory props children))))))

(dom/gen-react-dom-fns)

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
