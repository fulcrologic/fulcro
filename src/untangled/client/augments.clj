(ns untangled.client.augments
  (:require
    [untangled.client.defui-augment :refer [defui-augment]]))

(defmethod defui-augment :DevTools [{:keys [defui/loc defui/ui-name env/cljs?]} ast _]
  (cond-> ast cljs?
    (update-in [:impls "Object" :methods "render"]
      (fn [{:as method :keys [body param-list]}]
        (assoc method :body
          (conj (vec (butlast body))
                `(untangled.client.ui/wrap-render ~loc
                   ~{:klass ui-name
                     :this (first param-list)}
                   ~(last body))))))))

(defmethod defui-augment :DerefFactory [{:keys [defui/ui-name env/cljs?]} ast _]
  (letfn [(get-factory-opts [ast]
            (when-let [{:keys [static methods]} (get-in ast [:impls "Defui"])]
              (assert static "Defui should be a static protocol")
              (assert (>= 1 (count methods))
                (str "There can only be factory-opts implemented on Defui, failing methods: " methods))
              (when (= 1 (count methods))
                (assert (get methods "factory-opts")
                  (str "You did not implement factory-opts, instead found: " methods))))
            (when-let [{:keys [param-list body]} (get-in ast [:impls "Defui" :methods "factory-opts"])]
              (assert (and (vector? param-list) (empty? param-list)))
              (assert (and (= 1 (count body))))
              (last body)))]
    (let [?factoryOpts (get-factory-opts ast)]
      (-> ast
        (assoc-in [:impls (if cljs? "IDeref" "clojure.lang.IDeref")]
          {:static 'static
           :protocol (if cljs? 'IDeref 'clojure.lang.IDeref)
           :methods {(if cljs? "-deref" "deref")
                     {:name (if cljs? '-deref 'deref)
                      :param-list '[_]
                      :body `[(om.next/factory ~ui-name
                                ~(or ?factoryOpts {}))]}}})
        (update-in [:impls] #(dissoc % "Defui"))))))

(defmethod defui-augment :WithExclamation [_ ast {:keys [excl]}]
  (update-in ast [:impls "Object" :methods "render" :body]
    (fn [body]
      (conj (vec (butlast body))
            `(om.dom/div nil
               (om.dom/p nil ~(str excl))
               ~(last body))))))
