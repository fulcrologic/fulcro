(ns com.fulcrologic.fulcro.dom
  "MACROS for generating CLJS code. See dom.cljs. There are both CLJ and CLJS versions of this file, but *both* are
  form CLJS. The CLJ file is necessary because macros in CLJS are expanded at compile time in the JVM. There is
  no way to get macros and functions that work properly for both CLJ and CLJS output, so in order to get
  both a macro and function that is usable in CLJ and CLJS there need to be two namespaces. The DOM macros/functions
  that work for CLJ are in `dom-server`.

  Thus, if you are using these in a CLJC file, you MUST require them like this:

  ```
  (ns my-thing.ui
    (:require
      #?(:clj [com.fulcrologic.fulcro.dom-server :as dom]
         :cljs [com.fulcrologic.fulcro.dom :as dom])))
  ```

  This is a limitation of the operation of the language itself (if you want both macros for performance in CLJ and CLJS
  (expanded at compile time to optimal form) as well as function versions for use as lambdas).
  "
  (:refer-clojure :exclude [map meta time mask select use set symbol filter])
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom-common :as cdom]
    [clojure.string :as str])
  (:import
    (cljs.tagged_literals JSValue)
    (clojure.lang ExceptionInfo)))

(defn wrap-inputs? [] (not (false? (:wrap-inputs? (comp/current-config)))))

