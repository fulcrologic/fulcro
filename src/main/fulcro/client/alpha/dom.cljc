(ns fulcro.client.alpha.dom
  "Client-side DOM macros and functions. For isomorphic (server) support, see also fulcro.client.alpha.dom-server"
  (:refer-clojure :exclude [map meta time mask select])
  #?(:cljs (:require-macros fulcro.client.alpha.dom))
  (:require
    fulcro.client.dom
    [clojure.spec.alpha :as s]
    [fulcro.util :as util]
    #?@(:clj  (
    [clojure.future :refer :all])
        :cljs ([cljsjs.react]
                [cljsjs.react.dom]
                [goog.object :as gobj]))
    [fulcro.client.alpha.dom-common :as cdom])
  #?(:clj
     (:import
       (cljs.tagged_literals JSValue))))


#?(:cljs
   (def ^{:private true} element-marker
     (-> (js/React.createElement "div" nil)
       (gobj/get "$$typeof"))))

#?(:cljs
   (defn element? "Returns true if the given arg is a react element."
     [x]
     (and (object? x) (= element-marker (gobj/get x "$$typeof")))))

#?(:clj
  (s/def ::map-of-literals (fn [v]
                            (and (map? v)
                              (not-any? symbol? (tree-seq #(or (map? %) (vector? %) (seq? %)) seq v))))))

#?(:clj
   (s/def ::map-with-expr (fn [v]
                          (and (map? v)
                            (some #(or (symbol? %) (list? %)) (tree-seq #(or (map? %) (vector? %) (seq? %)) seq v))))))

#?(:clj
   (s/def ::dom-macro-args
     (s/cat
       :css (s/? keyword?)
       :attrs (s/? (s/or :nil nil?
                     :map ::map-of-literals
                     :runtime-map ::map-with-expr
                     :js-object #(instance? JSValue %)
                     :symbol symbol?))
       :children (s/* (s/or :string string?
                        :number number?
                        :symbol symbol?
                        :nil nil?
                        :list list?)))))

#?(:cljs
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
                        :collection #(or (vector? %) (seq? %))
                        :element element?)))))

#?(:cljs
   (defn render
     "Equivalent to React.render"
     [component el]
     (js/ReactDOM.render component el)))

#?(:cljs
   (defn render-to-str
     "Equivalent to React.renderToString"
     [c]
     (js/ReactDOMServer.renderToString c)))

#?(:cljs
   (defn node
     "Returns the dom node associated with a component's React ref."
     ([component]
      (js/ReactDOM.findDOMNode component))
     ([component name]
      (some-> (.-refs component) (gobj/get name) (js/ReactDOM.findDOMNode)))))

#?(:cljs
   (defn create-element
     "Create a DOM element for which there exists no corresponding function.
      Useful to create DOM elements not included in React.DOM. Equivalent
      to calling `js/React.createElement`"
     ([tag]
      (create-element tag nil))
     ([tag opts]
      (js/React.createElement tag opts))
     ([tag opts & children]
      (js/React.createElement tag opts children))))

#?(:cljs (declare macro-create-wrapped-form-element macro-create-element macro-create-element*))

(declare a abbr address area article aside audio b base bdi bdo big blockquote body br button canvas caption cite
  code col colgroup data datalist dd del details dfn dialog div dl dt em embed fieldset figcaption figure footer form
  h1 h2 h3 h4 h5 h6 head header hr html i iframe img ins input textarea select option kbd keygen
  label legend li link main map mark menu menuitem meta meter nav noscript object ol optgroup output p param picture
  pre progress q rp rt ruby s samp script section small source span strong style sub summary sup table tbody
  td tfoot th thead time title tr track u ul var video wbr circle clipPath ellipse g line mask path
  pattern polyline rect svg text defs linearGradient polygon radialGradient stop tspan)

#?(:clj
   (defn clj-map->js-object
     "Recursively convert a map to a JS object. For use in macro expansion."
     [m]
     {:pre [(map? m)]}
     (JSValue. (into {}
                 (clojure.core/map (fn [[k v]]
                                     (cond
                                       (map? v) [k (clj-map->js-object v)]
                                       (vector? v) [k (mapv #(if (map? %) (clj-map->js-object %) %) v)]
                                       (symbol? v) [k `(cljs.core/clj->js ~v)]
                                       :else [k v])))
                 m))))

#?(:clj
   (defn- emit-tag
     "Helper function for generating CLJS DOM macros"
     [str-tag-name is-cljs? args]
     (let [conformed-args (util/conform! ::dom-macro-args args)
           {attrs    :attrs
            children :children
            css      :css} conformed-args
           css-props      (cdom/add-kwprops-to-props {} css)
           children       (mapv second children)
           attrs-type     (or (first attrs) :nil)           ; attrs omitted == nil
           attrs-value    (or (second attrs) {})
           create-element (case str-tag-name
                            "input" 'fulcro.client.alpha.dom/macro-create-wrapped-form-element
                            "textarea" 'fulcro.client.alpha.dom/macro-create-wrapped-form-element
                            "select" 'fulcro.client.alpha.dom/macro-create-wrapped-form-element
                            "option" 'fulcro.client.alpha.dom/macro-create-wrapped-form-element
                            'fulcro.client.alpha.dom/macro-create-element*)]
       (if is-cljs?
         (case attrs-type
           :js-object                                       ; kw combos not supported
           (if css
             (let [attr-expr `(cdom/add-kwprops-to-props ~attrs-value ~css)]
               `(~create-element ~(JSValue. (into [str-tag-name attr-expr] children))))
             `(~create-element ~(JSValue. (into [str-tag-name attrs-value] children))))

           :map
           `(~create-element ~(JSValue. (into [str-tag-name (-> attrs-value
                                                              (cdom/add-kwprops-to-props css)
                                                              (clj-map->js-object))]
                                          children)))

           :runtime-map
           (let [attr-expr (if css
                             `(cdom/add-kwprops-to-props ~(clj-map->js-object attrs-value) ~css)
                             (clj-map->js-object attrs-value))]
             `(~create-element ~(JSValue. (into [str-tag-name attr-expr] children))))


           :symbol
           `(fulcro.client.alpha.dom/macro-create-element
              ~str-tag-name ~(into [attrs-value] children) ~css)

           :nil
           `(~create-element
              ~(JSValue. (into [str-tag-name (JSValue. css-props)] children)))

           ;; pure children
           `(fulcro.client.alpha.dom/macro-create-element
              ~str-tag-name ~(JSValue. (into [attrs-value] children)) ~css))
         `(fulcro.client.dom/element {:tag       (quote ~(symbol str-tag-name))
                                      :attrs     (-> ~attrs-value
                                                   (dissoc :ref :key)
                                                   (cdom/add-kwprops-to-props ~css))
                                      :react-key (:key ~attrs-value)
                                      :children  ~children})))))

(defn- gen-dom-macro [name]
  `(defmacro ~name [& ~'args]
     (let [tag#      ~(str name)
           is-cljs?# (boolean (:ns ~'&env))]
       (emit-tag tag# is-cljs?# ~'args))))

(defmacro gen-dom-macros []
  (when (boolean (:ns &env))
    `(do ~@(clojure.core/map gen-dom-macro cdom/tags))))

#?(:cljs
   (gen-dom-macros))

(defn- gen-client-dom-fn [tag]
  `(defn ~tag [& ~'args]
     (let [conformed-args# (util/conform! ::dom-element-args ~'args)
           {attrs#    :attrs
            children# :children
            css#      :css} conformed-args#
           children#       (mapv second children#)
           attrs-value#    (or (second attrs#) {})]
       (fulcro.client.alpha.dom/macro-create-element ~(name tag) (into [attrs-value#] children#) css#))))

(defmacro gen-client-dom-fns []
  `(do ~@(clojure.core/map gen-client-dom-fn cdom/tags)))

#?(:cljs (gen-client-dom-fns))

#?(:cljs
   (defn convert-props
     "Given props, which can be nil, a js-obj or a clj map: returns a js object."
     [props]
     (cond
       (nil? props)
       #js {}
       (map? props)
       (clj->js props)
       :else
       props)))

;; called from macro
;; react v16 is really picky, the old direct .children prop trick no longer works
#?(:cljs
   (defn macro-create-element*
     "Used internally by the DOM element generation."
     [arr]
     {:pre [(array? arr)]}
     (.apply js/React.createElement nil arr)))

#?(:cljs
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
       (.setState component next-state))))

#?(:cljs
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
             (apply real-factory props children)))))))


#?(:cljs
   (def wrapped-input "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (wrap-form-element "input")))
#?(:cljs
   (def wrapped-textarea "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (wrap-form-element "textarea")))
#?(:cljs
   (def wrapped-option "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (wrap-form-element "option")))
#?(:cljs
   (def wrapped-select "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (wrap-form-element "select")))

#?(:cljs
   (defn- arr-append* [arr x]
     (.push arr x)
     arr))

#?(:cljs
   (defn- arr-append [arr tail]
     (reduce arr-append* arr tail)))

#?(:cljs
   (defn macro-create-wrapped-form-element
     "Used internally by element generation."
     [opts]
     (let [tag      (aget opts 0)
           props    (aget opts 1)
           children (aget opts 2)]
       (case tag
         "input" (apply wrapped-input props children)
         "textarea" (apply wrapped-textarea props children)
         "select" (apply wrapped-select props children)
         "option" (apply wrapped-option props children)))))

;; fallback if the macro didn't do this
#?(:cljs
   (defn macro-create-element
     "Used internally by element generation."
     ([type args] (macro-create-element type args nil))
     ([type args csskw]
      (let [[head & tail] args
            f (case type
                "input" macro-create-wrapped-form-element
                "textarea" macro-create-wrapped-form-element
                "select" macro-create-wrapped-form-element
                "option" macro-create-wrapped-form-element
                macro-create-element*)]
        (cond
          (nil? head)
          (f (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
               (arr-append tail)))

          (object? head)
          (f (doto #js [type (cdom/add-kwprops-to-props head csskw)]
               (arr-append tail)))

          (map? head)
          (f (doto #js [type (clj->js (cdom/add-kwprops-to-props head csskw))]
               (arr-append tail)))

          (element? head)
          (f (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
               (arr-append args)))

          :else
          (f (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
               (arr-append args))))))))
