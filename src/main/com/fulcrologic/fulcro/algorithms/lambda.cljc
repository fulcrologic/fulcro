(ns com.fulcrologic.fulcro.algorithms.lambda
  "Utilities for working with functions across CLJ/CLJS.

   The primary utility is `->arity-tolerant`, which wraps functions to match
   JavaScript's behavior where extra arguments are silently ignored. This is
   essential for headless testing in CLJ where event handlers may have different
   arities than the synthetic events passed to them.

   In CLJS, functions already tolerate extra arguments, so `->arity-tolerant`
   simply returns the function unchanged. In CLJ, it uses reflection (once at
   wrap time) to discover the function's supported arities and creates a wrapper
   that dispatches to the best matching arity.")

#?(:clj
   (defn fn-arities
     "Discover the arities of a function via reflection.

      Returns a map with:
      - :variadic? - true if the function accepts variable arguments
      - :arities   - set of supported fixed arities (when not variadic)
      - :min-arity - minimum required args for variadic functions

      Example:
      ```clojure
      (fn-arities (fn [x] x))           ;; => {:variadic? false :arities #{1}}
      (fn-arities (fn [x & more] x))    ;; => {:variadic? true :min-arity 1}
      (fn-arities (fn ([x] x) ([x y] y))) ;; => {:variadic? false :arities #{1 2}}
      ```"
     [f]
     (if (instance? clojure.lang.RestFn f)
       {:variadic? true
        :min-arity (.getRequiredArity ^clojure.lang.RestFn f)}
       {:variadic? false
        :arities   (->> (.getDeclaredMethods (class f))
                     (filter #(= "invoke" (.getName ^java.lang.reflect.Method %)))
                     (map #(count (.getParameterTypes ^java.lang.reflect.Method %)))
                     set)})))

(defn ->arity-tolerant
  "Wrap a function to tolerate extra arguments (matching JS/CLJS behavior).

   In CLJS, returns f unchanged since JavaScript already ignores extra args.
   In CLJ, uses reflection once at wrap time to discover the function's
   supported arities, then returns a wrapper that:
   - For variadic functions: passes through unchanged
   - For fixed-arity functions: selects the best matching arity (<= arg count)
     and drops extra arguments

   Returns nil if f is nil.

   Example:
   ```clojure
   (def handler (->arity-tolerant (fn [x] (println x))))

   ;; In CLJ, these all work:
   (handler \"event\")                    ;; prints \"event\"
   (handler \"event\" :extra :ignored)    ;; prints \"event\", extras dropped

   ;; Multi-arity functions select best match:
   (def multi (->arity-tolerant (fn ([x] :one) ([x y] :two))))
   (multi 1)         ;; => :one
   (multi 1 2)       ;; => :two
   (multi 1 2 3 4)   ;; => :two (uses arity-2, drops extras)
   ```"
  [f]
  #?(:cljs f
     :clj
     (if (nil? f)
       nil
       (let [{:keys [variadic? arities]} (fn-arities f)]
         (if variadic?
           f
           (let [sorted-arities (vec (sort > arities))]
             (fn [& args]
               (let [n     (count args)
                     arity (first (filter #(<= % n) sorted-arities))]
                 (if arity
                   (apply f (take arity args))
                   (throw (ex-info "No suitable arity for function"
                            {:arg-count         n
                             :available-arities arities})))))))))))
