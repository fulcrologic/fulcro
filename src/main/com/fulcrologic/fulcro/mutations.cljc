(ns com.fulcrologic.fulcro.mutations
  #?(:cljs (:require-macros com.fulcrologic.fulcro.mutations))
  (:require
    #?(:clj com.fulcrologic.fulcro.macros.defmutation))
  #?(:clj
     (:import (clojure.lang IFn))))

#?(:clj
   (deftype Mutation [sym]
     IFn
     (invoke [this]
       (this {}))
     (invoke [this args]
       (list sym args)))
   :cljs
   (deftype Mutation [sym]
     IFn
     (-invoke [this]
       (this {}))
     (-invoke [this args]
       (list sym args))))

(defn default-result-action [env]
  (let [{:keys [result handlers]} env
        {:keys [ok-action error-action]} handlers
        {:keys [status-code]} result]
    (if (= 200 status-code)
      (when ok-action
        (ok-action env))
      (when error-action
        (error-action env)))))

(defmulti mutate (fn [env] (-> env :ast :dispatch-key)))

#?(:clj
   (defmacro
     ^{:doc
       "Define a Fulcro mutation.

     The given symbol will be prefixed with the namespace of the current namespace, and if you use a simple symbol it
     will also be def'd into a name that when used as a function will simply resolve to that function call as data:

     ```
     (defmutation f [p]
       ...)

     (f {:x 1}) => `(f {:x 1})
     ```

     This allows mutations to behave as data in `tx!` without needing quoting.

     Mutations can have any number of handlers. By convention things that contain logic use names that end
     in `action`.  The remote behavior of a mutation is defined by naming a handler after the remote.

     ```
     (defmutation boo
       \"docstring\" [params-map]
       (action [env] ...)
       (my-remote [env] ...)
       (other-remote [env] ...)
       (remote [env] ...))
     ```

     NOTE: Every handler in the defmutation is turned into a lambda, and that lambda will be available in `env` under
     the key `:handlers`. Thus actions and remotes can cross-call (TODO: Make the macro rewrite cross calls so they
     can look like fn calls?):

     ```
     (defmutation boo
       \"docstring\" [params-map]
       (action [env] ...)
       (ok-action [env] ...)
       (result-action [env] ((-> env :handlers :ok-action) env)))
     ```

     This macro normally adds a `:result-action` handler that does normal Fulcro mutation remote result logic unless
     you supply your own.

     Remotes in Fulcro 3 are also lambdas, and are called with an `env` that contains the state as it exists *after*
     the `:action` has run in `state`, but also include the 'before action state' as a map in `:state-before-action`.
     "
       :arglists
       '([sym docstring? arglist handlers])} defmutation
     [& args]
     (com.fulcrologic.fulcro.macros.defmutation/defmutation* &env args)))

#?(:clj
   (defmacro declare-mutation
     "Define a quote-free interface for using the given `target-symbol` in mutations.
     The declared mutation can be used in lieu of the true mutation symbol
     as a way to prevent circular references while also allowing the shorthand of ns aliasing.

     In IntelliJ, use Resolve-as `def` to get proper IDE integration."
     ([name target-symbol]
      `(def ~name (->Mutation '~target-symbol)))))
