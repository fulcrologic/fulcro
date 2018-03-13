(ns fulcro.client.alpha.localized-dom
  (:refer-clojure :exclude [map meta time])
  #?(:cljs (:require-macros [fulcro.client.alpha.localized-dom]))
  (:require
    [fulcro.client.alpha.dom :as adom]
    [fulcro.client.dom :as old-dom]
    [fulcro.client.primitives :as prim]
    [clojure.string :as str]
    [clojure.spec.alpha :as s]
    #?@(:clj  [
    [fulcro.client.impl.protocols :as p]
    [clojure.future :refer :all]
    [clojure.core.reducers :as r]
    [fulcro.util :as util]
    [fulcro.checksums :as chk]]
        :cljs [[cljsjs.react]
               [cljsjs.react.dom]
               [cljsjs.react.dom.server]
               [goog.object :as gobj]]))
  #?(:clj
     (:import (cljs.tagged_literals JSValue))))

(def node fulcro.client.dom/node)
(def render-to-str fulcro.client.dom/render-to-str)
(def create-element fulcro.client.dom/create-element)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLJC CSS Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(letfn [(remove-separators [s] (when s (str/replace s #"^[.#$]" "")))
        (get-tokens [k] (re-seq #"[#.$]?[^#.$]+" (name k)))]
  (defn- parse
    "Parse CSS shorthand keyword and return map of id/classes.

    (parse :.klass3#some-id.klass1.klass2)
    => {:id        \"some-id\"
        :classes [\"klass3\" \"klass1\" \"klass2\"]}"
    [k]
    (if k
      (let [tokens         (get-tokens k)
            id             (->> tokens (filter #(re-matches #"^#.*" %)) first)
            classes        (->> tokens (filter #(re-matches #"^\..*" %)))
            global-classes (into []
                             (comp
                               (filter #(re-matches #"^[$].*" %))
                               (clojure.core/map (fn [k] (-> k
                                                           name
                                                           (str/replace "$" "")))))
                             tokens)
            sanitized-id   (remove-separators id)]
        (when-not (re-matches #"^(\.[^.#$]+|#[^.#$]+|[$][^.#$]+)+$" (name k))
          (throw (ex-info "Invalid style keyword. It contains something other than classnames and IDs." {})))
        (cond-> {:global-classes global-classes
                 :classes        (into [] (keep remove-separators classes))}
          sanitized-id (assoc :id sanitized-id)))
      {})))

(defn- combined-classes
  "Takes a sequence of classname strings and a string with existing classes. Returns a string of these properly joined.

  classes-str can be nil or and empty string, and classes-seq can be nil or empty."
  [classes-seq classes-str]
  (str/join " " (if (seq classes-str) (conj classes-seq classes-str) classes-seq)))

(letfn [(pget [p nm dflt] (cond
                            #?@(:clj [(instance? JSValue p) (get-in p [:val nm] dflt)])
                            (map? p) (get p nm dflt)
                            #?@(:cljs [(object? p) (gobj/get p (name nm) dflt)])))
        (passoc [p nm v] (cond
                           #?@(:clj [(instance? JSValue p) (JSValue. (assoc (.-val p) nm v))])
                           (map? p) (assoc p nm v)
                           #?@(:cljs [(object? p) (do (gobj/set p (name nm) v) p)])))
        (pdissoc [p nm] (cond
                          #?@(:clj [(instance? JSValue p) (JSValue. (dissoc (.-val p) nm))])
                          (map? p) (dissoc p nm)
                          #?@(:cljs [(object? p) (do (gobj/remove p (name nm)) p)])))
        (strip-prefix [s] (str/replace s #"^[:.#$]*" ""))]
  (defn fold-in-classes
    "Update the :className prop in the given props to include the classes in the :classes entry of props. Works on js objects and CLJ maps as props.
    If using js props, they must be mutable."
    [props component]
    (if-let [extra-classes (pget props :classes nil)]
      (let [old-classes (pget props :className "")]
        (pdissoc
          (if component
            (let [clz         (prim/react-type component)
                  new-classes (combined-classes (clojure.core/map (fn [c]
                                                                    (let [c (some-> c name)]
                                                                      (cond
                                                                        (nil? c) ""
                                                                        (str/starts-with? c ".") (fulcro-css.css/local-class clz (strip-prefix c))
                                                                        (str/starts-with? c "$") (strip-prefix c)
                                                                        :else c))) extra-classes) old-classes)]
              (passoc props :className new-classes))
            (let [new-classes (combined-classes (clojure.core/map strip-prefix extra-classes) old-classes)]
              (passoc props :className new-classes)))
          :classes))
      props)))

(defn add-kwprops-to-props
  "Combine a hiccup-style keyword with props that are either a JS or CLJS map."
  [props kw]
  (let [{:keys [global-classes classes id] :or {classes []}} (parse kw)
        classes (vec (concat
                       (if prim/*parent*
                         (clojure.core/map #(fulcro-css.css/local-class (prim/react-type prim/*parent*) %) classes)
                         classes)
                       global-classes))]
    (fold-in-classes
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
            id (assoc :id id))))
      prim/*parent*)))

(declare tags
  a abbr address area article aside audio b base bdi bdo big blockquote body br button canvas caption cite code
  col colgroup data datalist dd del details dfn dialog div dl dt em embed fieldset figcaption figure footer form h1
  h2 h3 h4 h5 h6 head header hr html i iframe img ins input textarea select option kbd keygen label
  legend li link main map mark menu menuitem meta meter nav noscript object ol optgroup output p param picture pre
  progress q rp rt ruby s samp script section small source span strong style sub summary sup table tbody td
  tfoot th thead time title tr track u ul var video wbr circle clipPath ellipse g line mask path pattern
  polyline rect svg text defs linearGradient polygon radialGradient stop tspan)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Macros and fns for SSR and Client DOM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
   (s/def ::map-of-literals (fn [v]
                              (and (map? v)
                                (not-any? symbol? (tree-seq #(or (map? %) (vector? %) (seq? %)) seq v))))))

#?(:clj
   (s/def ::map-with-expr (fn [v]
                            (and (map? v)
                              (some #(or (symbol? %) (list? %)) (tree-seq #(or (map? %) (vector? %) (seq? %)) seq v))))))

#?(:clj
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
                        :list list?)))))

#?(:clj
   (defn emit-tag [str-tag-name is-cljs? args]
     (let [conformed-args (util/conform! ::dom-element-args args)
           {attrs    :attrs
            children :children
            css      :css} conformed-args
           css-props      (if css `(fulcro.client.alpha.localized-dom/add-kwprops-to-props nil ~css) nil)
           children       (mapv second children)
           attrs-type     (or (first attrs) :nil)           ; attrs omitted == nil
           attrs-value    (or (second attrs) {})]
       (if is-cljs?
         (case attrs-type
           :js-object
           (let [attr-expr `(fulcro.client.alpha.localized-dom/add-kwprops-to-props ~attrs-value ~css)]
             `(adom/macro-create-element*
                ~(JSValue. (into [str-tag-name attr-expr] children))))

           :map
           (let [attr-expr (if (or css (contains? attrs-value :classes))
                             `(fulcro.client.alpha.localized-dom/add-kwprops-to-props ~(clj-map->js-object attrs-value) ~css)
                             (clj-map->js-object attrs-value))]
             `(adom/macro-create-element* ~(JSValue. (into [str-tag-name attr-expr] children))))

           :runtime-map
           (let [attr-expr `(fulcro.client.alpha.localized-dom/add-kwprops-to-props ~(clj-map->js-object attrs-value) ~css)]
             `(adom/macro-create-element*
                ~(JSValue. (into [str-tag-name attr-expr] children))))

           :symbol
           `(fulcro.client.alpha.localized-dom/macro-create-element
              ~str-tag-name ~(into [attrs-value] children) ~css)

           ;; also used for MISSING props
           :nil
           `(adom/macro-create-element*
              ~(JSValue. (into [str-tag-name css-props] children)))

           ;; pure children
           `(fulcro.client.alpha.localized-dom/macro-create-element
              ~str-tag-name ~(JSValue. (into [attrs-value] children)) ~css))
         `(old-dom/element {:tag       (quote ~(symbol str-tag-name))
                            :attrs     (-> ~attrs-value
                                         (dissoc :ref :key)
                                         (fulcro.client.alpha.localized-dom/add-kwprops-to-props ~css))
                            :react-key (:key ~attrs-value)
                            :children  ~children})))))

#?(:clj
   (defn gen-dom-macro [name]
     `(defmacro ~name [& args#]
        (let [tag#      ~(str name)
              is-cljs?# (boolean (:ns ~'&env))]
          (emit-tag tag# is-cljs?# args#)))))

#?(:clj
   (defmacro gen-dom-macros []
     `(do ~@(clojure.core/map gen-dom-macro adom/tags))))

#?(:clj (gen-dom-macros))

;; fallback if the macro didn't do this
#?(:cljs
   (letfn [(arr-append* [arr x] (.push arr x) arr)
           (arr-append [arr tail] (reduce arr-append* arr tail))]
     (defn macro-create-element
       ([type args] (macro-create-element type args nil))
       ([type args csskw]
        (let [[head & tail] args]
          (cond
            (nil? head)
            (adom/macro-create-element*
              (doto #js [type (add-kwprops-to-props #js {} csskw)]
                (arr-append tail)))

            (object? head)
            (adom/macro-create-element*
              (doto #js [type (add-kwprops-to-props head csskw)]
                (arr-append tail)))

            (map? head)
            (adom/macro-create-element*
              (doto #js [type (clj->js (add-kwprops-to-props head csskw))]
                (arr-append tail)))

            (adom/element? head)
            (adom/macro-create-element*
              (doto #js [type (add-kwprops-to-props #js {} csskw)]
                (arr-append args)))

            :else
            (adom/macro-create-element*
              (doto #js [type (add-kwprops-to-props #js {} csskw)]
                (arr-append args)))))))))
