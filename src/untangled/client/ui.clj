(ns untangled.client.ui
  (:require
    [om.next :refer [lifecycle-sigs]]
    [camel-snake-kebab.core :refer [->kebab-case]]))

(defn process-meta-info [meta-info] meta-info)

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