(defn- map-of-literals? [v]
  (and (map? v) (not-any? symbol? (tree-seq #(or (map? %) (vector? %) (seq? %)) seq v))))
(s/def ::map-of-literals map-of-literals?)

(defn- map-with-expr? [v]
  (and (map? v) (some #(or (symbol? %) (list? %)) (tree-seq #(or (map? %) (vector? %) (seq? %)) seq v))))
(s/def ::map-with-expr map-with-expr?)

(s/def ::dom-macro-args
  (s/cat
    :css (s/? keyword?)
    :attrs (s/? (s/or :nil nil?
                  :map ::map-of-literals
                  :runtime-map ::map-with-expr
                  :js-object #(instance? JSValue %)
                  :expression list?
                  :symbol symbol?))
    :children (s/* (s/or :string string?
                     :number number?
                     :symbol symbol?
                     :nil nil?
                     :list sequential?))))

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
              m)))

(defn emit-tag
  "PRIVATE.  DO NOT USE.

  Helper function for generating CLJS DOM macros. is public for code gen problems."
  [str-tag-name args]
  (let [conformed-args      (util/conform! ::dom-macro-args args)
        {attrs    :attrs
         children :children
         css      :css} conformed-args
        css-props           (cdom/add-kwprops-to-props {} css)
        raw-children        (mapv second children)
        children            (mapv (fn [[_ c]]
                                    (if (or (nil? c) (string? c))
                                      c
                                      `(comp/force-children ~c))) children)
        attrs-type          (or (first attrs) :nil)         ; attrs omitted == nil
        attrs-value         (or (second attrs) {})
        create-element      (case str-tag-name
                              "input" 'com.fulcrologic.fulcro.dom/macro-create-wrapped-form-element
                              "textarea" 'com.fulcrologic.fulcro.dom/macro-create-wrapped-form-element
                              "select" 'com.fulcrologic.fulcro.dom/macro-create-wrapped-form-element
                              "option" 'com.fulcrologic.fulcro.dom/macro-create-wrapped-form-element
                              'com.fulcrologic.fulcro.dom/macro-create-element*)
        classes-expression? (and (= attrs-type :map) (contains? attrs-value :classes))
        attrs-type          (if classes-expression? :runtime-map attrs-type)]
    (case attrs-type
      :js-object                                            ; kw combos not supported
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
      `(com.fulcrologic.fulcro.dom/macro-create-element ~str-tag-name ~(into [attrs-value] raw-children) ~css)


      (:symbol :expression)
      `(com.fulcrologic.fulcro.dom/macro-create-element
         ~str-tag-name ~(into [attrs-value] raw-children) ~css)

      :nil
      `(~create-element
         ~(JSValue. (into [str-tag-name (JSValue. css-props)] children)))

      ;; pure children
      `(com.fulcrologic.fulcro.dom/macro-create-element
         ~str-tag-name ~(JSValue. (into [attrs-value] raw-children)) ~css))))

(defn emit-tag-unwrapped
  "PRIVATE.  DO NOT USE. Same as emit-tag, but does no wrap inputs."
  [str-tag-name args]
  (let [conformed-args         (util/conform! ::dom-macro-args args)
        {attrs    :attrs
         children :children
         css      :css} conformed-args
        css-props              (cdom/add-kwprops-to-props {} css)
        raw-children           (mapv second children)
        children               (mapv (fn [[_ c]]
                                       (if (or (nil? c) (string? c))
                                         c
                                         `(comp/force-children ~c))) children)
        attrs-type             (or (first attrs) :nil)      ; attrs omitted == nil
        attrs-value            (or (second attrs) {})
        raw-create-element     'com.fulcrologic.fulcro.dom/macro-create-element*
        runtime-create-element 'com.fulcrologic.fulcro.dom/macro-create-unwrapped-element
        classes-expression?    (and (= attrs-type :map) (contains? attrs-value :classes))
        attrs-type             (if classes-expression? :runtime-map attrs-type)]
    (case attrs-type
      :js-object                                            ; kw combos not supported
      (if css
        (let [attr-expr `(cdom/add-kwprops-to-props ~attrs-value ~css)]
          `(~raw-create-element ~(JSValue. (into [str-tag-name attr-expr] children))))
        `(~raw-create-element ~(JSValue. (into [str-tag-name attrs-value] children))))

      :map
      `(~raw-create-element ~(JSValue. (into [str-tag-name (-> attrs-value
                                                             (cdom/add-kwprops-to-props css)
                                                             (clj-map->js-object))]
                                         children)))

      :runtime-map
      `(~runtime-create-element ~str-tag-name ~(into [attrs-value] raw-children) ~css)

      (:symbol :expression)
      `(~runtime-create-element
         ~str-tag-name ~(into [attrs-value] raw-children) ~css)

      :nil
      `(~raw-create-element
         ~(JSValue. (into [str-tag-name (JSValue. css-props)] children)))

      ;; pure children
      `(~runtime-create-element
         ~str-tag-name ~(JSValue. (into [attrs-value] raw-children)) ~css))))

(defn syntax-error
  "Format a DOM syntax error"
  [and-form ex]
  (let [location         (clojure.core/meta and-form)
        file             (some-> (:file location) (str/replace #".*[/]" ""))
        line             (:line location)
        unexpected-input (::s/value (ex-data ex))]
    (str "Syntax error at " file ":" line ". Unexpected input " unexpected-input)))

(defn gen-dom-macro [emitter name]
  `(defmacro ~name ~(cdom/gen-docstring name true)
     {:style/indent :defn}
     [& ~'args]
     (let [tag# ~(str name)]
       (try
         (~emitter tag# ~'args)
         (catch ExceptionInfo e#
           (throw (ex-info (syntax-error ~'&form e#) (ex-data e#))))))))

(defmacro gen-dom-macros
  ([emitter unwrapped-emitter]
   ;; System out sometimes gets munged into actual js output, for whatever buggy reason
   (if (wrap-inputs?)
     `(do ~@(clojure.core/map (partial gen-dom-macro emitter) cdom/tags))
     `(do ~@(clojure.core/map (partial gen-dom-macro unwrapped-emitter) cdom/tags))))
  ([emitter]
   `(do ~@(clojure.core/map (partial gen-dom-macro emitter) cdom/tags))))

(defn- gen-client-dom-fn [create-element-symbol tag]
  `(defn ~tag ~(cdom/gen-docstring tag true)
     [& ~'args]
     (let [conformed-args# (com.fulcrologic.fulcro.dom/parse-args ~'args) ; see CLJS file for spec
           {attrs#    :attrs
            children# :children
            css#      :css} conformed-args#
           children#       (mapv second children#)
           attrs-value#    (or (second attrs#) {})]
       (~create-element-symbol ~(name tag) (into [attrs-value#] children#) css#))))

(defmacro gen-client-dom-fns
  ([create-element-sym]
   `(do ~@(clojure.core/map (partial gen-client-dom-fn create-element-sym) cdom/tags)))
  ([create-element-sym create-unwrapped-element-sym]
   (if (wrap-inputs?)
     (do
       (.println System/err "Using Wrapped inputs")
       `(do ~@(clojure.core/map (partial gen-client-dom-fn create-element-sym) cdom/tags)))
     (do
       (.println System/err "Using RAW inputs. Remember to use `comp/transact!!` for sync updates on DOM events.")
       `(do ~@(clojure.core/map (partial gen-client-dom-fn create-unwrapped-element-sym) cdom/tags))))))

(gen-dom-macros com.fulcrologic.fulcro.dom/emit-tag com.fulcrologic.fulcro.dom/emit-tag-unwrapped)
