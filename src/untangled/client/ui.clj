(ns untangled.client.ui
  (:require
    [cljs.analyzer :as ana]
    [clojure.pprint :refer [pprint]]
    [clojure.spec :as s]
    [om.next :as om]
    [untangled.client.augmentation :as aug]
    [untangled.client.impl.built-in-augments]
    [untangled.client.util :as utl]))

(defn- install-augments [ctx ast]
  (reduce
    (fn [ast {:keys [aug params]}]
      (aug/defui-augmentation (assoc ctx :augment/dispatch aug) ast params))
    (dissoc ast :augments :defui-name) (aug/parse-augments (:augments ast))))

(defn- make-ctx [ast form env]
  {:defui/loc (merge (meta form) {:file ana/*cljs-file*})
   :defui/ui-name (:defui-name ast)
   :env/cljs? (boolean (:ns env))})

(s/fdef defui*
  :args ::aug/defui
  :ret ::aug/defui)
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

(s/fdef defui
  :args ::aug/defui
  :ret ::aug/defui)
(defmacro defui
  "Untangled's defui provides a way to compose transformations and behavior to your om next components.
   We're calling them *augments*, and they let you consume and provide behaviors
   in a declarative and transparent way.

   The untangled defui only provides an addition to the standard om.next/defui, a vector of augments,
   which are defined in `:untangled.client.augmentation/augments`, but roughly take the shape:
   [:some/augment ...] or a more granular approach {:always [...] :dev [...] :prod [...]}.

   Augments under `:always` are always used, and those under :dev and :prod are controlled by
   `untangled.client.augmentation/defui-augment-mode` (env var or jvm system prop `\"DEFUI_AUGMENT_MODE\"`).

   WARNING: Should be considered alpha tech, and the interface may be subject to changes
   depending on feedback and hammock time.

   NOTE: If anything isn't working right, or you just have some questions/comments/feedback,
   let @adambros know on the clojurians slack channel #untangled"
  [& body] (defui* body &form &env))
