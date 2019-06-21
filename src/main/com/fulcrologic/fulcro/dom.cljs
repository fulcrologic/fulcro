(ns com.fulcrologic.fulcro.dom
  "Client-side DOM macros and functions. For isomorphic (server) support, see also com.fulcrologic.fulcro.dom-server"
  (:refer-clojure :exclude [map meta time mask select use set symbol filter])
  (:require-macros [com.fulcrologic.fulcro.dom])
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.misc :as util]
    [cljsjs.react]
    [cljsjs.react.dom]
    [goog.object :as gobj]
    [goog.dom :as gdom]
    [com.fulcrologic.fulcro.dom-common :as cdom]
    [taoensso.timbre :as log]))

(declare a abbr address altGlyph altGlyphDef altGlyphItem animate animateColor animateMotion animateTransform area
  article aside audio b base bdi bdo big blockquote body br button canvas caption circle cite clipPath code
  col colgroup color-profile cursor data datalist dd defs del desc details dfn dialog discard div dl dt
  ellipse em embed feBlend feColorMatrix feComponentTransfer feComposite feConvolveMatrix feDiffuseLighting
  feDisplacementMap feDistantLight feDropShadow feFlood feFuncA feFuncB feFuncG feFuncR feGaussianBlur
  feImage feMerge feMergeNode feMorphology feOffset fePointLight feSpecularLighting feSpotLight feTile feTurbulence
  fieldset figcaption figure filter font font-face font-face-format font-face-name font-face-src font-face-uri
  footer foreignObject form g glyph glyphRef h1 h2 h3 h4 h5 h6 hatch hatchpath head header hkern hr html
  i iframe image img input ins kbd keygen label legend li line linearGradient link main map mark marker mask
  menu menuitem mesh meshgradient meshpatch meshrow meta metadata meter missing-glyph
  mpath nav noscript object ol optgroup option output p param path pattern picture polygon polyline pre progress q radialGradient
  rect rp rt ruby s samp script section select set small solidcolor source span stop strong style sub summary
  sup svg switch symbol table tbody td text textPath textarea tfoot th thead time title tr track tref tspan
  u ul unknown use var video view vkern wbr)

(def ^{:private true} element-marker
  (-> (js/React.createElement "div" nil)
    (gobj/get "$$typeof")))

(defn element? "Returns true if the given arg is a react element."
  [x]
  (and (object? x) (= element-marker (gobj/get x "$$typeof"))))

(s/def ::dom-element-args
  (s/cat
    :css (s/? keyword?)
    :attrs (s/? (s/or
                  :nil nil?
                  :map #(and (map? %) (not (element? %)))
                  :js-object #(and (object? %) (not (element? %)))))
    :children (s/* (s/or
                     :string string?
                     :number number?
                     :collection #(or (vector? %) (seq? %) (array? %))
                     :nil nil?
                     :element element?))))

(defn render
  "Equivalent to React.render"
  [component el]
  (js/ReactDOM.render component el))

(defn render-to-str
  "Equivalent to React.renderToString. NOTE: You must require cljsjs.react.dom.server to use this function."
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

(defn convert-props
  "Given props, which can be nil, a js-obj or a clj map: returns a js object."
  [props]
  (cond
    (nil? props)
    #js {}
    (map? props)
    (clj->js props)
    :else
    props))

;; called from macro
;; react v16 is really picky, the old direct .children prop trick no longer works
(defn macro-create-element*
  "Used internally by the DOM element generation."
  [arr]
  {:pre [(array? arr)]}
  (.apply js/React.createElement nil arr))

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

(defonce form-elements? #{"input" "select" "option" "textarea"})

(defn is-form-element? [element]
  (let [tag (.-tagName element)]
    (and tag (form-elements? (str/lower-case tag)))))

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
              this-node     (js/ReactDOM.findDOMNode this)
              value-node    (if (is-form-element? this-node)
                              this-node
                              (gdom/findNode this-node #(is-form-element? %)))
              element-value (gobj/get value-node "value")]
          (when goog.DEBUG
            (when (and state-value element-value (not= (type state-value) (type element-value)))
              (log/warn "There is a mismatch for the data type of the value on an input with value " element-value
                ". This will cause the input to miss refreshes. In general you should force the :value of an input to
                be a string since that is how values are stored on most real DOM elements.")))
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
              (apply real-factory p children)))
          (apply real-factory props children))))))


(def wrapped-input "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (wrap-form-element "input"))
(def wrapped-textarea "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (wrap-form-element "textarea"))
(def wrapped-option "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (wrap-form-element "option"))
(def wrapped-select "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (wrap-form-element "select"))

(defn- arr-append* [arr x]
  (.push arr x)
  arr)

(defn- arr-append [arr tail]
  (reduce arr-append* arr (util/force-children tail)))

(defn macro-create-wrapped-form-element
  "Used internally by element generation."
  [opts]
  (let [tag      (aget opts 0)
        props    (aget opts 1)
        children (.splice opts 2)]
    (case tag
      "input" (apply wrapped-input props children)
      "textarea" (apply wrapped-textarea props children)
      "select" (apply wrapped-select props children)
      "option" (apply wrapped-option props children))))


;; fallback if the macro didn't do this
(defn macro-create-element
  "Runtime interpretation of props. Used internally by element generation when the macro cannot expand the element at compile time."
  ([type args] (macro-create-element type args nil))
  ([type args csskw]
   (let [[head & tail] args
         f (if (form-elements? type)
             macro-create-wrapped-form-element
             macro-create-element*)]
     (cond
       (nil? head)
       (f (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
            (arr-append tail)))

       (element? head)
       (f (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
            (arr-append args)))

       (object? head)
       (f (doto #js [type (cdom/add-kwprops-to-props head csskw)]
            (arr-append tail)))

       (map? head)
       (f (doto #js [type (clj->js (cdom/add-kwprops-to-props (cdom/interpret-classes head) csskw))]
            (arr-append tail)))

       :else
       (f (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
            (arr-append args)))))))

(com.fulcrologic.fulcro.dom/gen-client-dom-fns com.fulcrologic.fulcro.dom/macro-create-element)
