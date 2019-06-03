(ns com.fulcrologic.fulcro.algorithms.legacy-db-tree
  (:require
    [clojure.zip :as zip]
    [taoensso.timbre :as log]))

(declare focus-query*)

(defn util-union?
  #?(:cljs {:tag boolean})
  [expr]
  (let [expr (cond-> expr (seq? expr) first)]
    (and (map? expr)
      (map? (-> expr first second)))))

(defn util-recursion?
  #?(:cljs {:tag boolean})
  [x]
  (or #?(:clj  (= '... x)
         :cljs (symbol-identical? '... x))
    (number? x)))

(defn util-join-entry [expr]
  (let [[k v] (if (seq? expr)
                (ffirst expr)
                (first expr))]
    [(if (list? k) (first k) k) v]))

(defn util-join-key [expr]
  (cond
    (map? expr) (let [k (ffirst expr)]
                  (if (list? k)
                    (first k)
                    (ffirst expr)))
    (seq? expr) (util-join-key (first expr))
    :else expr))

(defn util-ident?
  "Returns true if x is an ident."
  #?(:cljs {:tag boolean})
  [x]
  (and (vector? x)
    (== 2 (count x))
    (keyword? (nth x 0))))

(defn util-unique-ident?
  #?(:cljs {:tag boolean})
  [x]
  (and (util-ident? x) (= '_ (second x))))

(defn util-join? [x]
  #?(:cljs {:tag boolean})
  (let [x (if (seq? x) (first x) x)]
    (map? x)))

(defn- focused-join [expr ks full-expr union-expr]
  (let [expr-meta (meta expr)
        expr'     (cond
                    (map? expr)
                    (let [join-value (-> expr first second)
                          join-value (if (and (util-recursion? join-value)
                                           (seq ks))
                                       (if-not (nil? union-expr)
                                         union-expr
                                         full-expr)
                                       join-value)]
                      {(ffirst expr) (focus-query* join-value ks nil)})

                    (seq? expr) (list (focused-join (first expr) ks nil nil) (second expr))
                    :else expr)]
    (cond-> expr'
      (some? expr-meta) (with-meta expr-meta))))

(defn- focus-query*
  [query path union-expr]
  (if (empty? path)
    query
    (let [[k & ks] path]
      (letfn [(match [x]
                (= k (util-join-key x)))
              (value [x]
                (focused-join x ks query union-expr))]
        (if (map? query)                                    ;; UNION
          {k (focus-query* (get query k) ks query)}
          (into [] (comp (filter match) (map value) (take 1)) query))))))

(defn focus-query
  "Given a query, focus it along the specified path.

  Examples:
    (focus-query [:foo :bar :baz] [:foo])
    => [:foo]

    (fulcro.client.primitives/focus-query [{:foo [:bar :baz]} :woz] [:foo :bar])
    => [{:foo [:bar]}]"
  [query path]
  (focus-query* query path nil))

(defn- expr->key
  "Given a query expression return its key."
  [expr]
  (cond
    (keyword? expr) expr
    (map? expr) (ffirst expr)
    (seq? expr) (let [expr' (first expr)]
                  (when (map? expr')
                    (ffirst expr')))
    (util-ident? expr) (cond-> expr (= '_ (second expr)) first)
    :else
    (throw
      (ex-info (str "Invalid query expr " expr)
        {:type :error/invalid-expression}))))

(defn- query-zip
  "Return a zipper on a query expression."
  [root]
  (zip/zipper
    #(or (vector? %) (map? %) (seq? %))
    seq
    (fn [node children]
      (let [ret (cond
                  (vector? node) (vec children)
                  (map? node) (into {} children)
                  (seq? node) children)]
        (with-meta ret (meta node))))
    root))

(defn- move-to-key
  "Move from the current zipper location to the specified key. loc must be a
   hash map node."
  [loc k]
  (loop [loc (zip/down loc)]
    (let [node (zip/node loc)]
      (if (= k (first node))
        (-> loc zip/down zip/right)
        (recur (zip/right loc))))))

(defn- query-template
  "Given a query and a path into a query return a zipper focused at the location
   specified by the path. This location can be replaced to customize / alter
   the query."
  [query path]
  (letfn [(query-template* [loc path]
            (if (empty? path)
              loc
              (let [node (zip/node loc)]
                (if (vector? node)                          ;; SUBQUERY
                  (recur (zip/down loc) path)
                  (let [[k & ks] path
                        k' (expr->key node)]
                    (if (= k k')
                      (if (or (map? node)
                            (and (seq? node) (map? (first node))))
                        (let [loc'  (move-to-key (cond-> loc (seq? node) zip/down) k)
                              node' (zip/node loc')]
                          (if (map? node')                  ;; UNION
                            (if (seq ks)
                              (recur
                                (zip/replace loc'
                                  (zip/node (move-to-key loc' (first ks))))
                                (next ks))
                              loc')
                            (recur loc' ks)))               ;; JOIN
                        (recur (some-> loc zip/down zip/down zip/down zip/right) ks)) ;; CALL
                      (recur (zip/right loc) path)))))))]
    (query-template* (query-zip query) path)))

(defn- replace [template new-query]
  (-> template (zip/replace new-query) zip/root))

(defn reduce-query-depth
  "Changes a join on key k with depth limit from [:a {:k n}] to [:a {:k (dec n)}]"
  [q k]
  (if-not (empty? (focus-query q [k]))
    (if-let [pos (query-template q [k])]
      (let [node  (zip/node pos)
            node' (cond-> node (number? node) dec)]
        (replace pos node'))
      q)
    q))

(defn- reduce-union-recursion-depth
  "Given a union expression decrement each of the query roots by one if it
   is recursive."
  [union-expr recursion-key]
  (->> union-expr
    (map (fn [[k q]] [k (reduce-query-depth q recursion-key)]))
    (into {})))

(defn- mappable-ident? [refs ident]
  (and (util-ident? ident)
    (contains? refs (first ident))))

(defn- denormalize*
  "Denormalize a data based on query. refs is a data structure which maps idents
   to their values. map-ident is a function taking a ident to another ident,
   used during tempid transition. idents-seen is the set of idents encountered,
   used to limit recursion. union-expr is the current union expression being
   evaluated. recurse-key is key representing the current recursive query being
   evaluted."
  [query data refs map-ident idents-seen union-expr recurse-key]
  ;; support taking ident for data param
  (let [union-recur? (and union-expr recurse-key)
        recur-ident  (when union-recur?
                       data)
        data         (loop [data data limit 100]
                       (if (pos-int? limit)
                         (if (mappable-ident? refs data)
                           (recur (get-in refs (map-ident data)) (dec limit))
                           data)
                         {}))]
    (cond
      (vector? data)
      ;; join
      (let [step (fn [ident]
                   (if-not (mappable-ident? refs ident)
                     (if (= query '[*])
                       ident
                       (let [{props false joins true} (group-by util-join? query)
                             props (mapv #(cond-> % (seq? %) first) props)]
                         (loop [joins (seq joins) ret {}]
                           (if-not (nil? joins)
                             (let [join (first joins)
                                   [key sel] (util-join-entry join)
                                   v    (get ident key)]
                               (recur (next joins)
                                 (assoc ret
                                   key (denormalize* sel v refs map-ident
                                         idents-seen union-expr recurse-key))))
                             (merge (select-keys ident props) ret)))))
                     (let [ident'      (get-in refs (map-ident ident))
                           query       (cond-> query
                                         union-recur? (reduce-union-recursion-depth recurse-key))
                           ;; also reduce query depth of union-seen, there can
                           ;; be more union recursions inside
                           union-seen' (cond-> union-expr
                                         union-recur? (reduce-union-recursion-depth recurse-key))
                           query'      (cond-> query
                                         (map? query) (get (first ident)))] ;; UNION
                       (denormalize* query' ident' refs map-ident idents-seen union-seen' nil))))]
        (into [] (map step) data))

      (and (map? query) union-recur?)
      (denormalize* (get query (first recur-ident)) data refs map-ident
        idents-seen union-expr recurse-key)

      :else
      ;; map case
      (if (= '[*] query)
        data
        (let [{props false joins true} (group-by #(or (util-join? %) (util-ident? %)
                                                    (and (seq? %) (util-ident? (first %))))
                                         query)
              props (mapv #(cond-> % (seq? %) first) props)]
          (loop [joins (seq joins) ret {}]
            (if-not (nil? joins)
              (let [join        (first joins)
                    join        (cond-> join
                                  (seq? join) first)
                    join        (cond-> join
                                  (util-ident? join) (hash-map '[*]))
                    [key sel] (util-join-entry join)
                    recurse?    (util-recursion? sel)
                    recurse-key (when recurse? key)
                    v           (if (util-ident? key)
                                  (if (= '_ (second key))
                                    (get refs (first key))
                                    (get-in refs (map-ident key)))
                                  (get data key))
                    key         (cond-> key (util-unique-ident? key) first)
                    v           (if (mappable-ident? refs v)
                                  (loop [v v limit 100]
                                    (if (pos-int? limit)
                                      (let [next (get-in refs (map-ident v))]
                                        (if (mappable-ident? refs next)
                                          (recur next (dec limit))
                                          (map-ident v)))
                                      {}))
                                  v)
                    limit       (if (number? sel) sel :none)
                    union-entry (if (util-union? join)
                                  sel
                                  (when recurse?
                                    union-expr))
                    sel         (cond
                                  recurse?
                                  (if-not (nil? union-expr)
                                    union-entry
                                    (reduce-query-depth query key))

                                  (and (mappable-ident? refs v)
                                    (util-union? join))
                                  (get sel (first v))

                                  (and (util-ident? key)
                                    (util-union? join))
                                  (get sel (first key))

                                  :else sel)
                    graph-loop? (and recurse?
                                  (contains? (set (get idents-seen key)) v)
                                  (= :none limit))
                    idents-seen (if (and (mappable-ident? refs v) recurse?)
                                  (-> idents-seen
                                    (update-in [key] (fnil conj #{}) v)
                                    (assoc-in [:last-ident key] v)) idents-seen)]
                (cond
                  (= 0 limit) (recur (next joins) ret)
                  graph-loop? (recur (next joins) ret)
                  (nil? v) (recur (next joins) ret)
                  :else (recur (next joins)
                          (assoc ret
                            key (denormalize* sel v refs map-ident
                                  idents-seen union-entry recurse-key)))))
              (if-let [looped-key (some
                                    (fn [[k identset]]
                                      (if (contains? identset (get data k))
                                        (get-in idents-seen [:last-ident k])
                                        nil))
                                    (dissoc idents-seen :last-ident))]
                looped-key
                (merge (select-keys data props) ret)))))))))

(defn db->tree
  "Given a query, some data in the default database format, and the entire
   application state in the default database format, return the tree where all
   ident links have been replaced with their original node values."
  ([query data refs]
   {:pre [(map? refs)]}
   (denormalize* query data refs identity {} nil nil))
  ([query data refs map-ident]
   {:pre [(map? refs)]}
   (denormalize* query data refs map-ident {} nil nil)))
