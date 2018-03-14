(ns fulcro.client.alpha.localized-dom
  (:refer-clojure :exclude [map meta time])
  (:require
    [fulcro.client.dom :as old-dom]
    [clojure.spec.alpha :as s]
    [clojure.future :refer :all]
    [fulcro.util :as util]
    fulcro.client.alpha.localized-dom-common)
  (:import (cljs.tagged_literals JSValue)))

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

(s/def ::map-of-literals (fn [v]
                           (and (map? v)
                             (not-any? symbol? (tree-seq #(or (map? %) (vector? %) (seq? %)) seq v)))))

(s/def ::map-with-expr (fn [v]
                         (and (map? v)
                           (some #(or (symbol? %) (list? %)) (tree-seq #(or (map? %) (vector? %) (seq? %)) seq v)))))

(s/def ::dom-element-args
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
                     :list list?))))

(defn emit-tag [str-tag-name is-cljs? args]
  (let [conformed-args (util/conform! ::dom-element-args args)
        {attrs    :attrs
         children :children
         css      :css} conformed-args
        css-props      (if css `(fulcro.client.alpha.localized-dom-common/add-kwprops-to-props nil ~css) nil)
        children       (mapv second children)
        attrs-type     (or (first attrs) :nil)              ; attrs omitted == nil
        attrs-value    (or (second attrs) {})]
    (if is-cljs?
      (case attrs-type
        :js-object
        (let [attr-expr `(fulcro.client.alpha.localized-dom-common/add-kwprops-to-props ~attrs-value ~css)]
          `(fulcro.client.alpha.dom/macro-create-element*
             ~(JSValue. (into [str-tag-name attr-expr] children))))

        :map
        (let [attr-expr (if (or css (contains? attrs-value :classes))
                          `(fulcro.client.alpha.localized-dom-common/add-kwprops-to-props ~(clj-map->js-object attrs-value) ~css)
                          (clj-map->js-object attrs-value))]
          `(fulcro.client.alpha.dom/macro-create-element* ~(JSValue. (into [str-tag-name attr-expr] children))))

        :runtime-map
        (let [attr-expr `(fulcro.client.alpha.localized-dom-common/add-kwprops-to-props ~(clj-map->js-object attrs-value) ~css)]
          `(fulcro.client.alpha.dom/macro-create-element*
             ~(JSValue. (into [str-tag-name attr-expr] children))))

        :symbol
        `(fulcro.client.alpha.localized-dom/macro-create-element
           ~str-tag-name ~(into [attrs-value] children) ~css)

        ;; also used for MISSING props
        :nil
        `(fulcro.client.alpha.dom/macro-create-element*
           ~(JSValue. (into [str-tag-name css-props] children)))

        ;; pure children
        `(fulcro.client.alpha.localized-dom/macro-create-element
           ~str-tag-name ~(JSValue. (into [attrs-value] children)) ~css))
      `(old-dom/element {:tag       (quote ~(symbol str-tag-name))
                         :attrs     (-> ~attrs-value
                                      (dissoc :ref :key)
                                      (fulcro.client.alpha.localized-dom-common/add-kwprops-to-props ~css))
                         :react-key (:key ~attrs-value)
                         :children  ~children}))))

(defn gen-dom-macro [name]
  `(defmacro ~name [& args#]
     (let [tag#      ~(str name)
           is-cljs?# (boolean (:ns ~'&env))]
       (emit-tag tag# is-cljs?# args#))))

(defmacro gen-dom-macros []
  `(do ~@(clojure.core/map gen-dom-macro fulcro.client.alpha.dom-common/tags)))

(gen-dom-macros)
