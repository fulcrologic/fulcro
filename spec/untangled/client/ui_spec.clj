(ns untangled.client.ui-spec
  (:require
    [clojure.spec :as s]
    [clojure.test :as t]
    [om.next :as om]
    [untangled-spec.core :refer
     [specification behavior assertions when-mocking]]
    [untangled.client.augmentation :as aug]))

(specification "defui, TODO")

(comment
  (defmethod aug/defui-augmentation :untangled.client.ui/WithExclamation
    [_ ast {:keys [excl]}]
    (aug/wrap-augment ast 'Object 'render
      (fn [_ body]
        `(om.dom/div nil
           (om.dom/p nil ~(str excl))
           ~body))))

  (aug/add-defui-augmentation-group :untangled.client.ui/BuiltIns
    (fn [_augment]
      '[:untangled.client.ui/DerefFactory
        (:untangled.client.ui/WithExclamation {:excl "BuiltIns Engaged!"})])))
