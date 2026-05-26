(ns com.fulcrologic.fulcro.algorithms.bb-support
  "Babashka-only support shim. **Loaded only via `#?(:bb ...)` requires — never under JVM
   Clojure or ClojureScript.**

   Babashka ships a *precompiled, partial* `taoensso.encore` that shadows the encore jar
   (placing the full jar on the classpath does not help). Two problems with that built-in:

   1. It is **missing** plain fns Fulcro uses: `map-vals`, `remove-vals`, `dissoc-in`,
      `compiling-cljs?`.
   2. Its `if-let`/`when-let`/`catching` exist but **do not work** as macro calls through an
      `:as enc` alias.

   Rather than de-encore Fulcro's proven `:clj`/`:cljs` source, this namespace `intern`s
   faithful re-implementations into `taoensso.encore` at load time (interning overrides the
   broken built-ins), so the existing `enc/...` call sites work unchanged under babashka.

   The macros are intentionally named `bb-*` (not `if-let`/`when-let`) because babashka's SCI
   special-cases those core names; a `bb-`-prefixed macro avoids that collision, and its value
   is then interned into `taoensso.encore` under the real name.

   Requiring this namespace installs the patch; it is required (under `#?(:bb ...)`) by
   `com.fulcrologic.fulcro.algorithms.lookup`, which every encore-macro-using namespace
   requires, so the patch is present before those namespaces are macro-expanded. The patched
   fns/macros match encore's documented contracts."
  (:require
    [taoensso.encore]
    [taoensso.timbre]))

;; ---------------------------------------------------------------------------
;; Functions
;; ---------------------------------------------------------------------------

(defn map-vals
  "Returns `?map` with `(val-fn v)` applied to each value. Matches `taoensso.encore/map-vals`."
  [val-fn m]
  (when m
    (persistent! (reduce-kv (fn [acc k v] (assoc! acc k (val-fn v))) (transient {}) m))))

(defn remove-vals
  "Returns `?map`, removing keys for which `(val-pred v)` is truthy. Matches
   `taoensso.encore/remove-vals`."
  [val-pred m]
  (when m
    (persistent! (reduce-kv (fn [acc k v] (if (val-pred v) acc (assoc! acc k v))) (transient {}) m))))

(defn dissoc-in
  "Matches `taoensso.encore/dissoc-in` for the arities Fulcro uses:
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

(defn compiling-cljs?
  "Babashka never compiles ClojureScript, so this is always false."
  []
  false)

;; ---------------------------------------------------------------------------
;; React/SSR stubs.
;; `react-interop` (required transitively by `application`) aliases its `dom` to the
;; server DOM ns under :clj, but that ns (`dom-server`) uses `definterface`, which SCI does
;; not support, so it can never load under bb. Under :bb, react-interop aliases `dom` to THIS
;; namespace instead, so it can load for its data-layer value; these React-only factory fns
;; are never needed in a babashka context and throw if actually called.
;; ---------------------------------------------------------------------------

(defn- unsupported! [what]
  (throw (ex-info (str what " is not supported under babashka (no React/SSR).") {})))

(defn create-element   [& _] (unsupported! "dom/create-element"))
(defn convert-props    [& _] (unsupported! "dom/convert-props"))
(defn wrap-form-element [& _] (unsupported! "dom/wrap-form-element"))

;; ---------------------------------------------------------------------------
;; Macros (faithful to encore: multiple bindings + :let; cross-platform catch).
;; Named bb-* to avoid SCI's special-casing of if-let/when-let.
;; ---------------------------------------------------------------------------

(defmacro bb-if-let
  "Supersets `clojure.core/if-let`: multiple bindings + unconditional `:let`. Interned as
   `taoensso.encore/if-let`."
  ([bindings then] `(bb-if-let ~bindings ~then nil))
  ([bindings then else]
   (let [s (seq bindings)]
     (if s
       (let [[b1 b2 & bnext] s]
         (if (= b1 :let)
           `(let ~b2 (bb-if-let ~(vec bnext) ~then ~else))
           `(let [b2# ~b2]
              (if b2#
                (let [~b1 b2#]
                  (bb-if-let ~(vec bnext) ~then ~else))
                ~else))))
       then))))

(defmacro bb-when-let
  "Supersets `clojure.core/when-let`: multiple bindings + unconditional `:let`. Interned as
   `taoensso.encore/when-let`."
  [bindings & body]
  `(bb-if-let ~bindings (do ~@body)))

(defmacro bb-catching
  "Terse cross-platform try/catch. Interned as `taoensso.encore/catching` (Throwable for the
   `:all` case), matching the arities Fulcro uses."
  ([expr] `(try ~expr (catch Throwable ~'_ nil)))
  ([error-type expr] `(try ~expr (catch ~error-type ~'_ nil)))
  ([try-expr error-sym catch-expr]
   `(try ~try-expr (catch Throwable ~error-sym ~catch-expr)))
  ([try-expr error-sym catch-expr finally-expr]
   `(try ~try-expr (catch Throwable ~error-sym ~catch-expr) (finally ~finally-expr)))
  ([try-expr error-type error-sym catch-expr finally-expr]
   `(try ~try-expr (catch ~error-type ~error-sym ~catch-expr) (finally ~finally-expr))))

;; babashka's built-in timbre has trace/debug/info/warn/error but is missing `fatal`. Degrade
;; it to error-level logging (fatal is used only in last-ditch catch handlers).
(defmacro bb-fatal
  "babashka stub for `taoensso.timbre/fatal` (missing in bb's built-in timbre); logs at error level."
  [& args]
  `(taoensso.timbre/error ~@args))

;; ---------------------------------------------------------------------------
;; Install into taoensso.encore / taoensso.timbre (override the broken/absent built-ins).
;; ---------------------------------------------------------------------------

(defn- install! []
  (doseq [[target sym src-var macro?] [['taoensso.encore 'map-vals        (var map-vals)        false]
                                       ['taoensso.encore 'remove-vals     (var remove-vals)     false]
                                       ['taoensso.encore 'dissoc-in       (var dissoc-in)       false]
                                       ['taoensso.encore 'compiling-cljs? (var compiling-cljs?) false]
                                       ['taoensso.encore 'if-let          (var bb-if-let)       true]
                                       ['taoensso.encore 'when-let        (var bb-when-let)     true]
                                       ['taoensso.encore 'catching        (var bb-catching)     true]
                                       ['taoensso.timbre 'fatal           (var bb-fatal)        true]]]
    (intern target (with-meta sym {:macro macro?}) (deref src-var))))

(install!)
