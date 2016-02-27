(ns untangled.client.ui
  (:require [camel-snake-kebab.core :refer [->kebab-case]]))

(defn defui* [comp-name factory-opts body form]
  (let [info (merge (meta form) {:file *file*})]
    `(do (om.next/defui ^:once ~comp-name
           ~@body)
         (def ~(symbol (str "ui-" (->kebab-case comp-name)))
           (om.next/factory ~comp-name ~factory-opts)))))

(defmacro defui [comp-name factory-opts & body]
  (defui* comp-name factory-opts body &form))
