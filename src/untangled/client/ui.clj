(ns untangled.client.ui
  (:require
    [cljs.analyzer :as ana]
    [clojure.pprint :refer [pprint]]
    [clojure.spec :as s]
    [om.next :as om]
    [untangled.client.augmentation :as aug]
    [untangled.client.impl.built-in-augments]
    [untangled.client.impl.util :as utl]))

(defn- install-augments [ctx ast]
  (reduce
    (fn [ast {:keys [aug params]}]
      (aug/defui-augmentation (assoc ctx :augment/dispatch aug) ast params))
    (dissoc ast :augments :defui-name) (aug/parse-augments (:augments ast))))

(defn- make-ctx [ast form env]
  {:defui/loc (merge (meta form) {:file ana/*cljs-file*})
   :defui/ui-name (:defui-name ast)
   :env/cljs? (boolean (:ns env))})

(defn defui* [body form env]
  (try
    (let [ast (utl/conform! ::aug/defui body)
          {:keys [defui/ui-name env/cljs?] :as ctx} (make-ctx ast form env)]
      ((if cljs? om/defui* om/defui*-clj)
       (vary-meta ui-name assoc :once true)
       (->> ast
         (install-augments ctx)
         (s/unform ::aug/defui))))
    (catch Exception e
      (.println System/out e)
      (.println System/out (with-out-str (pprint (into [] (.getStackTrace e))))))))

(defmacro defui [& body]
  (defui* body &form &env))
