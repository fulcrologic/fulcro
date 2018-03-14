(ns fulcro.client.alpha.dom
  "MACROS for generating CLJS code. See dom.cljs"
  (:require
    [clojure.spec.alpha :as s]
    [fulcro.util :as util]
    [clojure.future :refer :all]
    [fulcro.client.alpha.dom-common :as cdom])
  (:import
    (cljs.tagged_literals JSValue)))

(s/def ::map-of-literals
  (fn [v]
    (and (map? v)
      (not-any? symbol? (tree-seq #(or (map? %) (vector? %) (seq? %)) seq v)))))

(s/def ::map-with-expr
  (fn [v]
    (and (map? v)
      (some #(or (symbol? %) (list? %)) (tree-seq #(or (map? %) (vector? %) (seq? %)) seq v)))))

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
                     :list list?))))

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
  [str-tag-name is-cljs? args]
  (let [conformed-args (util/conform! ::dom-macro-args args)
        {attrs    :attrs
         children :children
         css      :css} conformed-args
        css-props      (cdom/add-kwprops-to-props {} css)
        children       (mapv second children)
        attrs-type     (or (first attrs) :nil)              ; attrs omitted == nil
        attrs-value    (or (second attrs) {})
        create-element (case str-tag-name
                         "input" 'fulcro.client.alpha.dom/macro-create-wrapped-form-element
                         "textarea" 'fulcro.client.alpha.dom/macro-create-wrapped-form-element
                         "select" 'fulcro.client.alpha.dom/macro-create-wrapped-form-element
                         "option" 'fulcro.client.alpha.dom/macro-create-wrapped-form-element
                         'fulcro.client.alpha.dom/macro-create-element*)]
    (if is-cljs?
      (case attrs-type
        :js-object                                          ; kw combos not supported
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
                                   :children  ~children}))))

(defn- gen-dom-macro [name]
  `(defmacro ~name [& ~'args]
    (let [tag#      ~(str name)
          is-cljs?# (boolean (:ns ~'&env))]
      (emit-tag tag# is-cljs?# ~'args))))

(defmacro gen-dom-macros []
  `(do ~@(clojure.core/map gen-dom-macro cdom/tags)))

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

(gen-dom-macros)
