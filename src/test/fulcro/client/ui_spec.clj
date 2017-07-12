(ns fulcro.client.ui-spec
  (:require
    [clojure.test :as t]
    [om.next :as om]
    [fulcro-spec.core :refer
     [specification behavior assertions when-mocking]]
    [fulcro.client.augmentation :as aug]))

(specification "defui, TODO")

(comment
  (defmethod aug/defui-augmentation :fulcro.client.ui/WithExclamation
    [_ ast {:keys [excl]}]
    (aug/wrap-augment ast 'Object 'render
      (fn [_ body]
        `(om.dom/div nil
           (om.dom/p nil ~(str excl))
           ~body))))

  (aug/add-defui-augmentation-group :fulcro.client.ui/BuiltIns
    (fn [_augment]
      '[:fulcro.client.ui/DerefFactory
        (:fulcro.client.ui/WithExclamation {:excl "BuiltIns Engaged!"})])))
