(ns com.fulcrologic.fulcro.algorithms.core
  "Small, dependency-free `clojure.core`-like utilities used throughout Fulcro.

   These were previously pulled in from `taoensso.encore`. Fulcro now provides its own
   faithful implementations so that the runtime/data layer has no dependency on encore at
   all (which also means babashka, whose built-in encore is partial/broken, needs no shim).

   Nothing in here has anything to do with encore.

   The `if-let`/`when-let` macros superset `clojure.core`: they support **multiple bindings**
   and unconditional `:let` bindings. `catching` is a terse cross-platform `try/catch`.
   `map-vals`/`remove-vals`/`dissoc-in`/`nnil`/`compiling-cljs?` mirror the encore fns of the
   same names."
  (:refer-clojure :exclude [if-let when-let])
  #?(:bb (:require [taoensso.timbre])))

;; ---------------------------------------------------------------------------
;; Macros. Wrapped in #?(:clj ...) (the standard cljc-macro pattern: macros are only ever
;; expanded on the Clojure side, including when the cljs compiler reads this file). Babashka
;; matches :clj (there is no :bb branch), so it gets them too.
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro if-let
     "Supersets `clojure.core/if-let`: supports multiple bindings and unconditional `:let`
      bindings. Each non-`:let` binding must be truthy to proceed; otherwise `else` is evaluated.

      ```
      (if-let [x       (find-x)
               :let [y (derive y x)]
               z       (find-z x y)]
        (use x y z)
        :none)
      ```"
     {:style/indent 1}
     ([bindings then] `(if-let ~bindings ~then nil))
     ([bindings then else]
      (let [s (seq bindings)]
        (if s
          (let [[b1 b2 & bnext] s]
            (if (= b1 :let)
              `(let ~b2 (if-let ~(vec bnext) ~then ~else))
              `(let [t# ~b2]
                 (if t#
                   (let [~b1 t#]
                     (if-let ~(vec bnext) ~then ~else))
                   ~else))))
          then)))))

#?(:clj
   (defmacro when-let
     "Supersets `clojure.core/when-let`: supports multiple bindings and unconditional `:let`
      bindings (see `if-let`). Evaluates `body` only when every non-`:let` binding is truthy."
     {:style/indent 1}
     [bindings & body]
     `(if-let ~bindings (do ~@body))))

#?(:clj
   (defmacro catching
     "Terse, cross-platform `try`/`catch`/`finally`. The catch-all clause catches `Throwable`
      under Clojure/babashka and `:default` under ClojureScript.

      Arities (mirroring encore):
      - `[try-expr]`                                             - returns nil on any error
      - `[try-expr error-sym catch-expr]`
      - `[try-expr error-sym catch-expr finally-expr]`
      - `[try-expr error-type error-sym catch-expr finally-expr]` - catch a specific type"
     {:style/indent 0}
     ([try-expr] `(catching ~try-expr e# nil))
     ([try-expr error-sym catch-expr]
      `(try ~try-expr (catch ~(if (:ns &env) :default 'Throwable) ~error-sym ~catch-expr)))
     ([try-expr error-sym catch-expr finally-expr]
      `(try ~try-expr (catch ~(if (:ns &env) :default 'Throwable) ~error-sym ~catch-expr) (finally ~finally-expr)))
     ([try-expr error-type error-sym catch-expr finally-expr]
      `(try ~try-expr (catch ~error-type ~error-sym ~catch-expr) (finally ~finally-expr)))))

;; ---------------------------------------------------------------------------
;; Functions.
;; ---------------------------------------------------------------------------

(defn map-vals
  "Returns `m` with `(f v)` applied to each value. Returns `{}` when `m` is nil."
  [f m]
  (if (nil? m)
    {}
    (persistent! (reduce-kv (fn [acc k v] (assoc! acc k (f v))) (transient {}) m))))

(defn remove-vals
  "Returns `m`, removing every entry whose value satisfies `pred`. Returns `{}` when `m` is nil."
  [pred m]
  (if (nil? m)
    {}
    (persistent! (reduce-kv (fn [acc k v] (if (pred v) acc (assoc! acc k v))) (transient {}) m))))

(defn dissoc-in
  "Dissociates within a nested map structure.
     `[m ks]`               - dissoc the leaf key of path `ks`
     `[m ks dissoc-k]`      - dissoc `dissoc-k` from the map at path `ks`
     `[m ks dissoc-k more]` - dissoc `dissoc-k` and each of `more` from the map at path `ks`
   Empty/absent paths are no-ops (the original map is returned)."
  ([m ks]
   (cond
     (empty? ks)      m
     (= 1 (count ks)) (dissoc m (first ks))
     :else            (update-in m (butlast ks) dissoc (last ks))))
  ([m ks dissoc-k]
   (if (empty? ks)
     (dissoc m dissoc-k)
     (update-in m (vec ks) dissoc dissoc-k)))
  ([m ks dissoc-k & more]
   (if (empty? ks)
     (apply dissoc m dissoc-k more)
     (update-in m (vec ks) #(apply dissoc % dissoc-k more)))))

(defn nnil
  "Returns the first non-nil argument, or nil. Note `false` counts as non-nil."
  ([] nil)
  ([x] x)
  ([x y] (if (nil? x) y x))
  ([x y z] (if (nil? x) (if (nil? y) z y) x))
  ([x y z & more]
   (if (nil? x)
     (if (nil? y)
       (if (nil? z)
         (reduce (fn [_ a] (when (some? a) (reduced a))) nil more)
         z)
       y)
     x)))

(defn compiling-cljs?
  "Returns true iff currently generating ClojureScript code (i.e. called from a macro being
   expanded by the cljs compiler). Always false under babashka (it cannot compile cljs) and at
   ClojureScript runtime."
  []
  #?(:clj  (boolean (some-> (find-ns 'cljs.analyzer) (ns-resolve '*cljs-file*) deref))
     :cljs false))

;; ---------------------------------------------------------------------------
;; Babashka-only compatibility shims (unrelated to the utilities above). Loaded only via
;; #?(:bb ...) requires; never under JVM Clojure or ClojureScript.
;; ---------------------------------------------------------------------------

;; React/SSR stubs. `react-interop` aliases its `dom` to the server DOM ns under :clj, but that
;; ns (`dom-server`) uses `definterface`, which SCI cannot load. Under :bb it aliases `dom` to
;; THIS namespace instead so it can load for its data-layer value; these React-only factory fns
;; are never needed in a babashka context and throw if actually called.
#?(:bb
   (do
     (defn- unsupported! [what]
       (throw (ex-info (str what " is not supported under babashka (no React/SSR).") {})))

     (defn create-element [& _] (unsupported! "dom/create-element"))
     (defn convert-props [& _] (unsupported! "dom/convert-props"))
     (defn wrap-form-element [& _] (unsupported! "dom/wrap-form-element"))))

;; babashka's built-in timbre has trace/debug/info/warn/error but is missing `fatal`. Intern an
;; error-level `fatal` so `log/fatal` call sites macro-expand under bb (fatal is used only in
;; last-ditch catch handlers). This ns is required (under #?(:bb ...)) by `lookup`, which loads
;; early, so the macro is present before fatal-using namespaces are expanded.
#?(:bb
   (do
     (defmacro ^:private bb-fatal [& args] `(taoensso.timbre/error ~@args))
     (intern 'taoensso.timbre (with-meta 'fatal {:macro true}) (deref (var bb-fatal)))))
