(ns com.fulcrologic.fulcro.algorithms.macro-support
  "React-agnostic compile-time helpers extracted from the `defsc` macro. These functions run at macroexpansion
   time (CLJ only) and are used to validate and rewrite the query, ident, and initial-state options of a
   stateful component definition.

   Errors are reported via an injectable error function so that this namespace does not depend on
   `cljs.analyzer`. By default `default-macro-error` returns an `ex-info`. The `defsc` macro rebinds
   `*macro-error*` to `cljs.analyzer/error` so that CLJS compilation produces nice analyzer errors."
  (:require
    [clojure.set :as set]
    [clojure.walk :refer [prewalk]]
    [clojure.spec.alpha :as s]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]))

(defn default-macro-error
  "Default macro error function. Returns (does not throw) an `ex-info` describing `msg`, including line/column
   information from `env` when available."
  [env msg]
  (ex-info msg (merge {:tag :fulcro.macro/error} (when (map? env) (select-keys env [:line :column])))))

(def ^:dynamic *macro-error*
  "The function used to construct (not throw) an error from `(env msg)`. Rebound by `defsc` so that CLJS
   compilation produces analyzer errors. Defaults to `default-macro-error`."
  default-macro-error)

(defn macro-error
  "Returns an exception (via `*macro-error*`) for the given macro `env` and message `msg`. Does not throw."
  [env msg]
  (*macro-error* env msg))

