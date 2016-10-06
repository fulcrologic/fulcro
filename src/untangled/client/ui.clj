(ns untangled.client.ui
  (:require
    cljs.analyzer
    [clojure.spec :as s]
    [om.next :as om]
    [untangled.client.xforms]))

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

(defn DevTools [{:keys [meta-info ui-name cljs?]} body]
  (cond-> body cljs?
    (update-in ["Object" :methods "render"]
      (fn [{:as method :keys [body param-list]}]
        (assoc method :body
          (conj (vec (butlast body))
                `(wrap-render ~meta-info
                   ~{:klass ui-name
                     :this (first param-list)}
                   ~(last body))))))))

(defn DerefFactory [{:keys [meta-info ui-name cljs?]} body]
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

(defn defui* [ui-name body form env xforms]
  (let [cljs? (boolean (:ns env))
        ctx {:meta-info (process-meta-info
                          (merge (meta form)
                                 {:file cljs.analyzer/*cljs-file*}))
             :ui-name ui-name
             :cljs? cljs?}
        apply-xforms
        (fn [ctx body]
          (reduce (fn [body xf]
                    (xf ctx body))
                  body xforms))]
    ((if cljs? om/defui* om/defui*-clj)
     (vary-meta ui-name assoc :once true)
     (->> body
       (conform! ::defui)
       (apply-xforms ctx)
       (s/unform ::defui)))))

(defmacro defui [ui-name xform-syms & body]
  (let [xforms (mapv #(some->> % (ns-resolve *ns* &env) var-get) xform-syms)]
    (assert (not-any? nil? xforms)
      (str {:failing-xforms (into [] (remove #(last %) (map vector xform-syms (repeat "=>") xforms)))
            :ui-name ui-name, :meta-info (meta &form), :env &env
            :*ns* *ns*, :ns cljs.analyzer/*cljs-ns*}))
    (defui* ui-name body &form &env xforms)))
