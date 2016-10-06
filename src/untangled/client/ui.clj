(ns untangled.client.ui
  (:require
    cljs.analyzer
    [clojure.spec :as s]
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

(defn base-defui-middleware [{:keys [meta-info ui-name]} body]
  (letfn [(inject-deref-factory [body]
            (assoc-in body ["IDeref"]
              {:static 'static
               :protocol 'IDeref
               :methods {"-deref"
                         {:name '-deref
                          :param-list '[_]
                          :body `[(om.next/factory ~ui-name ~{})]}}}))
          (wrap-render-with-meta-info [body]
            (update-in body ["Object" :methods "render"]
              (fn [{:as method :keys [body param-list]}]
                (assoc method :body
                  (conj (vec (butlast body))
                        `(wrap-render ~meta-info
                           ~{:klass ui-name
                             :this (first param-list)}
                           ~(last body)))))))]
    (-> body
      (inject-deref-factory)
      (wrap-render-with-meta-info))))

(defn defui* [ui-name body form env xforms]
  (let [ctx {:meta-info (process-meta-info
                          (merge (meta form)
                                 {:file cljs.analyzer/*cljs-file*}))
             :ui-name ui-name}
        apply-xforms
        (fn [ctx body]
          (reduce (fn [body xf]
                    (xf ctx body))
                  body xforms))]
    (om/defui* (vary-meta ui-name assoc :once true)
      (->> body
        (conform! ::defui)
        (apply-xforms ctx)
        (s/unform ::defui)))))

(defmacro defui [ui-name & body]
  (defui* ui-name body &form &env
    [base-defui-middleware]))