(defn is-link?
  "Returns true if the given query element is a link query like [:x '_]."
  [query-element]
  (and (vector? query-element)
    (keyword? (first query-element))
    ; need the double-quote because when in a macro we'll get the literal quote.
    (#{''_ '_} (second query-element))))

(defn -legal-keys
  "Find the legal keys in a query. NOTE: This is at compile time, so the get-query calls are still embedded (thus cannot
   use the AST)"
  [query]
  (letfn [(keeper [ele]
            (cond
              (list? ele) (recur (first ele))
              (keyword? ele) ele
              (is-link? ele) (first ele)
              (and (map? ele) (keyword? (ffirst ele))) (ffirst ele)
              (and (map? ele) (is-link? (ffirst ele))) (first (ffirst ele))
              :else nil))]
    (set (keep keeper query))))

(defn children-by-prop
  "Part of Defsc macro implementation. Calculates a map from join key to class (symbol)."
  [query]
  (into {}
    (keep #(if (and (map? %) (or (is-link? (ffirst %)) (keyword? (ffirst %))))
             (let [k   (if (vector? (ffirst %))
                         (first (ffirst %))
                         (ffirst %))
                   cls (-> % first second second)]
               [k cls])
             nil) query)))

(defn replace-and-validate-fn
  "Replace the first sym in a list (the function name) with the given symbol.

   env - the macro &env
   sym - The symbol that the lambda should have
   external-args - A sequence of arguments that the user should not include, but that you want to be inserted in the external-args by this function.
   user-arity - The number of external-args the user should supply (resulting user-arity is (count external-args) + user-arity).
   fn-form - The form to rewrite
   sym - The symbol to report in the error message (in case the rewrite uses a different target that the user knows)."
  ([env sym external-args user-arity fn-form] (replace-and-validate-fn env sym external-args user-arity fn-form sym))
  ([env sym external-args user-arity fn-form user-known-sym]
   (when-not (<= user-arity (count (second fn-form)))
     (throw (macro-error (merge env (meta fn-form)) (str "Invalid arity for " user-known-sym ". Expected " user-arity " or more."))))
   (let [user-args    (second fn-form)
         updated-args (into (vec (or external-args [])) user-args)
         body-forms   (drop 2 fn-form)]
     (->> body-forms
       (cons updated-args)
       (cons sym)
       (cons 'fn)))))

(defn component-query [query-part]
  (and (list? query-part)
    (symbol? (first query-part))
    (= "get-query" (name (first query-part)))
    query-part))

(defn compile-time-query->checkable
  "Try to simplify the compile-time query (as seen by the macro)
   to something that EQL can check (`(get-query ..)` => a made-up vector).
   Returns nil if this is not possible."
  [query]
  (try
    (prewalk
      (fn [form]
        (cond
          (component-query form)
          [(keyword (str "subquery-of-" (some-> form second name)))]

          ;; Replace idents with idents that contain only keywords, so syms don't trip us up
          (and (vector? form) (= 2 (count form)))
          (mapv #(if (symbol? %) :placeholder %) form)

          (symbol? form)
          (throw (ex-info "Cannot proceed, the query contains a symbol" {:sym form}))

          :else
          form))
      query)
    (catch Throwable _
      nil)))

(defn check-query-looks-valid [err-env comp-class compile-time-query]
  (let [checkable-query (compile-time-query->checkable compile-time-query)]
    (when (false? (some->> checkable-query (s/valid? ::eql/query)))
      (let [{:clojure.spec.alpha/keys [problems]} (s/explain-data ::eql/query checkable-query)
            {:keys [in]} (first problems)]
        (when (vector? in)
          (throw (macro-error err-env (str "The element '" (get-in compile-time-query in) "' of the query of " comp-class " is not valid EQL"))))))))

(defn build-query-forms
  "Validate that the property destructuring and query make sense with each other."
  [env class thissym propargs {:keys [template method]}]
  (cond
    template
    (do
      (assert (or (symbol? propargs) (map? propargs)) "Property args must be a symbol or destructuring expression.")
      (let [to-keyword            (fn [s] (cond
                                            (nil? s) nil
                                            (keyword? s) s
                                            :otherwise (let [nspc (namespace s)
                                                             nm   (name s)]
                                                         (keyword nspc nm))))
            destructured-keywords (when (map? propargs) (util/destructured-keys propargs))
            queried-keywords      (-legal-keys template)
            has-wildcard?         (some #{'*} template)
            to-sym                (fn [k] (symbol (namespace k) (name k)))
            illegal-syms          (mapv to-sym (set/difference destructured-keywords queried-keywords))
            err-env               (merge env (meta template))]
        (when-let [child-query (some component-query template)]
          (throw (macro-error err-env (str "defsc " class ": `get-query` calls in :query can only be inside a join value, i.e. `{:some/key " child-query "}`"))))
        (when (and (not has-wildcard?) (seq illegal-syms))
          (throw (macro-error err-env (str "defsc " class ": " illegal-syms " was destructured in props, but does not appear in the :query!"))))
        `(~'fn ~'query* [~thissym] ~template)))
    method
    (replace-and-validate-fn env 'query* [thissym] 0 method)))

(defn build-ident
  "Builds the ident form. If ident is a vector, then it generates the function and validates that the ID is
   in the query. Otherwise, if ident is of the form (ident [this props] ...) it simply generates the correct
   entry in defsc without error checking."
  [env thissym propsarg {:keys [method template keyword]} is-legal-key?]
  (cond
    keyword (if (is-legal-key? keyword)
              `(~'fn ~'ident* [~'_ ~'props] [~keyword (~keyword ~'props)])
              (throw (macro-error (merge env (meta template)) (str "The table/id " keyword " of :ident does not appear in your :query"))))
    method (replace-and-validate-fn env 'ident* [thissym propsarg] 0 method)
    template (let [table   (first template)
                   id-prop (or (second template) :db/id)]
               (cond
                 (nil? table) (throw (macro-error (merge env (meta template)) "TABLE part of ident template was nil"))
                 (not (is-legal-key? id-prop)) (throw (macro-error (merge env (meta template)) (str "The ID property " id-prop " of :ident does not appear in your :query")))
                 :otherwise `(~'fn ~'ident* [~'this ~'props] [~table (~id-prop ~'props)])))))

(defn build-and-validate-initial-state-map [env sym initial-state legal-keys children-by-query-key]
  (let [env           (merge env (meta initial-state))
        join-keys     (set (keys children-by-query-key))
        init-keys     (set (keys initial-state))
        illegal-keys  (if (set? legal-keys) (set/difference init-keys legal-keys) #{})
        is-child?     (fn [k] (contains? join-keys k))
        param-expr    (fn [v]
                        (if-let [kw (and (keyword? v) (= "param" (namespace v))
                                      (keyword (name v)))]
                          `(~kw ~'params)
                          v))
        parameterized (fn [init-map] (into {} (map (fn [[k v]] (if-let [expr (param-expr v)] [k expr] [k v])) init-map)))
        child-state   (fn [k]
                        (let [state-params    (get initial-state k)
                              to-one?         (map? state-params)
                              to-many?        (and (vector? state-params) (every? map? state-params))
                              code?           (list? state-params)
                              from-parameter? (and (keyword? state-params) (= "param" (namespace state-params)))
                              child-class     (get children-by-query-key k)]
                          (when code?
                            (throw (macro-error env (str "defsc " sym ": Illegal parameters to :initial-state " state-params ". Use a lambda if you want to write code for initial state. Template mode for initial state requires simple maps (or vectors of maps) as parameters to children. See Developer's Guide."))))
                          (cond
                            (not (or from-parameter? to-many? to-one?)) (throw (macro-error env (str "Initial value for a child (" k ") must be a map or vector of maps!")))
                            to-one? `(com.fulcrologic.fulcro.components/get-initial-state ~child-class ~(parameterized state-params))
                            to-many? (mapv (fn [params]
                                             `(com.fulcrologic.fulcro.components/get-initial-state ~child-class ~(parameterized params)))
                                       state-params)
                            from-parameter? `(com.fulcrologic.fulcro.components/get-initial-state ~child-class ~(param-expr state-params))
                            :otherwise nil)))
        kv-pairs      (map (fn [k]
                             [k (if (is-child? k)
                                  (child-state k)
                                  (param-expr (get initial-state k)))]) init-keys)
        state-map     (into {} kv-pairs)]
    (when (seq illegal-keys)
      (throw (macro-error env (str "Initial state includes keys " illegal-keys ", but they are not in your query."))))
    `(~'fn ~'build-initial-state* [~'params] (com.fulcrologic.fulcro.raw.components/make-state-map ~initial-state ~children-by-query-key ~'params))))

(defn build-raw-initial-state
  "Given an initial state form that is a list (function-form), simple copy it into the form needed by defsc."
  [env method]
  (replace-and-validate-fn env 'build-raw-initial-state* [] 1 method))

(defn build-initial-state [env sym {:keys [template method]} legal-keys query-template-or-method]
  (when (and template (contains? query-template-or-method :method))
    (throw (macro-error (merge env (meta template)) (str "When query is a method, initial state MUST be as well."))))
  (cond
    method (build-raw-initial-state env method)
    template (let [query    (:template query-template-or-method)
                   children (or (children-by-prop query) {})]
               (build-and-validate-initial-state-map env sym template legal-keys children))))

(s/def ::ident (s/or :template (s/and vector? #(= 2 (count %))) :method list? :keyword keyword?))
;; NOTE: We cannot reuse ::eql/query because we have the raw input *form* inside a macro,
;; not the actual *data* that will be there at runtime (i.e. it may contain raw fn calls etc.)
(s/def ::query (s/or :template vector? :method list?))
(s/def ::initial-state (s/or :template map? :method list?))
(s/def ::options (s/keys :opt-un [::query
                                  ::ident
                                  ::initial-state]))

(s/def ::args (s/cat
                :sym symbol?
                :doc (s/? string?)
                :arglist (s/and vector? #(<= 2 (count %) 5))
                :options (s/? map?)
                :body (s/* any?)))
