(ns untangled.client.ui
  (:require
    [om.next :refer [lifecycle-sigs]]))

(defn process-meta-info [meta-info] meta-info)

(defn transform [body {:keys [meta-info comp-name factory-opts]}]
  (let [wrap-render
        (fn [x]
          (apply list
            (conj (vec (drop-last x))
                  (let [render-body (last x)]
                    `(wrap-render ~meta-info
                       ~{:klass comp-name
                         :this (first (second x))}
                       ~render-body)))))]
    (->> body
      (apply list)
      (reduce (fn [acc x]
                (conj acc (cond-> x
                            (and (seq? x)
                              (= 'render (first x)))
                            wrap-render)))
              [])
      (#(conj (apply list %) `(~'-deref [~'_] (om.next/factory ~comp-name ~factory-opts)) 'IDeref 'static))
      vec)))

(defn defui* [comp-name factory-opts body form]
  (assert (map? factory-opts)
          (str "invalid factory-opts <" factory-opts ">, it should be a map"))
  (let [meta-info (process-meta-info (merge (meta form) {:file cljs.analyzer/*cljs-file*}))]
    `(om.next/defui ^:once ~comp-name
       ~@(transform body
           {:meta-info meta-info
            :comp-name comp-name
            :factory-opts factory-opts}))))

(defmacro defui [comp-name factory-opts & body]
  (defui* comp-name factory-opts body &form))
