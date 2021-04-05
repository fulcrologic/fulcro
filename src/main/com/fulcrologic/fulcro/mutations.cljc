(ns com.fulcrologic.fulcro.mutations
  "Mutations are the central mechanism of getting things done in Fulcro. The term mutation refers to two things:

  * The literal data that stands for the operation. These are lists with a single symbol and a map of parameters. In
  earlier version, you had to quote them: `'[(f {:x 1})]`, but Fulcro 3 includes a way to declare them so that they
  auto-quote themselves for convenience. This can be confusing to new users. Remember that a mutation call is nothing
  more than a *submission* of this data via `comp/transact!` (i.e. call `f` with the parameter `{:x 1}`).
  * One or more definitions of what to do when the mutation is requested.

  The former are submitted with `transact!` and can be written like so:

  ```
  ;; The unquote on the parameters is typically needed because you'll use surrounding binding values in them.
  (let [x 3
        some-local-value 42]
    (comp/transact! this `[(f ~{:x x}) (g ~{:y some-local-value})]))
  ;; or, if pre-declared and required:
  (let [x 3
        some-local-value 42]
    (comp/transact! this [(f {:x x}) (g {:y some-local-value})]))
  ```

  This works because a mutation *definition* actually builds a record that response to function calls. This means

  ```
  (defn func [x] (inc x))
  (defmutation f [params] ...)

  ;; A regular function runs when called...
  (func 3)
  ;; => 4

  ;; A mutation simply returns its expression when called:
  (f {:x 1})
  ;; => (f {:x 1})
  ```

  This allows you to embed a mutation expression without quoting in your calls to transact (if desired) or with
  quoting if you have something like a circular reference problem.

  See the Developer's Guide for more information.
  "
  #?(:cljs (:require-macros com.fulcrologic.fulcro.mutations))
  (:require
    #?(:clj [cljs.analyzer :as ana])
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.guardrails.core :refer [>def >defn =>]]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]
    [taoensso.encore :as enc]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as futil]
    [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [clojure.string :as str])
  #?(:clj
     (:import (clojure.lang IFn))))

(>def ::env (s/keys :req-un [:com.fulcrologic.fulcro.application/app]))
(>def ::returning rc/component-class?)

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

(>defn update-errors-on-ui-component!
  "A handler for mutation network results that will place an error, if detected in env, on the data at `ref`.
  Errors are placed at `k` (defaults to `::m/mutation-error`).

  Typically used as part of the construction of a global default result handler for mutations.

  Swaps against app state and returns `env`."
  ([env]
   [::env => ::env]
   (update-errors-on-ui-component! env ::mutation-error))
  ([env k]
   [::env keyword? => ::env]
   (let [{:keys [app state result ref]} env
         remote-error? (ah/app-algorithm app :remote-error?)]
     (when ref
       (swap! state (fn [s]
                      (if (remote-error? (:result env))
                        (assoc-in s (conj ref k) result)
                        (update-in s ref dissoc k)))))
     env)))

(>defn trigger-global-error-action!
  "When there is a `global-error-action` defined on the application, this function will checks for errors in the given
  mutation `env`. If any are found then it will call the global error action function with `env`.

  Typically used as part of the construction of a global default result handler for mutations.

  Always returns `env`."
  [env]
  [::env => ::env]
  (let [{:keys [app result]} env]
    (enc/when-let [global-error-action (ah/app-algorithm app :global-error-action)
                   remote-error?       (ah/app-algorithm app :remote-error?)
                   _                   (remote-error? result)]
      (global-error-action env))
    env))

(>defn dispatch-ok-error-actions!
  "Looks for network mutation result in `env`, checks it against the global definition of remote errors.  If there
  is an error and the mutation has defined an `error-action` section, then it calls it; otherwise, if the mutation
  has an `ok-action` it calls that.

  Typically used as part of the construction of a global default result handler for mutations.

  Returns env."
  [env]
  [::env => ::env]
  (let [{:keys [app dispatch result]} env
        {:keys [ok-action error-action]} dispatch
        remote-error? (ah/app-algorithm app :remote-error?)]
    (if (remote-error? result)
      (when error-action
        (error-action env))
      (when ok-action
        (ok-action env)))
    env))

(>defn rewrite-tempids!
  "Rewrites tempids in state and places a tempid->realid map into env for further use by the mutation actions."
  [env]
  [::env => ::env]
  (let [{:keys [app result]} env
        {:keys [body]} result
        rid->tid (tempid/result->tempid->realid body)]
    (tempid/resolve-tempids! app body)
    (assoc env :tempid->realid rid->tid)))

(>defn integrate-mutation-return-value!
  "If there is a successful result from the remote mutation in `env` this function will merge it with app state
  (if there was a mutation join query), and will also rewrite any tempid remaps that were returned
  in all of the possible locations they might be in both app database and runtime application state (e.g. network queues).

  Typically used as part of the construction of a global default result handler for mutations.

  Returns env."
  [env]
  [::env => ::env]

  (let [{:keys [app state result mutation-ast transmitted-ast]} env
        ;; NOTE: transaction should only be present if the network middleware rewrote the tx, which means
        ;; transaction would be the global-eql-transformed query that was modified by middleware.
        ;; Otherwise, the query we care about is the transmitted AST, since that is what merge mark/sweep should
        ;; work with.
        {:keys [body transaction]} result
        mark-query    (if transmitted-ast
                        (futil/ast->query transmitted-ast)
                        transaction)
        body          (if (and body mark-query)
                        (merge/mark-missing body mark-query)
                        body)
        eql           (or transaction
                        (and mutation-ast [(eql/ast->expr mutation-ast true)])
                        mark-query)
        remote-error? (ah/app-algorithm app :remote-error?)]
    (when-not (remote-error? result)
      (swap! state merge/merge-mutation-joins eql body))
    env))

(>defn default-result-action!
  "The default Fulcro result action for `defmutation`, which can be overridden when you create your `app/fulcro-app`.

  This function is the following composition of operations from this same namespace:

```
  (-> env
    (update-errors-on-ui-component! ::mutation-error)
    (integrate-mutation-return-value!)
    (trigger-global-error-action!)
    (dispatch-ok-error-actions!))
