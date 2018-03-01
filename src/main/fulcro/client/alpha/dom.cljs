(ns fulcro.client.alpha.dom
  (:refer-clojure :exclude [map mask meta time select])
  (:require-macros [fulcro.client.alpha.dom :as dom])
  (:require [cljsjs.react]
            [cljsjs.react.dom]
            [fulcro.client.util :as util]
            [goog.object :as gobj]))

(defn- update-state
  "Updates the state of the wrapped input element."
  [component next-props value]
  (let [on-change  (gobj/getValueByKeys component "state" "onChange")
        next-state #js {}]
    (gobj/extend next-state next-props #js {:onChange on-change})
    (gobj/set next-state "value" value)
    (.setState component next-state)))

(defn wrap-form-element [element]
  (let [ctor (fn [props]
               (this-as this
                 (set! (.-state this)
                   (let [state #js {}]
                     (->> #js {:onChange (goog/bind (gobj/get this "onChange") this)}
                       (gobj/extend state props))
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
          ;; On IE, onChange event might come after actual value of
          ;; an element have changed. We detect this and render
          ;; element as-is, hoping that next onChange will
          ;; eventually come and bring our modifications anyways.
          ;; Ignoring this causes skipped letters in controlled
          ;; components
          ;; https://github.com/facebook/react/issues/7027
          ;; https://github.com/reagent-project/reagent/issues/253
          ;; https://github.com/tonsky/rum/issues/86
          ;; TODO: Find a better solution, since this conflicts
          ;; with controlled/uncontrolled inputs.
          ;; https://github.com/r0man/sablono/issues/148
          (if (not= state-value element-value)
            (update-state this new-props element-value)
            (update-state this new-props (gobj/get new-props "value")))))

      (render [this]
        (js/React.createElement element (.-state this))))
    (js/React.createFactory ctor)))

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

;; fallback if the macro didn't do this
(defn macro-create-element [type args]
  (let [[head & tail] args]
    (cond
      (map? head)
      (macro-create-element*
        (doto #js [type (convert-props head)]
          (arr-append tail)))

      (nil? head)
      (macro-create-element*
        (doto #js [type nil]
          (arr-append tail)))

      (element? head)
      (macro-create-element*
        (doto #js [type nil]
          (arr-append args)))

      (object? head)
      (macro-create-element*
        (doto #js [type head]
          (arr-append tail)))

      :else
      (macro-create-element*
        (doto #js [type nil]
          (arr-append args))))))
