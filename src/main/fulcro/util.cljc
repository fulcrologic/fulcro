(ns fulcro.util
  (:refer-clojure :exclude [ident?])
  (:require
    [clojure.spec.alpha :as s]
    clojure.walk
    [fulcro.client.logging :as log]
    #?(:clj
    [clojure.spec.gen.alpha :as sg]))
  #?(:clj
     (:import (clojure.lang Atom))))

(defn force-children [x]
  (cond->> x
    (seq? x) (into [] (map force-children))))

(defn union?
  #?(:cljs {:tag boolean})
  [expr]
  (let [expr (cond-> expr (seq? expr) first)]
    (and (map? expr)
      (map? (-> expr first second)))))

(defn join? [x]
  #?(:cljs {:tag boolean})
  (let [x (if (seq? x) (first x) x)]
    (map? x)))

(defn ident?
  "Returns true if x is an ident."
  #?(:cljs {:tag boolean})
  [x]
  (and (vector? x)
    (== 2 (count x))
    (keyword? (nth x 0))))

(defn join-entry [expr]
  (if (seq? expr)
    (ffirst expr)
    (first expr)))

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

(defn unique-ident?
  #?(:cljs {:tag boolean})
  [x]
  (and (ident? x) (= '_ (second x))))

(defn recursion?
  #?(:cljs {:tag boolean})
  [x]
  (or #?(:clj  (= '... x)
         :cljs (symbol-identical? '... x))
    (number? x)))

(defn mutation?
  #?(:cljs {:tag boolean})
  [expr]
  (or (mutation-join? expr) (symbol? (cond-> expr (seq? expr) first))))

(defn mutation-key [expr]
  {:pre [(symbol? (first expr))]}
  (first expr))

(defn unique-key
  "Get a unique string-based key. Never returns the same value."
  []
  (let [s #?(:clj (java.util.UUID/randomUUID)
             :cljs (random-uuid))]
    (str s)))

(defn atom? [a] (instance? Atom a))

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

(defn soft-invariant
  "Logs the given message if v is false."
  [v msg]
  (when-not v (log/error "Invariant failed")))

#?(:clj
   (def TRUE (s/with-gen (constantly true) sg/int)))

