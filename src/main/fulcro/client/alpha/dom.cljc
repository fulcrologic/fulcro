(ns fulcro.client.alpha.dom
  (:refer-clojure :exclude [map meta time mask select])
  #?(:cljs (:require-macros fulcro.client.alpha.dom))
  (:require
    fulcro.client.dom
    [clojure.string :as str]
    [fulcro.client.impl.protocols :as p]
    [fulcro.util :as util]
    [clojure.spec.alpha :as s]
    #?@(:clj  (
    [clojure.core.reducers :as r]
    [clojure.future :refer :all]
    [fulcro.checksums :as chk])
        :cljs ([cljsjs.react]
                [cljsjs.react.dom]
                [goog.object :as gobj]))
    [fulcro.client.dom :as dom])
  #?(:clj
     (:import
       (fulcro.client.dom Element)
       (cljs.tagged_literals JSValue))))

(def node fulcro.client.dom/node)
(def render-to-str fulcro.client.dom/render-to-str)
(def create-element fulcro.client.dom/create-element)

#?(:cljs (declare macro-create-wrapped-form-element macro-create-element macro-create-element*))

(defn- remove-separators [s]
  (when s
    (str/replace s #"^[.#]" "")))

(defn- get-tokens [k]
  (re-seq #"[#.]?[^#.]+" (name k)))

(defn- parse
  "Parse CSS shorthand keyword and return map of id/classes.

  (parse :.klass3#some-id.klass1.klass2)
  => {:id        \"some-id\"
      :classes [\"klass3\" \"klass1\" \"klass2\"]}"
  [k]
  (if k
    (let [tokens       (get-tokens k)
          id           (->> tokens (filter #(re-matches #"^#.*" %)) first)
          classes      (->> tokens (filter #(re-matches #"^\..*" %)))
          sanitized-id (remove-separators id)]
      (when-not (re-matches #"^(\.[^.#]+|#[^.#]+)+$" (name k))
        (throw (ex-info "Invalid style keyword. It contains something other than classnames and IDs." {})))
      (cond-> {:classes (into []
                          (keep remove-separators classes))}
        sanitized-id (assoc :id sanitized-id)))
    {}))

(defn- combined-classes
  "Takes a sequence of classname strings and a string with existing classes. Returns a string of these properly joined.

  classes-str can be nil or and empty string, and classes-seq can be nil or empty."
  [classes-seq classes-str]
  (str/join " " (if (seq classes-str) (conj classes-seq classes-str) classes-seq)))

(defn add-kwprops-to-props
  "Combine a hiccup-style keyword with props that are either a JS or CLJS map."
  [props kw]
  (let [{:keys [classes id] :or {classes []}} (parse kw)]
    (if #?(:clj false :cljs (or (nil? props) (object? props)))
      #?(:clj  props
         :cljs (let [props            (gobj/clone props)
                     existing-classes (gobj/get props "className")]
                 (when (seq classes) (gobj/set props "className" (combined-classes classes existing-classes)))
                 (when id (gobj/set props "id" id))
                 props))
      (let [existing-classes (:className props)]
        (cond-> (or props {})
          (seq classes) (assoc :className (combined-classes classes existing-classes))
          id (assoc :id id))))))


(declare a abbr address area article aside audio b base bdi bdo big blockquote body br button canvas caption cite
  code col colgroup data datalist dd del details dfn dialog div dl dt em embed fieldset figcaption figure footer form
  h1 h2 h3 h4 h5 h6 head header hr html i iframe img ins input textarea select option kbd keygen
  label legend li link main map mark menu menuitem meta meter nav noscript object ol optgroup output p param picture
  pre progress q rp rt ruby s samp script section small source span strong style sub summary sup table tbody
  td tfoot th thead time title tr track u ul var video wbr circle clipPath ellipse g line mask path
  pattern polyline rect svg text defs linearGradient polygon radialGradient stop tspan)

(def tags '#{a abbr address area article aside audio b base bdi bdo big blockquote body br button canvas caption cite code
             col colgroup data datalist dd del details dfn dialog div dl dt em embed fieldset figcaption figure footer form h1
             h2 h3 h4 h5 h6 head header hr html i iframe img input ins kbd keygen label legend li link main
             map mark menu menuitem meta meter nav noscript object ol optgroup option output p param picture pre progress q rp rt
             ruby s samp script section select small source span strong style sub summary sup table tbody td textarea
             tfoot th thead time title tr track u ul var video wbr circle clipPath ellipse g line mask path pattern
             polyline rect svg text defs linearGradient polygon radialGradient stop tspan})

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

#?(:cljs
   (def ^{:private true} element-marker
     (-> (js/React.createElement "div" nil)
       (gobj/get "$$typeof"))))

#?(:clj
   (defn element? [x] (instance? Element x))
   :cljs
   (defn element? "Returns true if the given arg is a react element."
     [x]
     (and (object? x)
       (= element-marker (gobj/get x "$$typeof")))))

(s/def ::map-of-literals (fn [v]
                           (and (map? v)
                             (not (element? v))
                             (not-any? symbol? (tree-seq #(or (map? %) (vector? %) (seq? %)) seq v)))))

(s/def ::map-with-expr (fn [v]
                         (and (map? v)
                           (not (element? v))
                           (some #(or (symbol? %) (list? %)) (tree-seq #(or (map? %) (vector? %) (seq? %)) seq v)))))

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

#?(:clj
   (defn- emit-tag [str-tag-name is-cljs? args]
     (let [conformed-args (util/conform! ::dom-macro-args args)
           {attrs    :attrs
            children :children
            css      :css} conformed-args
           css-props      (add-kwprops-to-props {} css)
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
             (let [attr-expr `(fulcro.client.alpha.dom/add-kwprops-to-props ~attrs-value ~css)]
               `(~create-element ~(JSValue. (into [str-tag-name attr-expr] children))))
             `(~create-element ~(JSValue. (into [str-tag-name attrs-value] children))))

           :map
           `(~create-element ~(JSValue. (into [str-tag-name (-> attrs-value
                                                              (add-kwprops-to-props css)
                                                              (clj-map->js-object))]
                                          children)))

           :runtime-map
           (let [attr-expr (if css
                             `(fulcro.client.alpha.dom/add-kwprops-to-props ~(clj-map->js-object attrs-value) ~css)
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
                                                   (fulcro.client.alpha.dom/add-kwprops-to-props ~css))
                                      :react-key (:key ~attrs-value)
                                      :children  ~children})))))

(defn- gen-dom-macro [name]
  `(defmacro ~name [& args#]
     (let [tag#      ~(str name)
           is-cljs?# (boolean (:ns ~'&env))]
       (emit-tag tag# is-cljs?# args#))))

(defmacro gen-dom-macros []
  (when (boolean (:ns &env))
    `(do ~@(clojure.core/map gen-dom-macro tags))))

#?(:clj
   (gen-dom-macros))

(s/def ::dom-element-args
  (s/cat
    :css (s/? keyword?)
    :attrs (s/? (s/or
                  :nil nil?
                  :map #(and (map? %) (not (element? %)))
                  :js-object #?(:clj  #(instance? JSValue %)
                                :cljs #(and (object? %) (not (element? %))))))
    :children (s/* (s/or
                     :string string?
                     :number number?
                     :collection #(or (vector? %) (seq? %))
                     :element element?))))

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
  `(do ~@(clojure.core/map gen-client-dom-fn tags)))

#?(:cljs (fulcro.client.alpha.dom/gen-client-dom-fns))

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
   (def wrapped-input "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (dom/wrap-form-element "input")))
#?(:cljs
   (def wrapped-textarea "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (dom/wrap-form-element "textarea")))
#?(:cljs
   (def wrapped-option "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (dom/wrap-form-element "option")))
#?(:cljs
   (def wrapped-select "Low-level form input, with no syntactic sugar. Used internally by DOM macros" (dom/wrap-form-element "select")))

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
          (f (doto #js [type (add-kwprops-to-props #js {} csskw)]
               (arr-append tail)))

          (object? head)
          (f (doto #js [type (add-kwprops-to-props head csskw)]
               (arr-append tail)))

          (map? head)
          (f (doto #js [type (clj->js (add-kwprops-to-props head csskw))]
               (arr-append tail)))

          (element? head)
          (f (doto #js [type (add-kwprops-to-props #js {} csskw)]
               (arr-append args)))

          :else
          (f (doto #js [type (add-kwprops-to-props #js {} csskw)]
               (arr-append args))))))))

;; Server-side only gets function versions
#?(:clj
   (defn gen-tag-fn [tag]
     `(defn ~tag [& ~'args]
        (let [conformed-args# (util/conform! ::dom-element-args ~'args)
              {attrs#    :attrs
               children# :children
               css#      :css} conformed-args#
              children#       (mapv second children#)
              attrs-value#    (or (second attrs#) {})]
          (fulcro.client.dom/element {:tag       '~tag
                                      :attrs     (-> attrs-value#
                                                   (dissoc :ref :key)
                                                   (fulcro.client.alpha.dom/add-kwprops-to-props css#))
                                      :react-key (:key attrs-value#)
                                      :children  children#})))))

#?(:clj
   (defmacro gen-all-tags []
     (when-not (boolean (:ns &env))
       `(do
          ~@(clojure.core/map gen-tag-fn tags)))))

#?(:clj
   (gen-all-tags))
