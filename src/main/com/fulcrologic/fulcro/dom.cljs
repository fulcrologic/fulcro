(ns com.fulcrologic.fulcro.dom
  "Client-side DOM macros and functions. For isomorphic (server) support, see also com.fulcrologic.fulcro.dom-server"
  (:refer-clojure :exclude [filter map mask meta select set symbol time use])
  (:require-macros [com.fulcrologic.fulcro.dom])
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp]
    ["react" :as react]
    ["react-dom" :as react.dom]
    [com.fulcrologic.fulcro.dom-common :as cdom]
    [com.fulcrologic.fulcro.dom.inputs :as inputs]
    [goog.object :as gobj]))

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

(defn element? "Returns true if the given arg is a react element."
  [x]
  (react/isValidElement x))

(defn child->typed-child [child]
  (cond
    (string? child) [:string child]
    (number? child) [:number child]
    (or (vector? child) (seq? child) (array? child)) [:collection child]
    (nil? child) [:nil child]
    (element? child) [:element child]))

(defn parse-args
  "Runtime parsing of DOM tag arguments. Returns a map with keys :css, :attrs, and :children."
  [args]
  (letfn [(parse-css [[args result :as pair]]
            (let [arg (first args)]
              (if (keyword? arg)
                [(next args) (assoc result :css arg)]
                pair)))
          (parse-attrs [[args result :as pair]]
            (let [has-arg? (seq args)
                  arg      (first args)]
              (cond
                (and has-arg? (nil? arg)) [(next args) (assoc result :attrs [:nil nil])]
                (and (object? arg) (not (element? arg))) [(next args) (assoc result :attrs [:js-object arg])]
                (and (map? arg) (not (element? arg))) [(next args) (assoc result :attrs [:map arg])]
                :else pair)))
          (parse-children [[args result]]
            [nil (cond-> result
                   (seq args) (assoc :children (mapv child->typed-child args)))])]
    (-> [args {}]
      (parse-css)
      (parse-attrs)
      (parse-children)
      second)))

(defn render
  "Equivalent to React.render"
  [component el]
  (react.dom/render component el))

(defn ^:deprecated render-to-str
  "This fn is outdated - it expects js/ReactDOMServer to be defined (used to be provided cljsjs.react.dom.server).
  It is better to do it yourself (under shadow-cljs):

   ```clj
   (ns ex (:require [\"react-dom/server\" :as react-dom-server] ...))
   (react-dom-server/renderToString c)
   ```"
  [c]
  (js/ReactDOMServer.renderToString c))

(defn ^:deprecated node
  "Use React refs. Finding the node this way doesn't work anymore. React internal changes."
  ([component])
  ([^js component name]))

(def Input
  "React component that wraps dom/input to prevent cursor madness."
  (inputs/StringBufferedInput ::Input {:string->model identity
                                       :model->string identity}))

(def ui-input
  "A wrapped input. Use this when you see the cursor jump around while you're trying to type in an input. Drop-in replacement
   for `dom/input`.

   NOTE: The onChange and onBlur handlers will receive a string value, not an event. If you want the raw event on changes use onInput."
  (comp/factory Input {:keyfn :key}))

(defn create-element
  "Create a DOM element for which there exists no corresponding function.
   Useful to create DOM elements not included in React.DOM. Equivalent
   to calling `js/React.createElement`"
  ([tag]
   (create-element tag nil))
  ([tag opts]
   (react/createElement tag opts))
  ([tag opts & children]
   (apply react/createElement tag opts children)))

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
  (.apply react/createElement nil arr))

