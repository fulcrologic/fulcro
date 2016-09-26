(ns untangled.client.ui)

(defn process-meta-info [meta-info] meta-info)

(defn add-render-wrapper [body {:keys [meta-info comp-name factory-opts]}]
  (let [wrap-render
        (fn [x]
          (apply list
            (conj (vec (drop-last x))
                  (let [render-body (last x)]
                    `(wrap-render ~meta-info
                       ~{:klass comp-name
                         :this (first (second x))}
                       ~render-body)))))]
    (reduce (fn [acc x]
              (conj acc (cond-> x
                          (and (seq? x)
                            (= 'render (first x)))
                          (wrap-render))))
            [] body)))

(defn add-deref-factory [body {:keys [comp-name factory-opts]}]
  (vec (concat
         ['static 'IDeref
          `(~'-deref [~'_] (om.next/factory ~comp-name ~factory-opts))]
         body)))

(defn defui* [comp-name factory-opts body form]
  (assert (map? factory-opts)
          (str "invalid factory-opts <" factory-opts ">, it should be a map"))
  (let [meta-info (process-meta-info (merge (meta form) {:file cljs.analyzer/*cljs-file*}))
        ctx {:meta-info meta-info, :comp-name comp-name, :factory-opts factory-opts}]
    `(om.next/defui ^:once ~comp-name
       ~@(-> body
           (add-deref-factory ctx)
           (add-render-wrapper ctx)))))

(defmacro defui [comp-name factory-opts & body]
  (defui* comp-name factory-opts body &form))
