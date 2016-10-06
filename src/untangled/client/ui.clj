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

(defmacro defui [ui-name mixins & body]
  (let [xforms (if ((some-fn list? symbol?) mixins) (eval mixins)
                 (mapv #(eval %) mixins))]
    (assert (not-any? nil? xforms)
      (str {:failing-xforms (into [] (remove #(last %) (map vector mixins (repeat "=>") xforms)))
            :ui-name ui-name, :meta-info (meta &form), :env &env
            :*ns* *ns*, :ns cljs.analyzer/*cljs-ns*}))
    (defui* ui-name body &form &env xforms)))
