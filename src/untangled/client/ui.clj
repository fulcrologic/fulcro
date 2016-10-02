(ns untangled.client.ui
  (:require
    cljs.analyzer
    [clojure.spec :as s]
    [om.next :as om]))

(defn process-meta-info {:doc "for mocking"} [meta-info] meta-info)

(defn my-group-by [f coll]
  (into {} (map (fn [[k [v]]] [(name k) v]) (group-by f coll))))

(s/def ::defui-name symbol?)
(s/def ::method
  (s/cat :name symbol?
    :param-list vector?
    :body (s/+ (constantly true))))
(s/def ::protocol-impls
  (s/cat :static (s/? '#{static})
    :protocol symbol?
    :methods (s/and (s/+ ::method)
               (s/conformer
                 #(my-group-by :name %)
                 vals))))
(s/def ::defui (s/and (s/+ ::protocol-impls)
                 (s/conformer
                   #(my-group-by :protocol %)
                   vals)))

(defn conform! [spec x]
  (s/assert spec x)
  (s/conform spec x))

(defn base-defui-middleware [{:keys [meta-info ui-name factory-opts]} body]
  (letfn [(inject-deref-factory [body]
            (assoc-in body ["IDeref"]
              {:static 'static
               :protocol 'IDeref
               :methods {"-deref" {:name '-deref
                                   :param-list '[_]
                                   :body `[(om.next/factory ~ui-name ~factory-opts)]}}}))
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

(defonce defui-xform (fn [ctx body] body))
(defn set-defui-xform! [& fns]
  (let [dbg (fn [n x] (println "console.log('" (str n "=> " x) "');"))]
    (alter-var-root #'defui-xform
      (constantly
        (fn [ctx body]
          (reduce (fn [body f]
                    (f (assoc ctx :dbg dbg) body))
                  body fns))))))

(defn defui* [ui-name factory-opts body meta-data]
  (let [ctx {:meta-info (process-meta-info
                          (merge meta-data
                                 {:file cljs.analyzer/*cljs-file*}))
             :ui-name ui-name
             :factory-opts factory-opts}]
    (om/defui* (vary-meta ui-name assoc :once true)
      (s/unform ::defui
        (defui-xform ctx
          (base-defui-middleware ctx
            (conform! ::defui body)))))))

(defmacro defui [ui-name factory-opts & body]
  (defui* ui-name factory-opts body (meta &form)))
