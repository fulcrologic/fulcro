(ns com.fulcrologic.fulcro.algorithms.do-not-use
  "Some misc. utility functions. These are primarily meant for internal use, and are subject to
  relocation and removal in the future.

  You have been warned. Changes to this ns (or its complete removal)
  will not be considered breaking changes to the library, and no mention of said changes
  will even appear in the changelog."
  (:require
    [taoensso.timbre :as log]
    [edn-query-language.core :as eql]
    #?(:cljs [goog.object :as gobj])
    [clojure.spec.alpha :as s])
  #?(:clj
     (:import (clojure.lang Atom))))

(defn atom? [a] (instance? Atom a))

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

(defn conform! [spec x]
  (let [rt (s/conform spec x)]
    (when (s/invalid? rt)
      (throw (ex-info (s/explain-str spec x)
               (s/explain-data spec x))))
    rt))

(defn destructured-keys
  "Calculates the keys that are being extracted in a legal map destructuring expression.

  - `m`: A map containing legal CLJ destructurings, like `{:keys [a] x :x ::keys [y]}`

  Returns a set of all keywords that are destructured in the map.

  Example:

  ```
  (destructured-keys {:a/keys [v] sym :other-key}) => #{:a/v :other-key}
  ```
  "
  [m]
  (let [regular-destructurings (reduce
                                 (fn [acc k]
                                   (if (and (keyword? k) (= "keys" (name k)))
                                     (let [simple-syms (get m k)
                                           included-ns (namespace k)
                                           source-keys (into #{}
                                                         (map (fn [s]
                                                                (cond
                                                                  included-ns (keyword included-ns (name s))
                                                                  (and (keyword? s) (namespace s)) s
                                                                  (namespace s) (keyword (namespace s) (name s))
                                                                  :else (keyword s))))
                                                         simple-syms)]
                                       (into acc source-keys))
                                     acc))
                                 #{}
                                 (keys m))
        symbol-destructrings   (reduce
                                 (fn [acc k]
                                   (if (symbol? k)
                                     (conj acc (get m k))
                                     acc))
                                 #{}
                                 (keys m))]
    (into regular-destructurings symbol-destructrings)))
