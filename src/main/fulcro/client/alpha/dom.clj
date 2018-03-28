(ns fulcro.client.alpha.dom
  "MACROS for generating CLJS code. See dom.cljs"
  (:refer-clojure :exclude [map meta time mask select])
  (:require
    [clojure.spec.alpha :as s]
    [fulcro.util :as util]
    [clojure.future :refer :all]
    [fulcro.client.alpha.dom-common :as cdom]
    [fulcro.logging :as log]
    [clojure.string :as str])
  (:import
    (cljs.tagged_literals JSValue)
    (clojure.lang ExceptionInfo)))

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

(defn- emit-tag
  "Helper function for generating CLJS DOM macros"
  [str-tag-name args]
  (let [conformed-args (util/conform! ::dom-macro-args args)
        {attrs    :attrs
         children :children
         css      :css} conformed-args
        css-props      (cdom/add-kwprops-to-props {} css)
        children       (mapv (fn [[_ c]]
                               (if (or (nil? c) (string? c))
                                 c
                                 `(fulcro.util/force-children ~c))) children)
        attrs-type     (or (first attrs) :nil)              ; attrs omitted == nil
        attrs-value    (or (second attrs) {})
        create-element (case str-tag-name
                         "input" 'fulcro.client.alpha.dom/macro-create-wrapped-form-element
                         "textarea" 'fulcro.client.alpha.dom/macro-create-wrapped-form-element
                         "select" 'fulcro.client.alpha.dom/macro-create-wrapped-form-element
                         "option" 'fulcro.client.alpha.dom/macro-create-wrapped-form-element
                         'fulcro.client.alpha.dom/macro-create-element*)]
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
      (let [attr-expr (if css
                        `(cdom/add-kwprops-to-props ~(clj-map->js-object attrs-value) ~css)
                        (clj-map->js-object attrs-value))]
        `(~create-element ~(JSValue. (into [str-tag-name attr-expr] children))))


      (:symbol :expression)
      `(fulcro.client.alpha.dom/macro-create-element
         ~str-tag-name ~(into [attrs-value] children) ~css)

      :nil
      `(~create-element
         ~(JSValue. (into [str-tag-name (JSValue. css-props)] children)))

      ;; pure children
      `(fulcro.client.alpha.dom/macro-create-element
         ~str-tag-name ~(JSValue. (into [attrs-value] children)) ~css))))

(defn syntax-error
  "Format a DOM syntax error"
  [and-form ex]
  (let [location         (clojure.core/meta and-form)
        file             (some-> (:file location) (str/replace #".*[/]" ""))
        line             (:line location)
        unexpected-input (::s/value (ex-data ex))]
    (str "Syntax error at " file ":" line ". Unexpected input " unexpected-input)))

(defn gen-dom-macro [emitter name]
  `(defmacro ~name [& ~'args]
     (let [tag# ~(str name)]
       (try
         (~emitter tag# ~'args)
         (catch ExceptionInfo e#
           (throw (ex-info (syntax-error ~'&form e#) (ex-data e#))))))))

(defmacro gen-dom-macros [emitter]
  `(do ~@(clojure.core/map (partial gen-dom-macro emitter) cdom/tags)))

(defn- gen-client-dom-fn [create-element-symbol tag]
  `(defn ~tag [& ~'args]
     (let [conformed-args# (util/conform! :fulcro.client.alpha.dom/dom-element-args ~'args) ; see CLJS file for spec
           {attrs#    :attrs
            children# :children
            css#      :css} conformed-args#
           children#       (mapv second children#)
           attrs-value#    (or (second attrs#) {})]
       (~create-element-symbol ~(name tag) (into [attrs-value#] children#) css#))))

(defmacro gen-client-dom-fns [create-element-sym]
  `(do ~@(clojure.core/map (partial gen-client-dom-fn create-element-sym) cdom/tags)))

(gen-dom-macros fulcro.client.alpha.dom/emit-tag)
