(ns untangled.client.ui-spec
  (:require
    [clojure.spec :as s]
    [om.next :as om]
    [untangled-spec.core :refer
     [specification behavior assertions when-mocking]]
    [untangled.client.ui :as uc-ui]))

(specification "i can parse a defui body with clojure.spec"
  (assertions
    (uc-ui/defui Root uc-ui/defui-middleware {:factory :opts}
      static om/Ident (ident [this] [:ui :singleton])
      static om/IQuery (query [this] [:some :query])
      Object (render [this] (dom/div nil "hello world")))
    =fn=> #(and
             (not (:s/problems %))
             (re-find #":some :query" (str %))
             (re-find #":ui :singleton" (str %))
             (re-find #"IDeref" (str %))
             (re-find #"untangled.client.ui/wrap-render" (str %)))))
