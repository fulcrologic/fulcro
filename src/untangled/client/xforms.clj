(ns untangled.client.xforms)

(defn DevTools [{:keys [defui/loc defui/ui-name env/cljs?]} body _]
  (cond-> body cljs?
    (update-in ["Object" :methods "render"]
      (fn [{:as method :keys [body param-list]}]
        (assoc method :body
          (conj (vec (butlast body))
                `(untangled.client.ui/wrap-render ~loc
                   ~{:klass ui-name
                     :this (first param-list)}
                   ~(last body))))))))

(defn DerefFactory [{:keys [defui/ui-name env/cljs?]} body _]
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

(defn with-exclamation [_ body excl]
  (update-in body ["Object" :methods "render" :body]
    (fn [body]
      (conj (vec (butlast body))
            `(om.dom/div nil
               (om.dom/p nil ~(str excl))
               ~(last body))))))
