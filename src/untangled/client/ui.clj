(ns untangled.client.ui
  (:require
    cljs.analyzer
    [clojure.spec :as s]
    [clojure.string :as str]
    [om.next :as om]))

(defn process-meta-info {:doc "for mocking"} [meta-info] meta-info)

(defn dbg [& args]
  (println "console.log('" (str args) "');"))

(defn my-group-by [f coll]
  (into {} (map (fn [[k [v]]] [(name k) v]) (group-by f coll))))

(defn conform! [spec x]
  (let [rt (s/conform spec x)]
    (when (s/invalid? rt)
      (throw (ex-info (s/explain-str spec x) {})))
    rt))

(s/def ::defui-name symbol?)
(s/def ::method
  (s/cat :name symbol?
    :param-list vector?
    :body (s/+ (constantly true))))
(s/def ::protocol-impls
  (s/cat :static (s/? '#{static})
    :protocol symbol?
    :methods (s/+ (s/spec ::method))))
(s/def ::defui (s/and (s/+ ::protocol-impls)
                 (s/conformer
                   #(->> %
                      (mapv (fn [x] (update x :methods (partial my-group-by :name))))
                      (my-group-by :protocol))
                   #(->> % vals
                      (mapv (fn [x] (update x :methods vals)))))))

(defmulti defui-ast-xform (fn [ctx _ _] (::method-name ctx)))

(defmethod defui-ast-xform :DevTools [{:keys [defui/loc defui/ui-name env/cljs?]} body _]
  (cond-> body cljs?
    (update-in ["Object" :methods "render"]
      (fn [{:as method :keys [body param-list]}]
        (assoc method :body
          (conj (vec (butlast body))
                `(untangled.client.ui/wrap-render ~loc
                   ~{:klass ui-name
                     :this (first param-list)}
                   ~(last body))))))))

(defmethod defui-ast-xform :DerefFactory [{:keys [defui/ui-name env/cljs?]} body _]
  (letfn [(get-factory-opts [body]
            (when-let [{:keys [static methods]} (get body "Defui")]
              (assert static "Defui should be a static protocol")
              (assert (>= 1 (count methods))
                (str "There can only be factory-opts implemented on Defui, failing methods: " methods))
              (when (= 1 (count methods))
                (assert (get methods "factory-opts")
                  (str "You did not implement factory-opts, instead found: " methods))))
            (when-let [{:keys [param-list body]} (get-in body ["Defui" :methods "factory-opts"])]
              (assert (and (vector? param-list) (empty? param-list)))
              (assert (and (= 1 (count body))))
              (last body)))]
    (let [?factoryOpts (get-factory-opts body)]
      (-> body
        (assoc-in [(if cljs? "IDeref" "clojure.lang.IDeref")]
          {:static 'static
           :protocol (if cljs? 'IDeref 'clojure.lang.IDeref)
           :methods {(if cljs? "-deref" "deref")
                     {:name (if cljs? '-deref 'deref)
                      :param-list '[_]
                      :body `[(om.next/factory ~ui-name
                                ~(or ?factoryOpts {}))]}}})
        (dissoc "Defui")))))

(defmethod defui-ast-xform :WithExclamation [_ body excl]
  (update-in body ["Object" :methods "render" :body]
    (fn [body]
      (conj (vec (butlast body))
            `(om.dom/div nil
               (om.dom/p nil ~(str excl))
               ~(last body))))))

(defn get-defui-mode []
  (str/lower-case
    (or (System/getenv "DEFUI_MODE")
        (System/getProperty "DEFUI_MODE")
        "prod")))

(defn resolve-mixin [mixin]
  (or (cond
        (list? mixin)
        #_>> (let [[kw params] mixin]
               {:xf kw :params params})
        (keyword? mixin) #_>> (do (assert true "TODO: multimethod has method mixin")
                                {:xf mixin}))
      (throw (ex-info (str "<" mixin "> mixin not supported") {}))))

(defn defui* [ui-name mixins body form env]
  (let [defui-mode (get-defui-mode)
        _ (dbg "defui-mode" defui-mode)
        ast-xforms (into []
                     (comp
                       (map resolve-mixin)
                       (filter (comp #(if-let [nss (namespace %)]
                                        (do (dbg "nss" nss)
                                          (case nss
                                            "dev"  (#{"dev"} defui-mode)
                                            "prod" (#{"prod"} defui-mode)
                                            (throw (ex-info "Invalid mixin namespace" {:kw %}))))
                                        true) :xf))
                       (map #(update % :xf (comp keyword name))))
                     mixins)
        cljs? (boolean (:ns env))
        ctx {:defui/loc (process-meta-info
                          (merge (meta form)
                                 {:file cljs.analyzer/*cljs-file*}))
             :defui/ui-name ui-name
             :env/cljs? cljs?}
        apply-xforms
        (fn [ctx body]
          (reduce (fn [body {:keys [xf params]}]
                    (defui-ast-xform (assoc ctx ::method-name xf) body params))
                  body ast-xforms))]
    ((if cljs? om/defui* om/defui*-clj)
     (vary-meta ui-name assoc :once true)
     (->> body
       (conform! ::defui)
       (apply-xforms ctx)
       (s/unform ::defui)))))

(defmacro defui [ui-name mixins & body]
  (defui* ui-name mixins body &form &env))
