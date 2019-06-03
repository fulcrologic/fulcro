(ns com.fulcrologic.fulcro.macros.defsc
  (:require
    [cljs.analyzer :as ana]
    [clojure.walk :refer [prewalk]]
    [clojure.spec.alpha :as s]
    [clojure.set :as set]
    [taoensso.timbre :as log]))

(defn cljs? [env]
  (boolean (:ns env)))

(defn is-link?
  "Returns true if the given query element is a link query like [:x '_]."
  [query-element] (and (vector? query-element)
                    (keyword? (first query-element))
                    ; need the double-quote because when in a macro we'll get the literal quote.
                    (#{''_ '_} (second query-element))))

(defn -legal-keys
  "PRIVATE. Find the legal keys in a query. NOTE: This is at compile time, so the get-query calls are still embedded (thus cannot
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

(defn- children-by-prop
  [query]
  (into {}
    (keep #(if (and (map? %) (or (is-link? (ffirst %)) (keyword? (ffirst %))))
             (let [k   (if (vector? (ffirst %))
                         (first (ffirst %))
                         (ffirst %))
                   cls (-> % first second second)]
               [k cls])
             nil) query)))

(defn- replace-and-validate-fn
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
     (throw (ana/error (merge env (meta fn-form)) (str "Invalid arity for " user-known-sym ". Expected " user-arity " or more."))))
   (let [user-args    (second fn-form)
         updated-args (into (vec (or external-args [])) user-args)
         body-forms   (drop 2 fn-form)]
     (->> body-forms
       (cons updated-args)
       (cons sym)
       (cons 'fn)))))

(defn destructured-keys [m]
  (let [regular-destructurings (reduce
                                 (fn [acc k]
                                   (if (and (keyword? k) (= "keys" (name k)))
                                     (let [simple-syms (get m k)
                                           source-keys (into #{} (map (fn [s]
                                                                        (if (and (keyword? s) (namespace s))
                                                                          s
                                                                          (keyword (namespace k) (str s))))) simple-syms)]
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

(defn- build-query-forms
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
            destructured-keywords (when (map? propargs) (destructured-keys propargs))
            queried-keywords      (-legal-keys template)
            has-wildcard?         (some #{'*} template)
            to-sym                (fn [k] (symbol (namespace k) (name k)))
            illegal-syms          (mapv to-sym (set/difference destructured-keywords queried-keywords))]
        (when (and (not has-wildcard?) (seq illegal-syms))
          (throw (ana/error (merge env (meta template)) (str "defsc " class ": " illegal-syms " was destructured in props, but does not appear in the :query!"))))
        `(~'fn ~'query* [~thissym] ~template)))
    method
    (replace-and-validate-fn env 'query* [thissym] 0 method)))

(defn- build-ident
  "Builds the ident form. If ident is a vector, then it generates the function and validates that the ID is
  in the query. Otherwise, if ident is of the form (ident [this props] ...) it simply generates the correct
  entry in defui without error checking."
  [env thissym propsarg {:keys [method template keyword]} is-legal-key?]
  (cond
    keyword (if (is-legal-key? keyword)
              `(~'fn ~'ident* [~'_ ~'props] [~keyword (~keyword ~'props)])
              (throw (ana/error (merge env (meta template)) (str "The table/id " keyword " of :ident does not appear in your :query"))))
    method (replace-and-validate-fn env 'ident* [thissym propsarg] 0 method)
    template (let [table   (first template)
                   id-prop (or (second template) :db/id)]
               (cond
                 (nil? table) (throw (ana/error (merge env (meta template)) "TABLE part of ident template was nil" {}))
                 (not (is-legal-key? id-prop)) (throw (ana/error (merge env (meta template)) (str "The ID property " id-prop " of :ident does not appear in your :query")))
                 :otherwise `(~'fn ~'ident* [~'this ~'props] [~table (~id-prop ~'props)])))))

(defn- build-render [classsym thissym propsym compsym extended-args-sym body]
  (let [computed-bindings (when compsym `[~compsym (com.fulcrologic.fulcro.components/get-computed ~thissym)])
        extended-bindings (when extended-args-sym `[~extended-args-sym (com.fulcrologic.fulcro.components/get-extra-props ~thissym)])
        render-fn         (symbol (str "render-" (name classsym)))]
    `(~'fn ~render-fn [~thissym]
       (com.fulcrologic.fulcro.components/wrapped-render ~thissym
         (fn []
           (let [~propsym (com.fulcrologic.fulcro.components/props ~thissym)
                 ~@computed-bindings
                 ~@extended-bindings]
             ~@body))))))

(defn- build-and-validate-initial-state-map [env sym initial-state legal-keys children-by-query-key]
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
                            (throw (ana/error env (str "defsc " sym ": Illegal parameters to :initial-state " state-params ". Use a lambda if you want to write code for initial state. Template mode for initial state requires simple maps (or vectors of maps) as parameters to children. See Developer's Guide."))))
                          (cond
                            (not (or from-parameter? to-many? to-one?)) (throw (ana/error env (str "Initial value for a child (" k ") must be a map or vector of maps!")))
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
      (throw (ana/error env (str "Initial state includes keys " illegal-keys ", but they are not in your query."))))
    `(~'fn ~'build-initial-state* [~'c ~'params] (com.fulcrologic.fulcro.components/make-state-map ~initial-state ~children-by-query-key ~'params))))

(defn- build-raw-initial-state
  "Given an initial state form that is a list (function-form), simple copy it into the form needed by defui."
  [env thissym method]
  (replace-and-validate-fn env 'build-raw-initial-state* [thissym] 1 method))

(defn- build-initial-state [env sym thissym {:keys [template method]} legal-keys query-template-or-method]
  (when (and template (contains? query-template-or-method :method))
    (throw (ana/error (merge env (meta template)) (str "When query is a method, initial state MUST be as well."))))
  (cond
    method (build-raw-initial-state env thissym method)
    template (let [query    (:template query-template-or-method)
                   children (or (children-by-prop query) {})]
               (build-and-validate-initial-state-map env sym template legal-keys children))))

(s/def ::ident (s/or :template (s/and vector? #(= 2 (count %))) :method list? :keyword keyword?))
(s/def ::query (s/or :template vector? :method list?))
(s/def ::initial-state (s/or :template map? :method list?))
(s/def ::options (s/keys :opt-un [::query
                                                                ::ident
                                                                ::initial-state]))

(s/def ::args (s/cat
                                              :sym symbol?
                                              :doc (s/? string?)
                                              :arglist (s/and vector? #(<= 2 (count %) 5))
                                              :options (s/? ::options)
                                              :body (s/* any?)))

(defn defsc*
  [env args]
  (if-not (s/valid? ::args args)
    (throw (ana/error env (str "Invalid arguments. " (-> (s/explain-data ::args args)
                                                       ::s/problems
                                                       first
                                                       :path) " is invalid."))))
  (let [{:keys [sym doc arglist options body]} (s/conform ::args args)
        [thissym propsym computedsym extra-args] arglist
        {:keys [ident query initial-state]} options
        body                             (or body ['nil])
        ident-template-or-method         (into {} [ident])  ;clojure spec returns a map entry as a vector
        initial-state-template-or-method (into {} [initial-state])
        query-template-or-method         (into {} [query])
        validate-query?                  (and (:template query-template-or-method) (not (some #{'*} (:template query-template-or-method))))
        legal-key-checker                (if validate-query?
                                           (or (-legal-keys (:template query-template-or-method)) #{})
                                           (complement #{}))
        ident-form                       (build-ident env thissym propsym ident-template-or-method legal-key-checker)
        state-form                       (build-initial-state env sym thissym initial-state-template-or-method legal-key-checker query-template-or-method)
        query-form                       (build-query-forms env sym thissym propsym query-template-or-method)
        render-form                      (build-render sym thissym propsym computedsym extra-args body)
        nspc                             (if (cljs? env) (-> env :ns :name str) (name (ns-name *ns*)))
        fqkw                             (keyword (str nspc) (name sym))
        options-map                      (cond-> options
                                           state-form (assoc :initial-state state-form)
                                           ident-form (assoc :ident ident-form)
                                           query-form (assoc :query query-form)
                                           render-form (assoc :render render-form))]
    (if (cljs? env)
      `(do
         (declare ~sym)
         (let [options# ~options-map]
           (defn ~(vary-meta sym assoc :doc doc :once true)
             [props#]
             (cljs.core/this-as this#
               (if-let [init-state# (get options# :initLocalState)]
                 (set! (.-state this#) (cljs.core/js-obj "fulcro$state" (init-state# this#)))
                 (set! (.-state this#) (cljs.core/js-obj "fulcro$state" {})))
               (when-let [constructor# (get options# :constructor)]
                 (constructor# this# (goog.object/get props# "fulcro$value")))))
           (com.fulcrologic.fulcro.components/configure-component! ~sym ~fqkw options#)))
      `(do
         (let [options# ~options-map]
           (def ~(vary-meta sym assoc :doc doc :once true)
             (com.fulcrologic.fulcro.components/configure-component! ~(str sym) ~fqkw options#)))))))

