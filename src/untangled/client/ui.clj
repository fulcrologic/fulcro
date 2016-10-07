(ns untangled.client.ui
  (:require
    cljs.analyzer
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

(defmulti defui-augment (fn [ctx _ _] (::augment-dispatch ctx)))

(defmethod defui-augment :DevTools [{:keys [defui/loc defui/ui-name env/cljs?]} ast _]
  (cond-> ast cljs?
    (update-in [:impls "Object" :methods "render"]
      (fn [{:as method :keys [body param-list]}]
        (assoc method :body
          (conj (vec (butlast body))
                `(untangled.client.ui/wrap-render ~loc
                   ~{:klass ui-name
                     :this (first param-list)}
                   ~(last body))))))))

(defmethod defui-augment :DerefFactory [{:keys [defui/ui-name env/cljs?]} ast _]
  (letfn [(get-factory-opts [ast]
            (when-let [{:keys [static methods]} (get-in ast [:impls "Defui"])]
              (assert static "Defui should be a static protocol")
              (assert (>= 1 (count methods))
                (str "There can only be factory-opts implemented on Defui, failing methods: " methods))
              (when (= 1 (count methods))
                (assert (get methods "factory-opts")
                  (str "You did not implement factory-opts, instead found: " methods))))
            (when-let [{:keys [param-list body]} (get-in ast [:impls "Defui" :methods "factory-opts"])]
              (assert (and (vector? param-list) (empty? param-list)))
              (assert (and (= 1 (count body))))
              (last body)))]
    (let [?factoryOpts (get-factory-opts ast)]
      (-> ast
        (assoc-in [:impls (if cljs? "IDeref" "clojure.lang.IDeref")]
          {:static 'static
           :protocol (if cljs? 'IDeref 'clojure.lang.IDeref)
           :methods {(if cljs? "-deref" "deref")
                     {:name (if cljs? '-deref 'deref)
                      :param-list '[_]
                      :body `[(om.next/factory ~ui-name
                                ~(or ?factoryOpts {}))]}}})
        (update-in [:impls] #(dissoc % "Defui"))))))

(defmethod defui-augment :WithExclamation [_ ast {:keys [excl]}]
  (update-in ast [:impls "Object" :methods "render" :body]
    (fn [body]
      (conj (vec (butlast body))
            `(om.dom/div nil
               (om.dom/p nil ~(str excl))
               ~(last body))))))

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
            (defui-augment (assoc ctx ::augment-dispatch aug) ast params))
          (dissoc ast :augments :defui-name) (get-augments ast)))

(defn make-ctx [ast form env]
  {:defui/loc (process-meta-info
                (merge (meta form)
                       {:file cljs.analyzer/*cljs-file*}))
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
