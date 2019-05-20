(ns
  ^{:doc "
   Generic query expression parsing and AST manipulation.

   QUERY EXPRESSIONS

   Query expressions are a variation on Datomic Pull Syntax
   http://docs.datomic.com/pull.html more suitable for generic client/server
   state transfer. It's important to note the query expression syntax is
   *not* a strict superset of Datomic Pull.

   A query expression is composed of EDN values. The grammar for query
   expressions follows:

   QueryRoot      := EdnVector(QueryExpr*)
   PlainQueryExpr := (EdnKeyword | IdentExpr | JoinExpr)
   QueryExpr      := (PlainQueryExpr | ParamExpr)
   IdentExpr      := EdnVector2(Keyword, EdnValue)
   ParamExpr      := EdnList2(PlainQueryExpr | EdnSymbol, ParamMapExpr)
   ParamMapExpr   := EdnMap(Keyword, EdnValue)
   JoinExpr       := EdnMap((Keyword | IdentExpr), (QueryRoot | UnionExpr | RecurExpr))
   UnionExpr      := EdnMap(Keyword, QueryRoot)
   RecurExpr      := ('... | Integer)

   Note most of the api expects a QueryRoot not a QueryExpr.

   QUERY EXPRESSION AST FORMAT

   Given a QueryExpr you can get the AST via om.next.impl.parser/expr->ast.
   The following keys can appear in the AST representation:

   {:type         (:prop | :join | :call | :root | :union | :union-entry)
    :key          (EdnKeyword | EdnSymbol | IdentExpr)
    :dispatch-key (EdnKeyword | EdnSymbol)
    :union-key    EdnKeyword
    :query        (QueryRoot | RecurExpr)
    :params       ParamMapExpr
    :children     EdnVector(AST)
    :component    Object
    :target       EdnKeyword}

   :query and :params may or may not appear. :type :call is only for
   mutations."}
  com.fulcrologic.fulcro.algorithms.ast)

(declare expr->ast)

(defn- mark-meta [source target]
  (cond-> target
    (meta source) (assoc :meta (meta source))))

(defn symbol->ast [k]
  {:dispatch-key k
   :key          k})

(defn keyword->ast [k]
  {:type         :prop
   :dispatch-key k
   :key          k})

(defn union-entry->ast [[k v]]
  (let [component (-> v meta :component)]
    (merge
      {:type      :union-entry
       :union-key k
       :query     v
       :children  (into [] (map expr->ast) v)}
      (when-not (nil? component)
        {:component component}))))

(defn union->ast [m]
  {:type     :union
   :query    m
   :children (into [] (map union-entry->ast) m)})

(defn call->ast [[f args :as call]]
  (if (= 'quote f)
    (assoc (expr->ast args) :target (or (-> call meta :target) :remote))
    (let [ast (update-in (expr->ast f) [:params] merge (or args {}))]
      (cond-> (mark-meta call ast)
        (symbol? (:dispatch-key ast)) (assoc :type :call)))))

(defn query->ast
  "Convert a query to its AST representation."
  [query]
  (let [component (-> query meta :component)]
    (merge
      (mark-meta query
        {:type     :root
         :children (into [] (map expr->ast) query)})
      (when-not (nil? component)
        {:component component}))))

(defn join->ast [join]
  (let [query-root? (-> join meta :query-root)
        [k v] (first join)
        ast         (expr->ast k)
        type        (if (= :call (:type ast)) :call :join)
        component   (-> v meta :component)]
    (merge ast
      (mark-meta join {:type type :query v})
      (when-not (nil? component)
        {:component component})
      (when query-root?
        {:query-root true})
      (when-not (or (number? v) (= '... v))
        (cond
          (vector? v) {:children (into [] (map expr->ast) v)}
          (map? v) {:children [(union->ast v)]}
          :else (throw
                  (ex-info (str "Invalid join, " join)
                    {:type :error/invalid-join})))))))

(defn ident->ast [[k id :as ref]]
  {:type         :prop
   :dispatch-key k
   :key          ref})

(defn expr->ast
  "Given a query expression convert it into an AST."
  [x]
  (cond
    (symbol? x) (symbol->ast x)
    (keyword? x) (keyword->ast x)
    (map? x) (join->ast x)
    (vector? x) (ident->ast x)
    (seq? x) (call->ast x)
    :else (throw
            (ex-info (str "Invalid expression " x)
              {:type :error/invalid-expression}))))

(defn- wrap-expr [root? expr]
  (if root?
    (with-meta
      (cond-> expr (keyword? expr) list)
      {:query-root true})
    expr))

(defn- parameterize
  "Add parameters to the given raw query expression.

  (parameterize :x {:p 1}) => (:x {:p 1})"
  [expr params]
  (if-not (empty? params)
    (list expr params)
    (list expr)))

(defn ast->expr
  "Given a query expression AST convert it back into a query expression."
  ([ast]
   (ast->expr ast false))
  ([{:keys [type component] ast-meta :meta :as ast} unparse?]
   (if (= :root type)
     (cond-> (into (with-meta [] ast-meta) (map #(ast->expr % unparse?)) (:children ast))
       (not (nil? component)) (vary-meta assoc :component component))
     (let [{:keys [key query query-root params]} ast]
       (wrap-expr query-root
         (if (and params (not= :call type))
           (let [expr (ast->expr (dissoc ast :params) unparse?)]
             (parameterize expr params))
           (let [key (if (= :call type) (parameterize key params) key)]
             (if (or (= :join type)
                   (and (= :call type) (:children ast)))
               (if (and (not= '... query) (not (number? query))
                     (or (true? unparse?)
                       (= :call type)))
                 (let [{:keys [children]} ast
                       query-meta (meta query)]
                   (if (and (== 1 (count children))
                         (= :union (:type (first children)))) ;; UNION
                     (with-meta
                       {key (into (cond-> (with-meta {} ast-meta)
                                    component (vary-meta assoc :component component))
                              (map (fn [{:keys [union-key children component]}]
                                     [union-key
                                      (cond-> (into [] (map #(ast->expr % unparse?)) children)
                                        (not (nil? component)) (vary-meta assoc :component component))]))
                              (:children (first children)))}
                       ast-meta)
                     (with-meta
                       {key (cond-> (into (with-meta [] query-meta) (map #(ast->expr % unparse?)) children)
                              (not (nil? component)) (vary-meta assoc :component component))}
                       ast-meta)))
                 (with-meta {key query} ast-meta))
               key))))))))

(defn query->ast1
  "Call query->ast and return the first children."
  [query-expr]
  (-> (query->ast query-expr) :children first))

