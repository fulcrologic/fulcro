(ns untangled.client.ui
  (:require
    cljs.analyzer
    [clojure.spec :as s]
    [om.next :as om]
    [untangled.client.xforms :as xf]))

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

(defn resolve-mixins [mixins mixin->xform]
  (mapv (fn step [x]
          (or
            (cond
              (list? x)
              #_>> (let [[kw params] x]
                     (with-meta (step kw) {:params params}))
              (keyword? x)
              #_>> (get mixin->xform x))
            (throw (ex-info (str "<" x "> mixin not supported") {}))))
    mixins))

(defn defui* [ui-name mixins body form env mixin->xform]
  (let [xforms (resolve-mixins mixins mixin->xform)
        cljs? (boolean (:ns env))
        ctx {:defui/loc (process-meta-info
                          (merge (meta form)
                                 {:file cljs.analyzer/*cljs-file*}))
             :defui/ui-name ui-name
             :env/cljs? cljs?}
        apply-xforms
        (fn [ctx body]
          (reduce (fn [body xf]
                    (xf ctx body (:params (meta xf))))
                  body xforms))]
    ((if cljs? om/defui* om/defui*-clj)
     (vary-meta ui-name assoc :once true)
     (->> body
       (conform! ::defui)
       (apply-xforms ctx)
       (s/unform ::defui)))))

(defmacro defui [ui-name mixins & body]
  (defui* ui-name mixins body &form &env
    {:DevTools xf/DevTools
     :DerefFactory xf/DerefFactory
     :WithExclamation xf/with-exclamation}))