(defonce form-elements? #{"input" "select" "option" "textarea"})

(defn is-form-element? [element]
  (let [tag (.-tagName element)]
    (and tag (form-elements? (str/lower-case tag)))))

(defn wrap-form-element [input-type]
  (let [element (react/forwardRef
                  (fn [^js props ^js ref]
                    (let [checkbox?        (= "checkbox" (gobj/get props "type"))
                          value-field      (if checkbox? "checked" "value")
                          props-value      (or (gobj/get props value-field) "")
                          state            (react/useState props-value)
                          local-value      (aget state 0)
                          set-local-value! (aget state 1)
                          cursor-ref       (react/useRef nil)
                          pending-ref      (react/useRef #js [])
                          prev-props-ref   (react/useRef props-value)
                          our-ref          (react/useRef nil)
                          merged-ref-fn    (react/useCallback
                                             (fn [el]
                                               (set! (.-current our-ref) el)
                                               (when ref
                                                 (if (fn? ref)
                                                   (ref el)
                                                   (set! (.-current ref) el))))
                                             #js [ref])]
                      ;; Detect props changes during render (React-blessed getDerivedStateFromProps pattern)
                      (when (not= props-value (.-current prev-props-ref))
                        (set! (.-current prev-props-ref) props-value)
                        (let [pending (.-current pending-ref)
                              idx     (.indexOf pending props-value)]
                          (if (>= idx 0)
                            ;; Catch-up from typing — drop matched and older entries
                            (set! (.-current pending-ref) (.slice pending (inc idx)))
                            ;; External update — accept it
                            (do
                              (set! (.-current pending-ref) #js [])
                              (set-local-value! props-value)))))
                      ;; Restore cursor position after React commits DOM (before paint)
                      (react/useLayoutEffect
                        (fn []
                          (when-let [pos (.-current cursor-ref)]
                            (set! (.-current cursor-ref) nil)
                            (when-let [el (.-current our-ref)]
                              (try
                                (.setSelectionRange el pos pos)
                                (catch :default _))))
                          js/undefined))
                      (if (gobj/containsKey props value-field)
                        (let [new-props      (gobj/clone props)
                              user-on-change (gobj/get props "onChange")
                              on-change      (fn [^js evt]
                                               (let [el (.-target evt)
                                                     v  (gobj/get el value-field)]
                                                 ;; Save cursor for text inputs
                                                 (when-not checkbox?
                                                   (set! (.-current cursor-ref)
                                                     (.-selectionStart el)))
                                                 (.push (.-current pending-ref) v)
                                                 (set-local-value! v)
                                                 (when user-on-change
                                                   (user-on-change evt))))]
                          (gobj/set new-props "value" local-value)
                          (gobj/set new-props "onChange" on-change)
                          (gobj/set new-props "ref" merged-ref-fn)
                          (create-element input-type new-props))
                        ;; No value field — pass through props and ref
                        (let [final-props (if ref
                                            (doto (gobj/clone props)
                                              (gobj/set "ref" ref))
                                            props)]
                          (create-element input-type final-props))))))]
    (fn [^js props & children]
      (create-element element props children))))


(def wrapped-input "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (wrap-form-element "input"))
(def wrapped-textarea "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (wrap-form-element "textarea"))
(def wrapped-option "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (wrap-form-element "option"))
(def wrapped-select "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (wrap-form-element "select"))

(defn- arr-append* [arr x]
  (.push arr x)
  arr)

(defn- arr-append [arr tail]
  (reduce arr-append* arr tail))

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
   (let [[head & tail] (mapv comp/force-children args)
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

(defn macro-create-unwrapped-element
  "Just like macro-create-element, but never wraps form input types."
  ([type args] (macro-create-element type args nil))
  ([type args csskw]
   (let [[head & tail] (mapv comp/force-children args)]
     (cond
       (nil? head)
       (macro-create-element* (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
                                (arr-append tail)))

       (element? head)
       (macro-create-element* (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
                                (arr-append args)))

       (object? head)
       (macro-create-element* (doto #js [type (cdom/add-kwprops-to-props head csskw)]
                                (arr-append tail)))

       (map? head)
       (macro-create-element* (doto #js [type (clj->js (cdom/add-kwprops-to-props (cdom/interpret-classes head) csskw))]
                                (arr-append tail)))

       :else
       (macro-create-element* (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
                                (arr-append args)))))))

(com.fulcrologic.fulcro.dom/gen-client-dom-fns com.fulcrologic.fulcro.dom/macro-create-element com.fulcrologic.fulcro.dom/macro-create-unwrapped-element)
