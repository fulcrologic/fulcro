(ns untangled.client.ui
  (:require
    [untangled.client.defui-augment :refer [defui-augment]]
    [untangled.client.augments]
    [cljs.analyzer :as ana]
    [clojure.spec :as s]
    [clojure.spec.gen :as sg]
    [clojure.string :as str]
    [om.next :as om]))

(defn process-meta-info {:doc "for mocking"} [meta-info] meta-info)

(defn dbg [& args]
  (println "console.log('" (str args) "');"))

(defn my-group-by [f coll]
  (into {} (map (fn [[k v]]
                  (assert (= 1 (count v))
                    (str "Cannot implement " k " more than once!"))
                  [(name k) (first v)]) (group-by f coll))))

(defn conform! [spec x]
  (let [rt (s/conform spec x)]
    (when (s/invalid? rt)
      (throw (ex-info (s/explain-str spec x)
               (s/explain-data spec x))))
    rt))

(s/def ::method
  (s/cat :name symbol?
    :param-list (s/coll-of symbol? :into [] :kind vector?)
    :body (s/+ (s/with-gen (constantly true) sg/int))))
(s/def ::impls
  (s/cat :static (s/? '#{static})
    :protocol symbol?
    :methods (s/+ (s/spec ::method))))
(s/def ::augments
  (s/coll-of (s/or :kw keyword? :sym symbol?
                   :call (s/cat :aug (s/or :kw keyword? :sym symbol?)
                           :params map?))
    :into [] :kind vector?))
(s/def ::defui-name symbol?)
(s/def ::defui
  (s/and (s/cat
           :defui-name ::defui-name
           :augments (s/? ::augments)
           :impls (s/+ ::impls))
    (s/conformer
      #(-> %
         (update :impls (partial mapv (fn [x] (update x :methods (partial my-group-by :name)))))
         (update :impls (partial my-group-by :protocol)))
      #(-> %
         (update :impls vals)
         (update :impls (partial mapv (fn [x] (update x :methods vals))))))))

(def defui-augment-mode
  (str/lower-case
    (or (System/getenv "DEFUI_AUGMENT_MODE")
        (System/getProperty "DEFUI_AUGMENT_MODE")
        "prod")))

(defn active-aug? [aug]
  (case (namespace aug)
    nil    true
    "dev"  (#{"dev"} defui-augment-mode)
    "prod" (#{"prod"} defui-augment-mode)
    (throw (ex-info "Invalid augment namespace"
             {:aug aug :supported-namespaces #{"dev" "prod"}}))))

(defn resolve-augment [[aug-type augment]]
  (case aug-type
    (:kw :sym) {:aug augment}
    :call (update augment :aug (comp :aug resolve-augment))))

(defn get-augments [ast]
  (into []
    (comp
      (map resolve-augment)
      (filter (comp active-aug? :aug))
      (map #(update % :aug (comp keyword name))))
    (:augments ast)))

(defn install-augments [ctx ast]
  (reduce (fn [ast {:keys [aug params]}]
            (defui-augment (assoc ctx :augment/dispatch aug) ast params))
          (dissoc ast :augments :defui-name) (get-augments ast)))

(defn make-ctx [ast form env]
  {:defui/loc (process-meta-info
                (merge (meta form)
                       {:file ana/*cljs-file*}))
   :defui/ui-name (:defui-name ast)
   :env/cljs? (boolean (:ns env))})

(defn defui* [body form env]
  (let [ast (conform! ::defui body)
        {:keys [defui/ui-name env/cljs?] :as ctx} (make-ctx ast form env)]
    ((if cljs? om/defui* om/defui*-clj)
     (vary-meta ui-name merge (meta form) {:once true})
     (->> ast
       (install-augments ctx)
       (s/unform ::defui)))))

(defmacro defui [& body]
  (defui* body &form &env))