```

  This function returns `env`, so it can be used as part of the chain in your own definition of a \"default\"
  mutation result action.
  "
  [env]
  [::env => ::env]
  (-> env
    (update-errors-on-ui-component! ::mutation-error)
    (rewrite-tempids!)
    (integrate-mutation-return-value!)
    (trigger-global-error-action!)
    (dispatch-ok-error-actions!)))

(defn mutation-declaration? [expr] (= Mutation (type expr)))

(defn mutation-symbol
  "Return the real symbol (for mutation dispatch) of `mutation`, which can be a symbol (this function is then identity)
   or a mutation-declaration."
  [mutation]
  (if (mutation-declaration? mutation)
    (first (mutation))
    mutation))

(defmulti mutate (fn [env] (-> env :ast :dispatch-key)))

#?(:clj
   (defmacro declare-mutation
     "Define a quote-free interface for using the given `target-symbol` in mutations.
     The declared mutation can be used in lieu of the true mutation symbol
     as a way to prevent circular references while also allowing the shorthand of ns aliasing.

     In IntelliJ, use Resolve-as `def` to get proper IDE integration."
     ([name target-symbol]
      `(def ~name (->Mutation '~target-symbol)))))

#?(:cljs
   (com.fulcrologic.fulcro.mutations/defmutation set-props
     "
     mutation: A convenience helper, generally used 'bit twiddle' the data on a particular database table (using the component's ident).
     Specifically, merge the given `params` into the state of the database object at the component's ident.
     In general, it is recommended this be used for ui-only properties that have no real use outside of the component.
     "
     [params]
     (action [{:keys [state ref]}]
       (when (nil? ref) (log/error "ui/set-props requires component to have an ident."))
       (swap! state update-in ref (fn [st] (merge st params))))))

#?(:cljs
   (com.fulcrologic.fulcro.mutations/defmutation toggle
     "mutation: A helper method that toggles the true/false nature of a component's state by ident.
      Use for local UI data only. Use your own mutations for things that have a good abstract meaning. "
     [{:keys [field]}]
     (action [{:keys [state ref]}]
       (when (nil? ref) (log/error "ui/toggle requires component to have an ident."))
       (swap! state update-in (conj ref field) not))))

(defmethod mutate :default [{:keys [ast]}]
  (log/error "Unknown app state mutation. Have you required the file with your mutations?" (:key ast)))

(defn toggle!
  "Toggle the given boolean `field` on the specified component. It is recommended you use this function only on
  UI-related data (e.g. form checkbox checked status) and write clear top-level transactions for anything more complicated."
  [comp field]
  (rc/transact! comp `[(toggle {:field ~field})] {:compressible? true}))

(defn toggle!!
  "Like toggle!, but synchronously refreshes `comp` and nothing else."
  [comp field]
  (rc/transact!! comp `[(toggle {:field ~field})] {:compressible? true}))

(defn set-value!
  "Set a raw value on the given `field` of a `component`. It is recommended you use this function only on
  UI-related data (e.g. form inputs that are used by the UI, and not persisted data). Changes made via these
  helpers are compressed in the history."
  [component field value]
  (rc/transact! component `[(set-props ~{field value})] {:compressible? true}))

(defn set-value!!
  "Just like set-value!, but synchronously updates `component` and nothing else."
  [component field value]
  (rc/transact!! component `[(set-props ~{field value})] {:compressible? true}))

#?(:cljs
   (defn- ensure-integer
     "Helper for set-integer!, use that instead. It is recommended you use this function only on UI-related
     data (e.g. data that is used for display purposes) and write clear top-level transactions for anything else."
     [v]
     (let [rv (js/parseInt v)]
       (if (js/isNaN rv) 0 rv)))
   :clj
   (defn- ensure-integer [v] (Integer/parseInt v)))

