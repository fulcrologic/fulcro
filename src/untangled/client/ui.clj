(ns untangled.client.ui
  (:require
    [om.next :refer [lifecycle-sigs]]
    [camel-snake-kebab.core :refer [->kebab-case]]))

(defn process-meta-info [meta-info] meta-info)

;; ====== defui+ ======

(defn transform+ [ui-methods]
  (let [statics {:query  (fn [v] `(~'static om/IQuery       (~'query  ~@(rest v))))
                 :ident  (fn [v] `(~'static om/Ident        (~'ident  ~@(rest v))))
                 :params (fn [v] `(~'static om/IQueryParams (~'params ~@(rest v))))}

        objects (fn [k] (fn [v] `(~(symbol (name k)) ~@(rest v))))

        separate #(reduce (fn [acc [k v]]
                            (update acc (if (contains? statics k)
                                          :statics :objects)
                                    conj [k v]))
                          {:statics [] :objects []}
                          %)]
    (-> ui-methods
        separate
        (update :statics #(mapcat (fn [[k v]] ((statics k) v)) %))
        (update :objects #(->> %
                               (map (fn [[k v]] ((objects k) v)))
                               (cons 'Object)))
        vals ((partial apply concat)))))

(defn defui+* [comp-name body form]
  (let [meta-info (process-meta-info (merge (meta form) {:file cljs.analyzer/*cljs-file*}))
        wrap-render (fn [meta-info]
                      (fn [fn-list]
                        (assert (= 3 (count fn-list)))
                        (apply list
                               (update (vec fn-list) 2
                                       (fn [body] `(wrap-render ~meta-info ~body))))))
        [ui-methods factory-opts] (-> (apply hash-map body)
                                      (#(vector (dissoc % :opts) (:opts %)))
                                      (update-in [0 :render] (wrap-render meta-info)))]
    `(do (om.next/defui ^:once ~comp-name
           ~@(transform+ ui-methods))
         (def ~(symbol (str "ui-" (->kebab-case comp-name)))
           (om.next/factory ~comp-name ~(or factory-opts {}))))))

(defmacro defui+ [comp-name & body]
  (defui+* comp-name body &form))

;; ====== defui ======

(defn transform [meta-info body]
  (let [wrap-render (fn [x]
                      (apply list
                             (update (vec x) 2
                                     (fn [render-body]
                                       `(wrap-render ~meta-info ~render-body)))))]
    (->> body
         (apply list)
         (reduce (fn [acc x]
                   (conj acc (cond-> x
                               (and (seq? x)
                                    (= 'render (first x)))
                               wrap-render)))
                 []))))

(defn defui* [comp-name factory-opts body form]
  (assert (map? factory-opts)
          (str "invalid factory-opts <" factory-opts ">, it should be a map"))
  (let [meta-info (process-meta-info (merge (meta form) {:file cljs.analyzer/*cljs-file*}))]
    `(do (om.next/defui ^:once ~comp-name
           ~@(transform meta-info body))
         (def ~(symbol (str "ui-" (->kebab-case comp-name)))
           (om.next/factory ~comp-name ~(or factory-opts {}))))))

(defmacro defui [comp-name factory-opts & body]
  (defui* comp-name factory-opts body &form))
