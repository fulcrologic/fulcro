(ns fulcro.util
  (:refer-clojure :exclude [ident?])
  (:require
    [clojure.spec.alpha :as s]
    clojure.walk
    [fulcro.logging :as log]
    #?@(:clj
        [[clojure.stacktrace :as strace]
         [clojure.spec.gen.alpha :as sg]]))
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
  (let [[k v] (if (seq? expr)
                (ffirst expr)
                (first expr))]
    [(if (list? k) (first k) k) v]))

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

#?(:clj
   (defn resolve-externs
     "Ensures the given needs are loaded, and resolved. Updates inmap to include all of the function symbols that were requested
     as namespaced symbols.

     inmap - A map (nil/empty)
     needs - A sequence: ([namespace [f1 f2]] ...)

     Returns a map keyed by namespaced symbol whose value is the resolved function:

     {namespace/f1 (fn ...)
      namespace/h2 (fn ...)
      ...}

     Logs a detailed error message if it fails.
     "
     [inmap needs]
     (reduce (fn [m [nmspc fns]]
               (try
                 (require nmspc)
                 (let [n       (find-ns nmspc)
                       fn-keys (map #(symbol (name nmspc) (name %)) fns)
                       fnmap   (zipmap fn-keys (map #(or (ns-resolve n %) (throw (ex-info "No such symbol" {:ns nmspc :s %}))) fns))]
                   (merge m fnmap))
                 (catch Exception e
                   (log/error (str "Failed to load functions from " nmspc ". Fulcro does not have hard dependencies on that library, and you must explicitly add the dependency to your project."))
                   (log/error (with-out-str (strace/print-cause-trace e))))))
       (or inmap {})
       needs)))

#?(:clj
   (defn load-libs
     "Load libraries in Clojure dynamcically. externs is an atom that will hold the resulting resolved FQ symbols. needs
     is a list of needs as specified in `fulcro.util/resolve-externs`."
     [externs needs]
     (when (or (nil? @externs) (empty? @externs))
       (swap! externs resolve-externs needs))))

#?(:clj
   (defn build-invoke
     "Builds a function that can invoke a fq symbol by name. The returned function:

     - Ensures the specified needs are loaded (fast once loaded)
     - Looks up the function (cached)
     - Runs the function

     externs is an empty atom (which will be populated to cache the resolved functions)
     needs is a map as specified in `resolve-externs`

     ```
     (def externs (atom nil))
     (def invoke (fulcro.util/build-invoke externs '([bidi.bidi [bidi-match]])))

     ...
     (invoke 'bidi.bidi/bidi-match routes uri :request-method method)
     ```

     The generated invoke will attempt to load the function if it isn't yet loaded, and throws
     an exception if the function isn't found.

     The special fnsym 'noop will trigger loads without calling anything."
     [externs needs]
     (fn [fnsym & args]
       (load-libs externs needs)
       (when-not (= 'noop fnsym)
         (if-let [f (get @externs fnsym)]
           (apply f args)
           (throw (ex-info "Dynamically loaded function not found. You forgot to add a dependency to your classpath."
                    {:sym fnsym})))))))


(defn __integrate-ident-impl__
  "DO NOT USE!

  This logic is held here because it was originally in
  fulcro.client.primitives, but we wanted to deprecate that, move it into
  fulcro.client.mutations, and reference the mutations implementation from
  primitives. However the mutations namespace already depends on the primitives
  namespace. So we put the logic here and reference it from both places."
  [state ident & named-parameters]
  (let [actions (partition 2 named-parameters)]
    (reduce (fn [state [command data-path]]
              (let [already-has-ident-at-path? (fn [data-path] (some #(= % ident) (get-in state data-path)))]
                (case command
                  :prepend (if (already-has-ident-at-path? data-path)
                             state
                             (update-in state data-path #(into [ident] %)))
                  :append (if (already-has-ident-at-path? data-path)
                            state
                            (update-in state data-path (fnil conj []) ident))
                  :replace (let [path-to-vector (butlast data-path)
                                 to-many?       (and (seq path-to-vector) (vector? (get-in state path-to-vector)))
                                 index          (last data-path)
                                 vector         (get-in state path-to-vector)]
                             (assert (vector? data-path) (str "Replacement path must be a vector. You passed: " data-path))
                             (when to-many?
                               (do
                                 (assert (vector? vector) "Path for replacement must be a vector")
                                 (assert (number? index) "Path for replacement must end in a vector index")
                                 (assert (contains? vector index) (str "Target vector for replacement does not have an item at index " index))))
                             (assoc-in state data-path ident))
                  (throw (ex-info "Unknown post-op to merge-state!: " {:command command :arg data-path})))))
      state actions)))