(defn set-integer!
  "Set the given integer on the given `field` of a `component`. Allows same parameters as `set-string!`.

   It is recommended you use this function only on UI-related data (e.g. data that is used for display purposes)
   and write clear top-level transactions for anything else. Calls to this are compressed in history."
  [component field & {:keys [event value]}]
  (assert (and (or event value) (not (and event value))) "Supply either :event or :value")
  (let [value (ensure-integer (if event (evt/target-value event) value))]
    (set-value! component field value)))

(defn set-integer!!
  "Just like set-integer!, but synchronously refreshes `component` and nothing else."
  [component field & {:keys [event value]}]
  (assert (and (or event value) (not (and event value))) "Supply either :event or :value")
  (let [value (ensure-integer (if event (evt/target-value event) value))]
    (set-value!! component field value)))

#?(:cljs
   (defn- ensure-double [v]
     (let [rv (js/parseFloat v)]
       (if (js/isNaN rv) 0 rv)))
   :clj
   (defn- ensure-double [v] (Double/parseDouble v)))

(defn set-double!
  "Set the given double on the given `field` of a `component`. Allows same parameters as `set-string!`.

   It is recommended you use this function only on UI-related data (e.g. data that is used for display purposes)
   and write clear top-level transactions for anything else. Calls to this are compressed in history."
  [component field & {:keys [event value]}]
  (assert (and (or event value) (not (and event value))) "Supply either :event or :value")
  (let [value (ensure-double (if event (evt/target-value event) value))]
    (set-value! component field value)))

(defn set-double!!
  "Just like set-double!, but synchronously refreshes `component` and nothing else."
  [component field & {:keys [event value]}]
  (assert (and (or event value) (not (and event value))) "Supply either :event or :value")
  (let [value (ensure-double (if event (evt/target-value event) value))]
    (set-value!! component field value)))

(defn set-string!
  "Set a string on the given `field` of a `component`. The string can be literal via named parameter `:value` or
  can be auto-extracted from a UI event using the named parameter `:event`

  Examples

  ```
  (set-string! this :ui/name :value \"Hello\") ; set from literal (or var)
  (set-string! this :ui/name :event evt) ; extract from UI event target value
  ```

  It is recommended you use this function only on UI-related
  data (e.g. data that is used for display purposes) and write clear top-level transactions for anything else.
  Calls to this are compressed in history."
  [component field & {:keys [event value]}]
  (assert (and (or event value) (not (and event value))) "Supply either :event or :value")
  (let [value (if event (evt/target-value event) value)]
    (set-value! component field value)))

(defn set-string!!
  "Just like set-string!, but synchronously refreshes `component` and nothing else."
  [component field & {:keys [event value]}]
  (assert (and (or event value) (not (and event value))) "Supply either :event or :value")
  (let [value (if event (evt/target-value event) value)]
    (set-value!! component field value)))

