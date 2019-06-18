(ns com.fulcrologic.fulcro.algorithms.misc
  "Random functions that were ported from Fulcro.  Do not use except internally, as I plan to
  move them around still."
  (:refer-clojure :exclude [ident? uuid])
  (:require
    [taoensso.timbre :as log]
    [edn-query-language.core :as eql]
    #?(:cljs [goog.object :as gobj])
    [clojure.spec.alpha :as s])
  #?(:clj
     (:import (clojure.lang Atom))))

(defn atom? [a] (instance? Atom a))

(defn uuid
  #?(:clj ([] (java.util.UUID/randomUUID)))
  #?(:clj ([n]
           (java.util.UUID/fromString
             (format "ffffffff-ffff-ffff-ffff-%012d" n))))
  #?(:cljs ([] (random-uuid)))
  #?(:cljs ([& args] (cljs.core/uuid (apply str args)))))

(defn join-entry [expr]
  (let [[k v] (if (seq? expr)
                (ffirst expr)
                (first expr))]
    [(if (list? k) (first k) k) v]))

(defn join? [x]
  #?(:cljs {:tag boolean})
  (let [x (if (seq? x) (first x) x)]
    (map? x)))

(defn recursion?
  #?(:cljs {:tag boolean})
  [x]
  (or #?(:clj  (= '... x)
         :cljs (symbol-identical? '... x))
    (number? x)))

(defn union?
  #?(:cljs {:tag boolean})
  [expr]
  (let [expr (cond-> expr (seq? expr) first)]
    (and (map? expr)
      (map? (-> expr first second)))))

(defn join-key [expr]
  (cond
    (map? expr) (let [k (ffirst expr)]
                  (if (list? k)
                    (first k)
                    (ffirst expr)))
    (seq? expr) (join-key (first expr))
    :else expr))

(defn join-value [join]
  (second (join-entry join)))

(defn mutation-join? [expr]
  (and (join? expr) (symbol? (join-key expr))))

(defn now
  "Returns current time in ms."
  []
  #?(:clj  (java.util.Date.)
     :cljs (js/Date.)))

(defn deep-merge [& xs]
  "Merges nested maps without overwriting existing keys."
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))

(defn force-children [x]
  (cond->> x
    (seq? x) (into [] (map force-children))))

(defn conform! [spec x]
  (let [rt (s/conform spec x)]
    (when (s/invalid? rt)
      (throw (ex-info (s/explain-str spec x)
               (s/explain-data spec x))))
    rt))

(defn elide-ast-nodes
  "Remove items from a query (AST) that have a key that returns true for the elision-predicate"
  [{:keys [key union-key children] :as ast} elision-predicate]
  (let [union-elision? (elision-predicate union-key)]
    (when-not (or union-elision? (elision-predicate key))
      (when (and union-elision? (<= (count children) 2))
        (log/warn "Unions are not designed to be used with fewer than two children. Check your calls to Fulcro
        load functions where the :without set contains " (pr-str union-key)))
      (update ast :children (fn [c] (vec (keep #(elide-ast-nodes % elision-predicate) c)))))))

(defn elide-query-nodes
  "Remove items from a query when the query element where the (node-predicate key) returns true. Commonly used with
   a set as a predicate to elide specific well-known UI-only paths."
  [query node-predicate]
  (-> query eql/query->ast (elide-ast-nodes node-predicate) eql/ast->query))

(defn isoget-in
  ([obj kvs]
   (isoget-in obj kvs nil))
  ([obj kvs default]
   #?(:clj (get-in obj kvs default)
      :cljs
           (let [ks (mapv (fn [k] (some-> k name)) kvs)]
             (or (apply gobj/getValueByKeys obj ks) default)))))

(defn isoget
  ([obj k] (isoget obj k nil))
  ([obj k default]
   #?(:clj  (get obj k default)
      :cljs (or (gobj/get obj (some-> k (name))) default))))

(defn ghostwheel-enabled?
  #?(:cljs {:tag boolean})
  []
  true)
