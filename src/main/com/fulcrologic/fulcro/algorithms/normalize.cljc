(ns com.fulcrologic.fulcro.algorithms.normalize
  "Functions for dealing with normalizing Fulcro databases. In particular `tree->db`."
  (:require
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.components :refer [has-ident? ident get-ident get-query]]))

(defn- normalize* [query data tables union-seen transform]
  ;; `tables` is an (atom {}) where we collect normalized tables for all components encountered during processing, i.e.
  ;; we only return the "top-level keys" with their data/idents and all "tables" are inside this
  (let [data (if (and transform (not (vector? data)))
               (transform query data)
               data)]
    (cond
      (= '[*] query) data

      ;; union case
      (map? query)
      (let [class (-> query meta :component)
            ident (get-ident class data)]
        (if-not (nil? ident)
          (vary-meta (normalize* (get query (first ident)) data tables union-seen transform)
            assoc ::tag (first ident))                      ; FIXME: What is tag for?
          (throw (ex-info "Union components must have an ident" {}))))

      (vector? data) data                                   ;; already normalized

      :else
      (loop [q (seq query), ret data]
        (if-not (nil? q)
          (let [expr (first q)]
            (if (util/join? expr)
              (let [[join-key subquery] (util/join-entry expr)
                    recursive?  (util/recursion? subquery)
                    union-entry (if (util/union? expr) subquery union-seen)
                    subquery    (if recursive?
                                  (if-not (nil? union-seen)
                                    union-seen
                                    query)
                                  subquery)
                    class       (-> subquery meta :component)
                    v           (get data join-key)]
                (cond
                  ;; graph loop: db->tree leaves ident in place
                  (and recursive? (eql/ident? v)) (recur (next q) ret)
                  ;; normalize one
                  (map? v)
                  (let [x (normalize* subquery v tables union-entry transform)]
                    (if-not (or (nil? class) (not (has-ident? class)))
                      (let [i (get-ident class x)]
                        ;; Why don't we simply `update-in i ..` as we do below in normalize many?! Incidental?
                        (swap! tables update-in [(first i) (second i)] merge x) ; add x to the normalized client DB
                        (recur (next q) (assoc ret join-key i)))
                      (recur (next q) (assoc ret join-key x))))

                  ;; normalize many
                  (and (vector? v) (not (eql/ident? v)) (not (eql/ident? (first v))))
                  (let [xs (into [] (map #(normalize* subquery % tables union-entry transform)) v)]
                    (if-not (or (nil? class) (not (has-ident? class)))
                      (let [is (into [] (map #(get-ident class %)) xs)]
                        ;; Where does the code come from? A little lesson of history:
                        ;; There was no if and no union handling in https://github.com/omcljs/om/commit/bbd94ac17a4c208f928a84915a050b787b65cb6a
                        ;; and it was added by https://github.com/omcljs/om/commit/3882cb5b9a3db95fa94b016bbe7bfe7f8b1db638 "query union WIP";
                        ;; Later https://github.com/omcljs/om/commit/baaf4510d9970f1d9aa8dfcbe28bc89242bae87b#diff-8245f06a64876f1022b17c2eb5102ed6a658612b2da113093ea29185b5829682L2025
                        ;; "OM-802: Recursive query normalization incorrect " changed the old brqnch code to also use reduce
                        ;; but keeping the zipmap, likely without noticing the branches becane so similar so as to be mergable
                        ;; The `(when-not (empty? is) ..` was added by https://github.com/omcljs/om/commit/8a34c2cf90d45de3c464eceb4a2866de2d99e5f0
                        ;; and was necessary at that time b/c it still used `swap! refs update-in` and thus misbehaved for empty is
                        (if (vector? subquery)
                          (when-not (empty? is)
                            (swap! tables
                              (fn [tables']
                                (reduce (fn merge-to-client-db [m [i x]] (update-in m i merge x))
                                  ;; Why zipmap and not `map vector` as in the other merge-to-client-db?
                                  ;; Incidental or intentional? Do we accept duplicates there but only want the last one
                                  ;; here for some reason?
                                  ;; Seems incidental; acc. to https://github.com/omcljs/om/commit/baaf4510d9970f1d9aa8dfcbe28bc89242bae87b
                                  ;; it needed map because it merged it with tables[(ffirst is)]
                                  tables' (zipmap is xs)))))
                          ;; union case
                          ;;  The difference from non-union is that we process duplicates in v in merge-to-client-db (why??)
                          ;;  and that we don't check `is` for not empty (likely a perf. optimization not needed in this
                          ;;  rarely visited path?)
                          (swap! tables
                            (fn [tables']
                              (reduce (fn merge-to-client-db [m [i x]] (update-in m i merge x))
                                ;; Note: `is` might have multiple `[<kwd> nil]` occurrences if `v` has 2+ entity types
                                ;; the union does not handle, depending on its :ident impl. Do we care? why?
                                tables' (map vector is xs)))))
                        (recur (next q) (assoc ret join-key is)))
                      (recur (next q) (assoc ret join-key xs))))

                  ;; missing key
                  (nil? v)
                  (recur (next q) ret)

                  ;; can't handle
                  :else (recur (next q) (assoc ret join-key v))))
              (let [k (if (seq? expr) (first expr) expr)
                    v (get data k)]
                (if (nil? v)
                  (recur (next q) ret)
                  (recur (next q) (assoc ret k v))))))
          ret)))))

(defn tree->db
  "Given a component class or instance and a tree of data, use the component's
   query to transform the tree into the default database format. All nodes that
   can be mapped via Ident implementations wil be replaced with ident links. The
   original node data will be moved into tables indexed by ident. If merge-idents
   option is true, will return these tables in the result instead of as metadata."
  ([x data]
   (tree->db x data false))
  ([x data #?(:clj merge-idents :cljs ^boolean merge-idents)]
   (tree->db x data merge-idents nil))
  ([x data #?(:clj merge-idents :cljs ^boolean merge-idents) transform]
   (let [refs (atom {})
         x    (if (vector? x) x (get-query x data))
         ret  (normalize* x data refs nil transform)]
     (if merge-idents
       (let [refs' @refs] (merge ret refs'))
       (with-meta ret @refs)))))