(defn returning
  "Indicate the the remote operation will return a value of the given component type.

  `env` - The env of the mutation
  `class` - A component class that represents the return type.  You may supply a fully-qualified symbol instead of the
  actual class, and this method will look up the class for you (useful to avoid circular references).

  Returns an update `env`, and is a valid return value from mutation remote sections."
  [env class]
  (let [class (if (or (keyword? class) (symbol? class))
                (rc/registry-key->class class)
                class)]
    (let [{:keys [state ast]} env
          {:keys [key params query]} ast]
      (let [updated-query (cond-> (rc/get-query class @state)
                            query (vary-meta #(merge (meta query) %)))]
        (assoc env :ast (eql/query->ast1 [{(list key params) updated-query}]))))))

(defn with-target
  "Set's a target for the return value from the mutation to be merged into. This can be combined with returning to define
  a path to insert the new entry.

  `env` - The mutation env (you can thread together `returning` and `with-target`)
  `target` - A vector path, or any special target defined in `data-targeting` such as `append-to`.

  Returns an updated env (which is a valid return value from remote sections of mutations).
  "
  [{:keys [ast] :as env} target]
  (let [{:keys [key params query]} ast
        targeted-query (if query
                         (vary-meta query assoc ::targeting/target target)
                         (with-meta '[*] {::targeting/target target}))]
    (assoc env :ast (eql/query->ast1 [{(list key params) targeted-query}]))))

(defn with-params
  "Modify an AST containing a single mutation, changing it's parameters to those given as an argument. Overwrites
   any existing params of the mutation.

   `env` - the mutation environment
   `params` - A new map to use as the mutations parameters

   Returns an updated `env`, which can be used as the return value from a remote section of a mutation."
  [env params]
  (assoc-in env [:ast :params] params))

(>defn with-response-type
  "Modify the AST in env so that the request is sent such that an alternate low-level XHRIO response type is used.
  Only works with HTTP remotes. See goog.net.XhrIO.  Supported response types are :default, :array-buffer,
  :text, and :document."
  [env response-type]
  [::env :com.fulcrologic.fulcro.networking.http-remote/response-type => ::env]
  (assoc-in env [:ast :params :com.fulcrologic.fulcro.networking.http-remote/response-type] response-type))

(>defn with-server-side-mutation
  [env mutation-symbol]
  [::env qualified-symbol? => ::env]
  "Alter the remote mutation name to be `mutation-symbol` instead of the client-side's mutation name."
  (update env :ast assoc :key mutation-symbol :dispatch-key mutation-symbol))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEFMUTATION MACRO: This code could live in another ns, but then hot code reload won't work right on the macro itself.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (s/def ::handler (s/cat
                      :handler-name symbol?
                      :handler-args (fn [a] (and (vector? a) (= 1 (count a))))
                      :handler-body (s/+ (constantly true)))))

#?(:clj
   (s/def ::mutation-args (s/cat
                            :sym symbol?
                            :doc (s/? string?)
                            :arglist (fn [a] (and (vector? a) (= 1 (count a))))
                            :sections (s/* (s/or :handler ::handler)))))

#?(:clj
   (defn defmutation* [macro-env args]
     (let [conform!       (fn [element spec value]
                            (when-not (s/valid? spec value)
                              (throw (ana/error macro-env (str "Syntax error in " element ": " (s/explain-str spec value)))))
                            (s/conform spec value))
           {:keys [sym doc arglist sections]} (conform! "defmutation" ::mutation-args args)
           fqsym          (if (namespace sym)
                            sym
                            (symbol (name (ns-name *ns*)) (name sym)))
           handlers       (reduce (fn [acc [_ {:keys [handler-name handler-args handler-body]}]]
                                    (let [action? (str/ends-with? (str handler-name) "action")]
                                      (into acc
                                        (if action?
                                          [(keyword (name handler-name)) `(fn ~handler-name ~handler-args
                                                                            (binding [com.fulcrologic.fulcro.components/*after-render* true]
                                                                              ~@handler-body)
                                                                            nil)]
                                          [(keyword (name handler-name)) `(fn ~handler-name ~handler-args ~@handler-body)]))))
                            []
                            sections)
           ks             (into #{} (filter keyword?) handlers)
           result-action? (contains? ks :result-action)
           env-symbol     'fulcro-mutation-env-symbol
           method-map     (if result-action?
                            `{~(first handlers) ~@(rest handlers)}
                            `{~(first handlers) ~@(rest handlers)
                              :result-action    (fn [~'env]
                                                  (binding [com.fulcrologic.fulcro.components/*after-render* true]
                                                    (when-let [~'default-action (ah/app-algorithm (:app ~'env) :default-result-action!)]
                                                      (~'default-action ~'env))))})
           doc            (or doc "")
           multimethod    `(defmethod com.fulcrologic.fulcro.mutations/mutate '~fqsym [~env-symbol]
                             (let [~(first arglist) (-> ~env-symbol :ast :params)]
                               ~method-map))]
       (if (= fqsym sym)
         multimethod
         `(do
            (def ~(with-meta sym {:doc doc}) (com.fulcrologic.fulcro.mutations/->Mutation '~fqsym))
            ~multimethod)))))

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

     This allows mutations to behave as data in transactions without needing quoting.

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

     IMPORTANT: You can fully-qualify a mutation's symbol when declaring it to force it into a custom namespace,
     but this is highly discouraged and will require quoting when used in a mutation.
     "
       :arglists
       '([sym docstring? arglist handlers])} defmutation
     [& args]
     (defmutation* &env args)))
