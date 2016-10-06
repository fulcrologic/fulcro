(ns untangled.client.xforms)

(defn with-exclamation [ctx body]
  (update-in body ["Object" :methods "render" :body]
    (fn [body]
      (conj (vec (butlast body))
            `(om.dom/div nil
               (om.dom/p nil "victory")
               ~(last body))))))

(defn with-booya [ctx body]
  (update-in body ["Object" :methods "render" :body]
    (fn [body]
      (conj (vec (butlast body))
            `(om.dom/div nil
               (om.dom/p nil "booya")
               ~(last body))))))
