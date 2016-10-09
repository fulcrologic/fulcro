(ns untangled.client.ui
  (:require
    [untangled.client.augmentation :as aug]
    [untangled.client.built-in-augments]
    [untangled.client.impl.util :as utl]
    [cljs.analyzer :as ana]
    [clojure.spec :as s]
    [clojure.spec.gen :as sg]
    [clojure.string :as str]
    [om.next :as om]))

(defn process-meta-info {:doc "for mocking"} [meta-info] meta-info)

(defn my-group-by [f coll]
  (into {} (map (fn [[k v]]
                  (assert (= 1 (count v))
                    (str "Cannot implement " k " more than once!"))
                  [(name k) (first v)]) (group-by f coll))))

(s/def ::method
  (s/cat :name symbol?
    :param-list (s/coll-of symbol? :into [] :kind vector?)
    :body (s/+ utl/TRUE)))
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
(.println System/out (str "INITIALIZED DEFUI_AUGMENT_MODE TO: " defui-augment-mode))

(defn active-aug? [{:keys [aug params]}]
  (case (::mode params)
    nil    true
    :dev  (re-find #"^dev" defui-augment-mode)
    :prod (re-find #"^prod" defui-augment-mode)
    (throw (ex-info "Invalid augment namespace"
             {:aug aug :supported-namespaces #{:dev :prod}}))))

(defn resolve-augment [[aug-type augment]]
  (case aug-type
    (:kw :sym) {:aug augment}
    :call (update augment :aug (comp :aug resolve-augment))))

(defn get-augments [ast]
  (into []
    (comp
      (map resolve-augment)
      (filter active-aug?))
    (:augments ast)))

(defn install-augments [ctx ast]
  (reduce (fn [ast {:keys [aug params]}]
            (aug/defui-augmentation (assoc ctx :augment/dispatch aug) ast params))
          (dissoc ast :augments :defui-name) (get-augments ast)))

(defn make-ctx [ast form env]
  {:defui/loc (process-meta-info
                (merge (meta form)
                       {:file ana/*cljs-file*}))
   :defui/ui-name (:defui-name ast)
   :env/cljs? (boolean (:ns env))})

(defn defui* [body form env]
  (let [ast (utl/conform! ::defui body)
        {:keys [defui/ui-name env/cljs?] :as ctx} (make-ctx ast form env)]
    ((if cljs? om/defui* om/defui*-clj)
     (vary-meta ui-name assoc :once true)
     (->> ast
       (install-augments ctx)
       (s/unform ::defui)))))

(defmacro defui [& body]
  (defui* body &form &env))
