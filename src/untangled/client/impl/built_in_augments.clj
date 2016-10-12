(ns untangled.client.impl.built-in-augments
  (:require
    [untangled.client.impl.util :as utl]
    [untangled.client.augmentation :as aug]))

(defmethod aug/defui-augmentation :untangled.client.ui/DevTools
  [{:keys [defui/loc defui/ui-name env/cljs?]} ast _]
  (cond-> ast cljs?
    (aug/wrap-augment 'Object 'render
      (fn [params body]
        `(untangled.client.ui/wrap-render ~loc
           ~{:klass ui-name
             :this (first params)}
           ~body)))))

(defmethod aug/defui-augmentation :untangled.client.ui/DerefFactory
  [{:keys [defui/ui-name env/cljs?]} ast args]
  (aug/inject-augment ast 'static
    (if cljs? 'IDeref 'clojure.lang.IDeref)
    (if cljs? '-deref 'deref)
    `(fn [_#] (om.next/factory ~ui-name ~(or args {})))))

(defmethod aug/defui-augmentation :untangled.client.ui/WithExclamation
  [_ ast {:keys [excl]}]
  (aug/wrap-augment ast 'Object 'render
    (fn [_ body]
      `(om.dom/div nil
         (om.dom/p nil ~(str excl))
         ~body))))

(aug/add-defui-augmentation-group :untangled.client.ui/BuiltIns
  (fn [_augment]
    '[:untangled.client.ui/DevTools :untangled.client.ui/DerefFactory
      (:untangled.client.ui/WithExclamation {:excl "BuiltIns Engaged!"})]))
